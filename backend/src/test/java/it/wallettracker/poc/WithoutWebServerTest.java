package it.wallettracker.poc;

import static org.assertj.core.api.Assertions.assertThat;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.connection.ConnectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Il programma della Fase 0 (profilo "poc", docs/02) avvia l'app senza server web. Con la Fase 4 (Spring Security,
 * docs/46) l'app non partiva più così: SecurityConfig chiedeva HttpSecurity, che senza server web non c'è.
 *
 * <p>Qui l'app parte come la fa partire il POC, ma senza PocRunner (che leggerebbe dalla tastiera): basta che il
 * contesto si avvii, e che i servizi usati dal POC ci siano.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.main.web-application-type=none", "wallettracker.sync.enabled=false"})
@Import(IntegrationTestConfiguration.class)
class WithoutWebServerTest {

    @Autowired
    ApplicationContext context;

    @Test
    void theAppStartsWithoutAWebServerAsThePocDoes() {
        assertThat(context.getBeansOfType(SecurityFilterChain.class)).isEmpty();
        assertThat(context.getBean(ConnectionService.class)).isNotNull();
    }
}
