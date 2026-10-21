package it.wallettracker.ai;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import it.wallettracker.security.SecretCipher;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.mistralai.MistralAiChatModel;
import org.springframework.ai.mistralai.MistralAiChatOptions;
import org.springframework.ai.mistralai.api.MistralAiApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Il {@link ChatClient} giusto per ogni fornitore (passo 7c). È il punto in cui si vede il vantaggio di Spring AI:
 * Ollama, Mistral e Gemini sono tre "modelli" diversi ({@code ChatModel}), ma sopra c'è lo stesso
 * {@code ChatClient}, e {@link CategoryModel} fa la stessa domanda a tutti e tre.
 *
 * <ul>
 *   <li><b>Ollama</b>: il ChatClient lo crea Spring all'avvio (configurazione automatica dello starter), con
 *       l'indirizzo di application.yml;</li>
 *   <li><b>Mistral e Gemini</b>: la chiave la inserisce l'utente dalla pagina Impostazioni e sta nel database,
 *       cifrata. Quindi il modello non si può creare all'avvio: lo costruiamo noi la prima volta che serve, con
 *       la chiave decifrata, e lo teniamo da parte. Se l'utente cambia la chiave, lo ricostruiamo.</li>
 * </ul>
 * Le opzioni (modello e temperatura) invece vanno con ogni domanda: si cambiano senza ricostruire niente.
 */
@Component
public class AiChatClients {

    /** L'etichetta con cui le chiavi sono cifrate (vedi SecretCipher): una per servizio. */
    static String keyPurpose(AiProvider cloud) {
        return "ai." + cloud.name().toLowerCase() + ".api_key";
    }

    /**
     * Quando riprovare una domanda andata male. Mai per una chiave sbagliata (401) o un modello che non esiste
     * (404): riprovando non cambia niente. Sì per gli errori "passeggeri" ({@link TransientAiException}: servizio
     * sovraccarico, rete con un singhiozzo) e, per Mistral, per "troppe richieste" (429): i piani gratuiti hanno un
     * limite al minuto, e dopo qualche secondo si passa. Spring AI considera il 429 di Mistral un errore definitivo,
     * quindi lo aggiungiamo noi. Per Gemini no: la libreria di Google riprova già da sola (fino a 5 volte), e
     * riprovare anche noi moltiplicherebbe i tentativi.
     *
     * @param firstDelay l'attesa prima del primo nuovo tentativo (poi raddoppia); i test usano pochi millisecondi
     */
    static RetryTemplate retry(AiProvider provider, Duration firstDelay) {
        return new RetryTemplate(RetryPolicy.builder()
                .predicate(e -> e instanceof TransientAiException
                        || (provider == AiProvider.MISTRAL && String.valueOf(e.getMessage()).contains("429")))
                .maxRetries(2)
                .delay(firstDelay)
                .multiplier(2)
                .build());
    }

    /** Un client già costruito, con la chiave (cifrata) con cui l'abbiamo costruito. */
    private record Built(String encryptedKey, ChatClient client) {
    }

    private final ChatClient ollama;
    private final AiSettingsRepository settingsRepository;
    private final SecretCipher cipher;
    private final String mistralBaseUrl;
    private final String geminiBaseUrl;
    private final Duration retryDelay;
    private final Map<AiProvider, Built> built = new ConcurrentHashMap<>();

    /**
     * Gli indirizzi dei servizi cloud si possono cambiare solo nella configurazione (i test li puntano a un server
     * finto). Per Gemini, vuoto vuol dire "quello di Google".
     */
    @Autowired
    public AiChatClients(ChatClient.Builder ollamaChatClientBuilder, AiSettingsRepository settingsRepository,
            SecretCipher cipher, @Value("${wallettracker.ai.mistral.base-url}") String mistralBaseUrl,
            @Value("${wallettracker.ai.gemini.base-url:}") String geminiBaseUrl) {
        this(ollamaChatClientBuilder, settingsRepository, cipher, mistralBaseUrl, geminiBaseUrl, Duration.ofSeconds(5));
    }

