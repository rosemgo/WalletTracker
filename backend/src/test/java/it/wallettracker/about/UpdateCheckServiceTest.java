package it.wallettracker.about;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** C'è una versione nuova? (docs/50, parte J), con un finto GitHub sul computer stesso. */
class UpdateCheckServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private HttpServer server;
    private volatile int status = 200;
    private volatile String body = release("v1.2.0", "https://github.com/rosemgo/WalletTracker/releases/tag/v1.2.0",
            "- una novità\\n- un'altra");
    private int calls;

    @BeforeEach
    void startFakeGitHub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/releases/latest", exchange -> {
            calls++;
            reply(exchange);
        });
        server.start();
    }

    @AfterEach
    void stopFakeGitHub() {
        server.stop(0);
    }

    private void reply(HttpExchange exchange) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String release(String tag, String url, String notes) {
        return """
                {"tag_name": "%s", "html_url": "%s", "body": "%s", "name": "x", "assets": []}
                """.formatted(tag, url, notes);
    }

    private UpdateCheckService service(String installed, boolean enabled) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/releases/latest";
        return new UpdateCheckService(new UpdateProperties(enabled, url, Duration.ofHours(24), Duration.ofMinutes(2)),
                installed, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void aNewerPublishedVersionIsAvailableWithItsNotesAndPage() {
        UpdateCheckService service = service("1.1.3", true);
        service.check();

        UpdateCheckService.UpdateStatus s = service.status();
        assertThat(s.available()).isTrue();
        assertThat(s.latestVersion()).isEqualTo("1.2.0");
        assertThat(s.currentVersion()).isEqualTo("1.1.3");
        assertThat(s.releaseUrl()).isEqualTo("https://github.com/rosemgo/WalletTracker/releases/tag/v1.2.0");
        assertThat(s.notes()).isEqualTo("- una novità\n- un'altra");
        assertThat(s.checkedAt()).isEqualTo(NOW);
    }

    @Test
    void theSameOrAnOlderVersionIsNotAnUpdate() {
        UpdateCheckService same = service("1.2.0", true);
        same.check();
        assertThat(same.status().available()).isFalse();
        assertThat(same.status().latestVersion()).isEqualTo("1.2.0");

        UpdateCheckService newer = service("1.10.0", true);
        newer.check();
        assertThat(newer.status().available()).isFalse();
    }

    @Test
    void versionsAreComparedAsNumbersNotAsText() {
        assertThat(UpdateCheckService.isNewer("1.10.0", "1.9.0")).isTrue();
        assertThat(UpdateCheckService.isNewer("2.0.0", "1.99.99")).isTrue();
        assertThat(UpdateCheckService.isNewer("1.0.1", "1.0.0")).isTrue();
        assertThat(UpdateCheckService.isNewer("1.0.0", "1.0.0")).isFalse();
        assertThat(UpdateCheckService.isNewer("1.0.0", "sviluppo")).isFalse();
    }

    @Test
    void fromTheSourceCodeOrWhenSwitchedOffGitHubIsNeverAsked() {
        UpdateCheckService development = service("sviluppo", true);
        development.check();
        assertThat(development.status().checkEnabled()).isFalse();

        UpdateCheckService off = service("1.0.0", false);
        off.check();
        assertThat(off.status().checkEnabled()).isFalse();
        assertThat(off.status().available()).isFalse();

        assertThat(calls).isZero();
    }

    @Test
    void aPageOutsideWalletTrackerReleasesIsNotLinked() {
        body = release("v1.2.0", "https://esempio.invalid/scarica-qui", "");
        UpdateCheckService service = service("1.0.0", true);
        service.check();

        assertThat(service.status().available()).isTrue();
        assertThat(service.status().releaseUrl()).isNull();
        assertThat(service.status().notes()).isNull();
    }

    @Test
    void anAnswerThatIsNotAVersionIsIgnored() {
        body = release("ultima", "https://github.com/rosemgo/WalletTracker/releases/tag/ultima", "");
        UpdateCheckService service = service("1.0.0", true);
        service.check();

        assertThat(service.status().latestVersion()).isNull();
        assertThat(service.status().available()).isFalse();
    }

    @Test
    void whenGitHubFailsThePreviousAnswerStays() {
        UpdateCheckService service = service("1.0.0", true);
        service.check();
        status = 503;
        body = "{}";
        service.check();

        assertThat(service.status().latestVersion()).isEqualTo("1.2.0");
        assertThat(service.status().available()).isTrue();
    }

    @Test
    void withNoInternetNothingBreaks() {
        UpdateCheckService service = service("1.0.0", true);
        server.stop(0);
        service.check();

        assertThat(service.status().checkEnabled()).isTrue();
        assertThat(service.status().available()).isFalse();
        assertThat(service.status().latestVersion()).isNull();
    }
}
