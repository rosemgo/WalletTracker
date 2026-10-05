package it.wallettracker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import it.wallettracker.bank.enablebanking.TestKeys;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Configurazione comune ai test che avviano l'intera applicazione ({@code @SpringBootTest}).
 *
 * <p>Aggiungila a un test con {@code @Import(IntegrationTestConfiguration.class)}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class IntegrationTestConfiguration {

    /**
     * Un PostgreSQL vero, avviato in un container Docker usa e getta (Testcontainers).
     * Grazie a {@code @ServiceConnection} Spring Boot ci si collega da solo: URL, utente e password
     * vengono presi dal container, non da application.yml. Per questo i test richiedono Docker avviato.
     */
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:18-alpine");
    }

    /** Per i test usiamo un application id finto e una chiave RSA generata al volo. */
    @Bean
    DynamicPropertyRegistrar enableBankingProperties() {
        return registry -> {
            try {
                Path privateKey = TestKeys.writePrivateKey(TestKeys.generateKeyPair(),
                        Files.createTempDirectory("wallettracker-test"));
                registry.add("enable-banking.application-id", () -> TestKeys.APPLICATION_ID);
                registry.add("enable-banking.private-key-path", privateKey::toString);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }
}
