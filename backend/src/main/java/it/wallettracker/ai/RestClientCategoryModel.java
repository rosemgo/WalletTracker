package it.wallettracker.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * La categoria con <b>RestClient</b>: la richiesta HTTP a Ollama la scriviamo noi, come per Enable Banking.
 * Fa la stessa cosa di {@link SpringAiCategoryModel}: confrontale riga per riga (docs/28).
 *
 * <p>La chiamata è {@code POST /api/chat} (vedi la Parte B della guida):
 * <pre>
 * {"model": "gemma3:4b", "stream": false, "options": {"temperature": 0},
 *  "format": {lo schema JSON della risposta},
 *  "messages": [{"role": "system", "content": "..."}, {"role": "user", "content": "..."}]}
 * </pre>
 * e la risposta ha il testo del modello in {@code message.content}: un testo che è a sua volta JSON
 * ({@code {"categoryId": 12}}), da leggere una seconda volta.
 */
@Component
public class RestClientCategoryModel implements CategoryModel {

    /**
     * Lo schema JSON della risposta, scritto a mano: un oggetto con un solo campo, categoryId, intero e
     * obbligatorio. Con Spring AI lo stesso schema lo ricava la libreria dal record {@link CategoryPrompt.Answer}
     * (il test CategoryModelContractTest mostra i due JSON: uguali, a parte l'indicazione "$schema" della
     * versione dello standard e "format": "int64" del tipo Long, che Ollama ignora).
     */
    static final Map<String, Object> ANSWER_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("categoryId", Map.of("type", "integer")),
            "required", List.of("categoryId"),
            "additionalProperties", false);

    /** La richiesta di /api/chat: i campi con i nomi che vuole Ollama. */
    record ChatRequest(String model, List<Message> messages, boolean stream, Object format,
            Map<String, Object> options) {
    }

    /** Un messaggio: role è "system", "user" o "assistant" (le risposte del modello). */
    record Message(String role, String content) {
    }

    /** La parte della risposta che ci serve: il messaggio del modello (gli altri campi li ignoriamo). */
    record ChatResponse(Message message) {
    }

    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    @Autowired
    public RestClientCategoryModel(RestClient.Builder builder, JsonMapper jsonMapper,
            @Value("${spring.ai.ollama.base-url}") String ollamaUrl) {
        // Un modello sulla CPU può metterci decine di secondi (soprattutto la prima volta, quando si carica in
        // memoria): un tempo massimo generoso, ma non infinito.
        this(builder.baseUrl(ollamaUrl).requestFactory(requestFactory(Duration.ofMinutes(3))).build(), jsonMapper);
    }

    /** Per i test: un RestClient già pronto (collegato a un server finto, vedi RestClientCategoryModelTest). */
    RestClientCategoryModel(RestClient restClient, JsonMapper jsonMapper) {
        this.restClient = restClient;
        this.jsonMapper = jsonMapper;
    }

    private static JdkClientHttpRequestFactory requestFactory(Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(readTimeout);
        return requestFactory;
    }

    @Override
    public Optional<Long> suggest(CategoryQuestion question, String model) {
        ChatRequest request = new ChatRequest(model,
                List.of(new Message("system", CategoryPrompt.SYSTEM),
                        new Message("user", CategoryPrompt.user(question))),
                false, ANSWER_SCHEMA, Map.of("temperature", 0));
        ChatResponse response = restClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()        // un errore HTTP (es. 404 "model not found") diventa un'eccezione
                .body(ChatResponse.class);
        if (response == null || response.message() == null) {
            throw new IllegalStateException("Ollama ha risposto senza un messaggio");
        }
        // Il contenuto è un testo JSON: lo leggiamo come Answer. Se il modello non avesse rispettato lo schema,
        // qui avremmo un'eccezione; con "format" Ollama lo impone, ma non si sa mai.
        CategoryPrompt.Answer answer = jsonMapper.readValue(response.message().content(), CategoryPrompt.Answer.class);
        return CategoryPrompt.validated(answer, question);
    }

    @Override
    public AiClient client() {
        return AiClient.REST_CLIENT;
    }
}
