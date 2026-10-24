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
 * Gemini con le <b>librerie vere</b> (quelle di Spring AI e di Google) e un servizio <b>finto</b>: un
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
        saveKey(AiProvider.GEMINI, "gemini-key-654321");

        chatClients = new AiChatClients(ChatClient.builder(mock(ChatModel.class)), repository, cipher, url,
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

    /** La risposta di Gemini: il testo del modello è in candidates[0].content.parts[0].text. */
    static String geminiAnswer(String content) {
        return """
                {"candidates": [{"content": {"role": "model", "parts": [{"text": %s}]}, "finishReason": "STOP"}],
                 "usageMetadata": {"promptTokenCount": 120, "candidatesTokenCount": 8, "totalTokenCount": 140,
                                   "thoughtsTokenCount": 12},
                 "modelVersion": "gemini-3.8-flash"}
                """.formatted(quoted(content));
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\"", "\\\"") + "\"";
    }

    @Test
    void geminiGetsTheKeyTheModelAndTheSchema() {
        reply = geminiAnswer("{\"categoryId\": 12}");

        CategoryModel.Reply answer = categoryModel.ask(QUESTION, new AiTarget(AiProvider.GEMINI, "gemini-flash-latest"));

        assertThat(answer.categoryId()).contains(12L);
        assertThat(answer.model()).isEqualTo("gemini-3.8-flash"); // il modello vero dietro l'alias
        assertThat(answer.thinkingTokens()).isEqualTo(12);
        Received request = received.getFirst();
        System.out.println("GEMINI " + request.path() + "\n" + request.body());
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).contains("models/gemini-flash-latest:generateContent");
        assertThat(request.googleKey()).isEqualTo("gemini-key-654321");
        assertThat(request.body()).contains("\"temperature\":0.0", "\"categoryId\"", "Merchant: ESSELUNGA MILANO");
    }

    @Test
    void aWrongKeyGivesAMessageThatSaysWhatToDo() {
        // Google, con una chiave sbagliata, risponde 400 (non 401): la risposta vera, vista provando dal vivo.
        status = 400;
        reply = """
                {"error": {"code": 400, "message": "API key not valid. Please pass a valid API key.",
                 "status": "INVALID_ARGUMENT", "details": [{"@type": "type.googleapis.com/google.rpc.ErrorInfo",
                 "reason": "API_KEY_INVALID", "domain": "googleapis.com"}]}}""";

        assertThatThrownBy(() -> categoryModel.suggest(QUESTION, new AiTarget(AiProvider.GEMINI, "gemini-flash-latest")))
                .satisfies(e -> assertThat(AiErrors.describe(e, AiProvider.GEMINI))
                        .startsWith("La chiave di Google Gemini non è valida"));
        assertThat(received).hasSize(1); // una chiave sbagliata non si riprova
    }

    @Test
    void tooManyRequestsIsRetriedAndThenExplained() {
        status = 429;
        reply = """
                {"error": {"code": 429, "message": "Resource has been exhausted (e.g. check quota).",
                 "status": "RESOURCE_EXHAUSTED"}}""";

        assertThatThrownBy(() -> categoryModel.suggest(QUESTION, new AiTarget(AiProvider.GEMINI, "gemini-flash-latest")))
                .satisfies(e -> assertThat(AiErrors.describe(e, AiProvider.GEMINI)).contains("troppe richieste"));
        // La prima volta e due nuovi tentativi, nostri: quelli della libreria di Google sono spenti. Prima di
        // spegnerli le richieste erano 5 (solo Google), e il test durava 17 secondi.
        assertThat(received).hasSize(3);
    }

    @Test
    void afterAnOverloadTheNextTryCanWork() {
        // 503 "sovraccarico" la prima volta, poi la risposta.
        server.removeContext("/");
        server.createContext("/", exchange -> {
            received.add(new Received("POST", exchange.getRequestURI().toString(), null, null, ""));
            boolean first = received.size() == 1;
            byte[] bytes = (first ? "{\"error\": {\"code\": 503, \"message\": \"The model is overloaded.\"}}"
                    : geminiAnswer("{\"categoryId\": 12}")).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(first ? 503 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        assertThat(categoryModel.suggest(QUESTION, new AiTarget(AiProvider.GEMINI, "gemini-flash-latest"))).contains(12L);
        assertThat(received).hasSize(2);
    }

    @Test
    void aNewKeyIsUsedRightAway() {
        reply = geminiAnswer("{\"categoryId\": 15}");
        categoryModel.suggest(QUESTION, new AiTarget(AiProvider.GEMINI, "gemini-flash-latest"));

        saveKey(AiProvider.GEMINI, "gemini-key-NEW-999");
        categoryModel.suggest(QUESTION, new AiTarget(AiProvider.GEMINI, "gemini-flash-latest"));

        assertThat(received).extracting(Received::googleKey).containsExactly("gemini-key-654321", "gemini-key-NEW-999");
    }
}
