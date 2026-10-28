package it.wallettracker.auth;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Per i test con il server web vero ({@code RANDOM_PORT}): un "browser" che ha inserito il PIN.
 *
 * <p>Fa quello che fa la dashboard: chiede lo stato ({@code /api/auth/status}, che dà il cookie XSRF-TOKEN), crea il
 * PIN o lo inserisce, e tiene i cookie (la sessione e il gettone CSRF). Le richieste che modificano qualcosa partono
 * con l'header X-XSRF-TOKEN, come le manda Angular.
 */
public final class TestSession implements AutoCloseable {

    /** Il PIN dei test: inventato, solo per i test. */
    public static final String TEST_PIN = "135792";

    private final String base;
    private final CookieManager cookies = new CookieManager();
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

    private TestSession(int port) {
        this.base = "http://localhost:" + port;
    }

    /** Apre una sessione: crea il PIN dei test se non c'è ancora, altrimenti lo inserisce. */
    public static TestSession open(int port) throws IOException, InterruptedException {
        TestSession session = new TestSession(port);
        String status = session.send(session.request("/api/auth/status").GET()).body();
        String path = status.contains("\"pinSet\":true") ? "/api/auth/login" : "/api/auth/pin";
        HttpResponse<String> answer = session.send(session.request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"pin\":\"" + TEST_PIN + "\"}")));
        if (answer.statusCode() != 204) {
            throw new IllegalStateException("Sessione dei test non aperta: " + answer.statusCode() + " " + answer.body());
        }
        return session;
    }

    /** Una richiesta verso il server dei test, con il gettone CSRF (se c'è già). */
    public HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path));
        String token = xsrfToken();
        return token == null ? builder : builder.header("X-XSRF-TOKEN", token);
    }

    public HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String xsrfToken() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .map(HttpCookie::getValue)
                .findFirst().orElse(null);
    }

    @Override
    public void close() {
        client.close();
    }
}
