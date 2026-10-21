package it.wallettracker.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaApi;

/**
 * Lo scaricamento con un OllamaApi <b>vero</b> (la rete di Spring AI, con Reactor Netty) e un Ollama finto: un
 * piccolo server HTTP del JDK che risponde come Ollama. Serve a provare quello che con un OllamaApi finto
 * (AiControllerTest) non si vede: <b>su quale thread</b> arriva la fine dello stream.
 *
 * <p>È il difetto trovato provando l'app (docs/27, Parte E5): la fine dello stream arriva su un thread di Reactor
 * Netty, dove una chiamata bloccante (l'elenco dei modelli) è vietata. Ogni scaricamento riuscito finiva con
 * "Ollama ha finito senza scaricare il modello".
 */
class ModelDownloadTest {

    private HttpServer server;
    private boolean installed;

    @BeforeEach
    void startFakeOllama() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); // porta 0: una libera qualsiasi
        server.createContext("/api/pull", exchange -> {
            installed = true; // come Ollama: alla fine dello stream il modello c'è
            reply(exchange, "application/x-ndjson", """
                    {"status":"pulling manifest"}
                    {"status":"verifying sha256 digest"}
                    {"status":"writing manifest"}
                    {"status":"success"}
                    """);
        });
        server.createContext("/api/tags", exchange -> reply(exchange, "application/json", installed
                ? "{\"models\":[{\"name\":\"gemma3:4b\",\"model\":\"gemma3:4b\",\"size\":1,\"digest\":\"d\"}]}"
                : "{\"models\":[]}"));
        server.start();
    }

    @AfterEach
    void stopFakeOllama() {
        server.stop(0);
    }

    private static void reply(HttpExchange exchange, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void aSuccessfulDownloadEndsWithSuccess() throws InterruptedException {
        OllamaApi ollamaApi = OllamaApi.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
        ModelDownload download = new ModelDownload(ollamaApi);

        download.start("gemma3:4b");
        for (int i = 0; i < 50 && download.state().running(); i++) {
            Thread.sleep(100);
        }

        assertThat(download.state().error()).isNull();
        assertThat(download.state().status()).isEqualTo("success");
    }
}
