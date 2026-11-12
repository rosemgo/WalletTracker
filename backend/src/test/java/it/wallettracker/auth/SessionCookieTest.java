package it.wallettracker.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import it.wallettracker.IntegrationTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Il cookie della sessione, come lo scrive davvero Tomcat (docs/46, parte B): con MockMvc non si vedrebbe, perché
 * gli attributi li aggiunge il server web. Qui il server è vero, su una porta libera.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(IntegrationTestConfiguration.class)
class SessionCookieTest {

    @LocalServerPort
    int port;

    @Autowired
    AppPinRepository repository;

    /**
     * "Senza PIN" dal PC si entra senza sessione: nessun cookie WALLETTRACKER_SESSION, quindi niente da rubare. Il test
     * parte da "nessuna scelta" e alla fine la cancella, perché gli altri test con il server vero vogliono il PIN.
     */
    @Test
    void withoutPinThePcGetsNoSessionCookie() throws Exception {
        repository.deleteAll();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> status = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/auth/status")).build(), HttpResponse.BodyHandlers.ofString());
            String token = cookieValue(status.headers().allValues("Set-Cookie"), "XSRF-TOKEN");
            HttpResponse<String> choice = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/auth/no-pin"))
                    .header("Cookie", "XSRF-TOKEN=" + token).header("X-XSRF-TOKEN", token)
                    .header(TestSession.LOCAL_HEADER, "local")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(choice.statusCode()).as(choice.body()).isEqualTo(204);

            HttpResponse<String> accounts = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/accounts"))
                    .header(TestSession.LOCAL_HEADER, "local").build(), HttpResponse.BodyHandlers.ofString());
            assertThat(accounts.statusCode()).isEqualTo(200);
            assertThat(accounts.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("WALLETTRACKER_SESSION="));
        } finally {
            repository.deleteAll();
        }
    }

    @Test
    void theSessionCookieIsHttpOnlyAndSameSiteStrict() throws Exception {
        String cookie = sessionCookie(null);

        assertThat(cookie).startsWith("WALLETTRACKER_SESSION=").contains("HttpOnly").contains("SameSite=Strict");
        // Su http (come http://localhost) niente Secure: il browser non lo rimanderebbe.
        assertThat(cookie).doesNotContain("Secure");
    }

    /**
     * Quando nginx dice "il browser è arrivato in https" (X-Forwarded-Proto, con Tailscale), il cookie diventa Secure:
     * il browser lo manderà solo su https. Tomcat crede all'header perché arriva da un indirizzo interno (qui
     * 127.0.0.1; con Docker, la rete interna).
     */
    @Test
    void behindHttpsTheCookieIsAlsoSecure() throws Exception {
        assertThat(sessionCookie("https")).contains("Secure");
    }

    /** Apre una sessione (crea il PIN o lo inserisce) e restituisce l'header Set-Cookie della sessione. */
    private String sessionCookie(String forwardedProto) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest.Builder status = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/status"));
            if (forwardedProto != null) {
                status.header("X-Forwarded-Proto", forwardedProto);
            }
            HttpResponse<String> answer = client.send(status.build(), HttpResponse.BodyHandlers.ofString());
            String token = cookieValue(answer.headers().allValues("Set-Cookie"), "XSRF-TOKEN");
            String path = answer.body().contains("\"pinSet\":true") ? "/api/auth/login" : "/api/auth/pin";

            HttpRequest.Builder login = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .header("Cookie", "XSRF-TOKEN=" + token)
                    .header("X-XSRF-TOKEN", token)
                    .header(TestSession.LOCAL_HEADER, "local")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"pin\":\"" + TestSession.TEST_PIN + "\"}"));
            if (forwardedProto != null) {
                login.header("X-Forwarded-Proto", forwardedProto);
            }
            HttpResponse<String> in = client.send(login.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(in.statusCode() / 100).as(in.body()).isEqualTo(2);
            return in.headers().allValues("Set-Cookie").stream()
                    .filter(header -> header.startsWith("WALLETTRACKER_SESSION="))
                    .findFirst().orElseThrow();
        }
    }

    private static String cookieValue(List<String> setCookies, String name) {
        return setCookies.stream()
                .filter(header -> header.startsWith(name + "="))
                .map(header -> header.substring(name.length() + 1, header.indexOf(';')))
                .findFirst().orElseThrow();
    }
}
