package it.wallettracker.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import it.wallettracker.security.SecretCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Mistral e Gemini con le <b>librerie vere</b> (quelle di Spring AI e di Google) e un servizio <b>finto</b>: un
 * piccolo server HTTP del JDK, come in ModelDownloadTest. Così vediamo che cosa parte davvero verso i servizi
 * (indirizzo, chiave, modello, schema della risposta) senza chiavi vere e senza internet, e proviamo i loro errori.
 */
class CloudChatTest {

    @TempDir
    Path tempDir;

    /** Una richiesta ricevuta dal servizio finto. */
    record Received(String method, String path, String authorization, String googleKey, String body) {
    }

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String reply;

    private SecretCipher cipher;
    private AiSettings settings;
    private AiChatClients chatClients;
    private CategoryModel categoryModel;

    static final CategoryQuestion QUESTION = new CategoryQuestion("ESSELUNGA MILANO", true,
            List.of(new CategoryQuestion.Option(12L, "Spesa alimentare"), new CategoryQuestion.Option(15L, "Ristoranti")),
            List.of());

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("x-goog-api-key"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();

        // Le chiavi, cifrate come nel database. Le impostazioni sono finte: diamo solo la chiave cifrata.
        cipher = new SecretCipher(tempDir.resolve("encryption.key").toString());
        settings = mock(AiSettings.class);
        AiSettingsRepository repository = mock(AiSettingsRepository.class);
        when(repository.findById(AiSettings.ID)).thenReturn(Optional.of(settings));
        saveKey(AiProvider.MISTRAL, "mistral-key-123456");
        saveKey(AiProvider.GEMINI, "gemini-key-654321");

        chatClients = new AiChatClients(ChatClient.builder(mock(ChatModel.class)), repository, cipher, url, url,
                Duration.ofMillis(10)); // tra un tentativo e l'altro, nei test, non aspettiamo 5 secondi
        categoryModel = new CategoryModel(chatClients);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void saveKey(AiProvider provider, String key) {
        when(settings.encryptedApiKey(provider)).thenReturn(cipher.encrypt(key, AiChatClients.keyPurpose(provider)));
    }

    /** La risposta di Mistral (la stessa forma di quella di OpenAI): il testo del modello è in message.content. */
    static String mistralAnswer(String content) {
        return """
                {"id": "cmpl-1", "object": "chat.completion", "created": 1792600000, "model": "mistral-small-latest",
                 "choices": [{"index": 0, "finish_reason": "stop",
                              "message": {"role": "assistant", "content": %s}}],
                 "usage": {"prompt_tokens": 120, "completion_tokens": 8, "total_tokens": 128}}
                """.formatted(quoted(content));
    }

    /** La risposta di Gemini: il testo del modello è in candidates[0].content.parts[0].text. */
    static String geminiAnswer(String content) {
        return """
                {"candidates": [{"content": {"role": "model", "parts": [{"text": %s}]}, "finishReason": "STOP"}],
                 "usageMetadata": {"promptTokenCount": 120, "candidatesTokenCount": 8, "totalTokenCount": 128},
                 "modelVersion": "gemini-flash-latest"}
                """.formatted(quoted(content));
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\"", "\\\"") + "\"";
    }

    @Test
    void mistralGetsTheKeyTheModelAndTheSchema() {
        reply = mistralAnswer("{\"categoryId\": 12}");

        Optional<Long> answer = categoryModel.suggest(QUESTION, new AiTarget(AiProvider.MISTRAL, "mistral-small-latest"));

        assertThat(answer).contains(12L);
        Received request = received.getFirst();
        System.out.println("MISTRAL " + request.path() + "\n" + request.body());
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/chat/completions");
        assertThat(request.authorization()).isEqualTo("Bearer mistral-key-123456"); // la chiave decifrata
        assertThat(request.body()).contains("\"model\":\"mistral-small-latest\"", "\"temperature\":0.0",
                "\"categoryId\"", "Merchant: ESSELUNGA MILANO");
    }

