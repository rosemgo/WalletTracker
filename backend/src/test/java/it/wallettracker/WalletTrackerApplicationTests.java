package it.wallettracker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Verifica che l'applicazione parta: tutti i componenti vengono creati e collegati, Flyway crea
 * le tabelle e Hibernate controlla che corrispondano alle entità (ddl-auto: validate).
 */
@SpringBootTest
@Import(IntegrationTestConfiguration.class)
class WalletTrackerApplicationTests {

    @Test
    void contextLoads() {
    }
}
