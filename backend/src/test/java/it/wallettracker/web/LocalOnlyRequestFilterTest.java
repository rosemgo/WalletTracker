package it.wallettracker.web;

import static org.assertj.core.api.Assertions.assertThat;

import it.wallettracker.IntegrationTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Test del filtro contro DNS rebinding e richieste da altri siti. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class LocalOnlyRequestFilterTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void requestsToLocalhostPass() {
        assertThat(mvc.get().uri("/api/setup").header("Host", "localhost:8080")).hasStatusOk();
        assertThat(mvc.get().uri("/api/setup").header("Host", "127.0.0.1:8080")).hasStatusOk();
    }

    @Test
    void aForeignHostNameIsRejected() {
        // È quello che succede con il DNS rebinding: il browser manda il nome del sito malevolo.
        assertThat(mvc.get().uri("/api/transactions").header("Host", "evil.example:8080"))
                .hasStatus(403)
                .bodyJson().extractingPath("$.detail").asString().contains("evil.example");
    }

    @Test
    void aChangeRequestedByAnotherSiteIsRejected() {
        assertThat(mvc.post().uri("/api/sync").header("Origin", "https://evil.example"))
                .hasStatus(403)
                .bodyJson().extractingPath("$.detail").asString().contains("altro sito");
    }

    @Test
    void aChangeRequestedByTheDashboardPasses() {
        // La dashboard (https://localhost:8443) ha l'origine consentita: niente 403 (la risposta dipende dall'API).
        assertThat(mvc.post().uri("/api/sync").header("Origin", "https://localhost:8443"))
                .hasStatusOk();
    }
}