    @Test
    void geminiGetsTheKeyTheModelAndTheSchema() {
        reply = geminiAnswer("{\"categoryId\": 12}");

        Optional<Long> answer = categoryModel.suggest(QUESTION, new AiTarget(AiProvider.GEMINI, "gemini-flash-latest"));

        assertThat(answer).contains(12L);
        Received request = received.getFirst();
        System.out.println("GEMINI " + request.path() + "\n" + request.body());
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).contains("models/gemini-flash-latest:generateContent");
        assertThat(request.googleKey()).isEqualTo("gemini-key-654321");
        assertThat(request.body()).contains("\"temperature\":0.0", "\"categoryId\"", "Merchant: ESSELUNGA MILANO");
    }

    @Test
    void aWrongKeyGivesAMessageThatSaysWhatToDo() {
        status = 401;
        reply = "{\"message\": \"Unauthorized\", \"request_id\": \"abc\"}";

        assertThatThrownBy(() -> categoryModel.suggest(QUESTION, new AiTarget(AiProvider.MISTRAL, "mistral-small-latest")))
                .satisfies(e -> assertThat(AiErrors.describe(e, AiProvider.MISTRAL))
                        .startsWith("La chiave di Mistral AI non è valida"));
        assertThat(received).hasSize(1); // una chiave sbagliata non si riprova

        // Google, con una chiave sbagliata, risponde 400 (non 401): la risposta vera, vista provando dal vivo.
        status = 400;
        reply = """
                {"error": {"code": 400, "message": "API key not valid. Please pass a valid API key.",
                 "status": "INVALID_ARGUMENT", "details": [{"@type": "type.googleapis.com/google.rpc.ErrorInfo",
                 "reason": "API_KEY_INVALID", "domain": "googleapis.com"}]}}""";
        assertThatThrownBy(() -> categoryModel.suggest(QUESTION, new AiTarget(AiProvider.GEMINI, "gemini-flash-latest")))
                .satisfies(e -> assertThat(AiErrors.describe(e, AiProvider.GEMINI))
                        .startsWith("La chiave di Google Gemini non è valida"));
    }

    @Test
    void tooManyRequestsIsRetriedAndThenExplained() {
        status = 429;
        reply = "{\"message\": \"Requests rate limit exceeded\"}";

        assertThatThrownBy(() -> categoryModel.suggest(QUESTION, new AiTarget(AiProvider.MISTRAL, "mistral-small-latest")))
                .satisfies(e -> assertThat(AiErrors.describe(e, AiProvider.MISTRAL)).contains("troppe richieste"));
        assertThat(received).hasSize(3); // la prima volta e due nuovi tentativi
    }

    @Test
    void afterATooManyRequestsTheNextTryCanWork() {
        // Il limite al minuto passa: al secondo tentativo il servizio risponde.
        status = 429;
        reply = "{\"message\": \"Requests rate limit exceeded\"}";
        server.createContext("/v1/chat/completions", exchange -> {
            received.add(new Received("POST", "/v1/chat/completions", null, null, ""));
            boolean first = received.size() == 1;
            byte[] bytes = (first ? reply : mistralAnswer("{\"categoryId\": 12}")).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(first ? 429 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        assertThat(categoryModel.suggest(QUESTION, new AiTarget(AiProvider.MISTRAL, "mistral-small-latest"))).contains(12L);
        assertThat(received).hasSize(2);
    }

    @Test
    void aNewKeyIsUsedRightAway() {
        reply = mistralAnswer("{\"categoryId\": 15}");
        categoryModel.suggest(QUESTION, new AiTarget(AiProvider.MISTRAL, "mistral-small-latest"));

        saveKey(AiProvider.MISTRAL, "mistral-key-NEW-999");
        categoryModel.suggest(QUESTION, new AiTarget(AiProvider.MISTRAL, "mistral-small-latest"));

        assertThat(received).extracting(Received::authorization)
                .containsExactly("Bearer mistral-key-123456", "Bearer mistral-key-NEW-999");
    }
}
