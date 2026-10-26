package it.wallettracker.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import it.wallettracker.IntegrationTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Il controllo di salute che usa Docker (healthcheck del servizio backend, docs/44): {@code /actuator/health}
 * risponde "UP" senza dettagli, e gli altri endpoint di Actuator non ci sono.
 *
 * <p>Come {@link UploadLimitTest}, con il server web vero su una porta libera.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(IntegrationTestConfiguration.class)
class HealthEndpointTest {

    @LocalServerPort
    int port;

    @Test
    void healthIsUpWithoutDetails() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        // "groups" sono i nomi dei gruppi di controlli (liveness, readiness): niente di riservato. I dettagli
        // ("components", con il database e lo spazio su disco) non devono esserci.
        assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("components", "details");
    }

    /** Gli endpoint che mostrerebbero la configurazione (password comprese) o i log restano spenti. */
    @Test
    void theOtherActuatorEndpointsAreNotExposed() throws Exception {
        for (String path : new String[] {"/actuator/env", "/actuator/configprops", "/actuator/loggers",
                "/actuator/heapdump", "/actuator/beans"}) {
            assertThat(get(path).statusCode()).as(path).isEqualTo(404);
        }
    }

    /**
     * Anche Actuator passa dal "buttafuori" ({@link LocalOnlyRequestFilter}): una richiesta che modifica, partita
     * da un altro sito, è rifiutata prima di arrivarci. (L'header Host il client di Java non lo lascia cambiare:
     * quel caso lo prova {@code LocalOnlyRequestFilterTest}.)
     */
    @Test
    void actuatorIsBehindTheRequestFilter() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                .header("Origin", "https://evil.example")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
