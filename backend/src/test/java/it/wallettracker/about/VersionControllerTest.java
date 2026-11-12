package it.wallettracker.about;

import static org.assertj.core.api.Assertions.assertThat;

import it.wallettracker.IntegrationTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** La versione dell'app (docs/50, parte B): nei test, come costruendo dal codice, è "sviluppo". */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class VersionControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void withoutAPublishedVersionTheAppIsADevelopmentBuild() {
        assertThat(mvc.get().uri("/api/version")).hasStatusOk()
                .bodyJson().extractingPath("$.version").isEqualTo("sviluppo");
    }

    @Test
    void fromTheSourceCodeThereIsNeverAnUpdateToOffer() {
        assertThat(mvc.get().uri("/api/version/update")).hasStatusOk()
                .bodyJson().satisfies(json -> {
                    assertThat(json).extractingPath("$.currentVersion").isEqualTo("sviluppo");
                    assertThat(json).extractingPath("$.checkEnabled").isEqualTo(false);
                    assertThat(json).extractingPath("$.available").isEqualTo(false);
                });
    }
}
