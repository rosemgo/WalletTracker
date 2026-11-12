package it.wallettracker.ai;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import it.wallettracker.security.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Il {@link ChatClient} giusto per ogni fornitore (passo 7c). È il punto in cui si vede il vantaggio di Spring AI:
 * Ollama e Gemini sono due "modelli" diversi ({@code ChatModel}), ma sopra c'è lo stesso
 * {@code ChatClient}, e {@link CategoryModel} fa la stessa domanda a tutti e due.
 *
 * <ul>
 *   <li><b>Ollama</b>: il ChatClient lo crea Spring all'avvio (configurazione automatica dello starter), con
 *       l'indirizzo di application.yml;</li>
 *   <li><b>Gemini</b>: la chiave la inserisce l'utente dalla pagina Impostazioni e sta nel database,
 *       cifrata. Quindi il modello non si può creare all'avvio: lo costruiamo noi la prima volta che serve, con
 *       la chiave decifrata, e lo teniamo da parte. Se l'utente cambia la chiave, lo ricostruiamo.</li>
 * </ul>
 * Le opzioni (modello e temperatura) invece vanno con ogni domanda: si cambiano senza ricostruire niente.
 */
@Component
public class AiChatClients {

    private static final Logger log = LoggerFactory.getLogger(AiChatClients.class);

    /** I codici HTTP che vale la pena riprovare: "troppe richieste" (429) e "problema temporaneo" (500, 503). */
    private static final Set<Integer> RETRY_CODES = Set.of(429, 500, 503);

    /** L'etichetta con cui le chiavi sono cifrate (vedi SecretCipher): una per servizio. */
    static String keyPurpose(AiProvider cloud) {
        return "ai." + cloud.name().toLowerCase() + ".api_key";
    }

    /**
     * Quando riprovare una domanda andata male. Mai per una chiave sbagliata (400/401) o un modello che non esiste
     * (404): riprovando non cambia niente. Sì per gli errori "passeggeri" ({@link TransientAiException}, 429, 500,
     * 503): il piano gratuito ha un limite al minuto, e i server ogni tanto sono sovraccarichi.
     *
     * <p>I tentativi li facciamo <b>solo noi</b>: quelli della libreria di Google li spegniamo (vedi
     * {@link #chatModel}). Prima ce n'erano due livelli, uno dentro l'altro (fino a 5 x 3 richieste, decine di
     * secondi senza che nessuno lo sapesse); ora ce n'è uno, e ogni tentativo fallito finisce nel log con il motivo.
     *
     * @param firstDelay l'attesa prima del primo nuovo tentativo (poi raddoppia); i test usano pochi millisecondi
     */
    static RetryTemplate retry(AiProvider provider, Duration firstDelay) {
        RetryTemplate template = new RetryTemplate(RetryPolicy.builder()
                .predicate(e -> e instanceof TransientAiException
                        || AiErrors.httpCode(e).filter(RETRY_CODES::contains).isPresent())
                .maxRetries(2)
                .delay(firstDelay)
                .multiplier(2)
                .build());
        template.setRetryListener(new RetryListener() {
            @Override
            public void onRetryableExecution(RetryPolicy policy, Retryable<?> retryable, RetryState state) {
                if (!state.isSuccessful()) {
                    log.warn("{}: tentativo {} non riuscito: {}", provider.label, state.getRetryCount() + 1,
                            AiErrors.describe(state.getLastException(), provider));
                }
            }
        });
        return template;
    }

    /** Un client già costruito, con la chiave (cifrata) con cui l'abbiamo costruito. */
    private record Built(String encryptedKey, ChatClient client) {
    }

    private final ChatClient ollama;
    private final AiSettingsRepository settingsRepository;
    private final SecretCipher cipher;
    private final String geminiBaseUrl;
    private final Duration retryDelay;
    private final Map<AiProvider, Built> built = new ConcurrentHashMap<>();

    /**
     * L'indirizzo di Gemini si può cambiare solo nella configurazione (i test lo puntano a un server finto): vuoto
     * vuol dire "quello di Google".
     */
    @Autowired
    public AiChatClients(ChatClient.Builder ollamaChatClientBuilder, AiSettingsRepository settingsRepository,
            SecretCipher cipher, @Value("${wallettracker.ai.gemini.base-url:}") String geminiBaseUrl) {

        this(ollamaChatClientBuilder, settingsRepository, cipher, geminiBaseUrl, Duration.ofSeconds(5));

    }

    /** Per i test: con un'attesa breve tra i tentativi. */
    AiChatClients(ChatClient.Builder ollamaChatClientBuilder, AiSettingsRepository settingsRepository,
            SecretCipher cipher, String geminiBaseUrl, Duration retryDelay) {
        this.retryDelay = retryDelay;
        this.ollama = ollamaChatClientBuilder.build();
        this.settingsRepository = settingsRepository;
        this.cipher = cipher;
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
            case GEMINI -> GoogleGenAiChatOptions.builder().model(target.model()).temperature(0.0);
            case NONE -> throw new IllegalStateException("L'IA è spenta");
        };
    }

    /**
     * Il "modello" di Spring AI per un servizio cloud. Per Gemini Spring AI usa sotto la libreria ufficiale di Google
     * ({@code com.google.genai.Client}), ma sopra ha la stessa forma di Ollama.
     */
    private org.springframework.ai.chat.model.ChatModel chatModel(AiProvider provider, String apiKey) {
        return switch (provider) {
            case GEMINI -> {
                // attempts(1): un solo tentativo per la libreria di Google, i nuovi tentativi li fa retry().
                HttpOptions.Builder http = HttpOptions.builder()
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build());
                if (!geminiBaseUrl.isBlank()) {
                    http.baseUrl(geminiBaseUrl);
                }
                Client.Builder client = Client.builder().apiKey(apiKey).httpOptions(http.build());
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
