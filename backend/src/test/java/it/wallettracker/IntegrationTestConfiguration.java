package it.wallettracker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import it.wallettracker.bank.enablebanking.TestKeys;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
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
                // Una chiave di cifratura usa e getta, creata al primo avvio nella cartella temporanea.
                Path keyFile = privateKey.getParent().resolve("encryption.key");
                registry.add("wallettracker.encryption.key-file", keyFile::toString);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    /**
     * Dal passo 3 (docs/46) le API vogliono il PIN. I test delle API non parlano del PIN: per loro ogni richiesta di
     * MockMvc parte "come chi ha già inserito il PIN" ({@code user}) e con il gettone CSRF ({@code csrf}).
     * {@code defaultRequest} aggiunge queste due cose a ogni richiesta. I test del PIN e della sicurezza, che vogliono
     * proprio le richieste SENZA, si costruiscono un MockMvc loro (AuthControllerTest, SecurityTest).
     */
    @Bean
    MockMvcBuilderCustomizer authenticatedRequests() {
        return builder -> builder.defaultRequest(MockMvcRequestBuilders.get("/").with(user("proprietario")).with(csrf()));
    }
}