    /** Per i test: con un'attesa breve tra i tentativi. */
    AiChatClients(ChatClient.Builder ollamaChatClientBuilder, AiSettingsRepository settingsRepository,
            SecretCipher cipher, String mistralBaseUrl, String geminiBaseUrl, Duration retryDelay) {
        this.retryDelay = retryDelay;
        this.ollama = ollamaChatClientBuilder.build();
        this.settingsRepository = settingsRepository;
        this.cipher = cipher;
        this.mistralBaseUrl = mistralBaseUrl;
        this.geminiBaseUrl = geminiBaseUrl;
    }

    /** Il client per questo fornitore. Per il cloud serve la chiave salvata, altrimenti IllegalStateException. */
    @Transactional(readOnly = true)
    public ChatClient client(AiProvider provider) {
        if (provider == AiProvider.OLLAMA) {
            return ollama;
        }
        if (!provider.cloud()) {
            throw new IllegalStateException("L'IA è spenta");
        }
        String encryptedKey = settingsRepository.findById(AiSettings.ID).orElseThrow().encryptedApiKey(provider);
        if (encryptedKey == null) {
            throw new IllegalStateException("Manca la chiave di " + provider.label);
        }
        Built current = built.get(provider);
        if (current == null || !current.encryptedKey().equals(encryptedKey)) {
            // La prima volta, o la chiave è cambiata: costruiamo il modello con la chiave decifrata.
            String apiKey = cipher.decrypt(encryptedKey, keyPurpose(provider));
            current = new Built(encryptedKey, ChatClient.builder(chatModel(provider, apiKey)).build());
            built.put(provider, current);
        }
        return current.client();
    }

    /**
     * Le opzioni della domanda: il modello scelto e temperatura 0 (sempre la risposta più probabile, docs/28). Ogni
     * fornitore ha la sua classe di opzioni, ma tutte sono un {@code ChatOptions.Builder}: è quello che vuole
     * {@code ChatClient.prompt().options(...)}.
     */
    public static ChatOptions.Builder<?> options(AiTarget target) {
        return switch (target.provider()) {
            case OLLAMA -> OllamaChatOptions.builder().model(target.model()).temperature(0.0);
            case MISTRAL -> MistralAiChatOptions.builder().model(target.model()).temperature(0.0);
            case GEMINI -> GoogleGenAiChatOptions.builder().model(target.model()).temperature(0.0);
            case NONE -> throw new IllegalStateException("L'IA è spenta");
        };
    }

    /**
     * Il "modello" di Spring AI per un servizio cloud. Due librerie diverse sotto, la stessa forma sopra:
     * <ul>
     *   <li>Mistral: {@code MistralAiApi} fa le chiamate HTTP (con RestClient, come OllamaApi);</li>
     *   <li>Gemini: Spring AI usa la libreria ufficiale di Google ({@code com.google.genai.Client}).</li>
     * </ul>
     */
    private org.springframework.ai.chat.model.ChatModel chatModel(AiProvider provider, String apiKey) {
        return switch (provider) {
            case MISTRAL -> MistralAiChatModel.builder()
                    .mistralAiApi(MistralAiApi.builder().baseUrl(mistralBaseUrl).apiKey(apiKey).build())
                    .options(MistralAiChatOptions.builder().build())
                    .retryTemplate(retry(provider, retryDelay))
                    .build();
            case GEMINI -> {
                Client.Builder client = Client.builder().apiKey(apiKey);
                if (!geminiBaseUrl.isBlank()) {
                    client.httpOptions(HttpOptions.builder().baseUrl(geminiBaseUrl).build());
                }
                yield GoogleGenAiChatModel.builder()
                        .genAiClient(client.build())
                        .options(GoogleGenAiChatOptions.builder().build())
                        .retryTemplate(retry(provider, retryDelay))
                        .build();
            }
            default -> throw new IllegalArgumentException("Non è un servizio cloud: " + provider);
        };
    }
}
