package it.wallettracker.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import it.wallettracker.IntegrationTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Il PIN attraverso le API (docs/46, parte C): crearlo, entrare, sbagliare, essere bloccati, uscire.
 *
 * <p>Qui le richieste partono <b>senza</b> PIN: il MockMvc è costruito a mano, senza le richieste "già dentro"
 * di {@link IntegrationTestConfiguration}. {@code springSecurity()} fa passare le richieste dai filtri di Spring
 * Security, come quelle vere.
 *
 * <p>L'orologio è finto ({@link TestClock}): per vedere il blocco finire non aspettiamo un minuto, lo spostiamo avanti.
 */
@SpringBootTest
@Import({IntegrationTestConfiguration.class, AuthControllerTest.Clocks.class})
// Un nome come quello di Tailscale (passo 4), per provare che da lì il PIN non si crea.
@TestPropertySource(properties = "wallettracker.allowed-hosts=localhost,127.0.0.1,pc-casa.esempio.ts.net")
class AuthControllerTest {

    private static final String PIN = "246810";

    @Autowired
    WebApplicationContext context;
    @Autowired
    AppPinRepository repository;
    @Autowired
    TestClock clock;

    MockMvcTester mvc;

    /** Un orologio che si sposta solo quando lo dice il test. */
    static class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-27T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Europe/Rome");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Clocks {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    @BeforeEach
    void noPinAndAFreshMockMvc() {
        repository.deleteAll();
        MockMvc plain = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc = MockMvcTester.create(plain);
    }

    @Test
    void withoutAPinTheApiIs401AndThePcIsInvitedToCreateOne() {
        assertThat(mvc.get().uri("/api/accounts")).hasStatus(401)
                .bodyJson().extractingPath("$.detail").isEqualTo("Inserisci il PIN.");

        MvcTestResult status = mvc.get().uri("/api/auth/status").header("Host", "localhost:8000").exchange();
        assertThat(status).hasStatusOk().bodyJson().isLenientlyEqualTo(
                "{\"pinSet\":false,\"authenticated\":false,\"canCreatePin\":true}");
        // Lo stato dà anche il gettone CSRF, nel cookie che Angular legge.
        assertThat(status).cookies().containsCookie("XSRF-TOKEN");
    }

    @Test
    void thePinIsCreatedOnlyFromThePc() {
        assertThat(mvc.get().uri("/api/auth/status").header("Host", "pc-casa.esempio.ts.net"))
                .bodyJson().extractingPath("$.canCreatePin").isEqualTo(false);
        assertThat(mvc.post().uri("/api/auth/pin").header("Host", "pc-casa.esempio.ts.net").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(pinJson(PIN)))
                .hasStatus(403)
                .bodyJson().extractingPath("$.detail").asString().contains("solo dal PC");
        assertThat(repository.count()).isZero();
    }

    @Test
    void creatingThePinOpensTheSessionAndOnlyOnce() {
        MvcTestResult created = createPin(PIN);
        assertThat(created).hasStatus(204);
        MockHttpSession session = (MockHttpSession) created.getRequest().getSession(false);
        assertThat(session).isNotNull();

        assertThat(mvc.get().uri("/api/accounts").session(session)).hasStatusOk();
        assertThat(mvc.get().uri("/api/auth/status").session(session)).bodyJson().isLenientlyEqualTo(
                "{\"pinSet\":true,\"authenticated\":true,\"canCreatePin\":false}");

        // Il PIN si salva come hash bcrypt, mai in chiaro.
        assertThat(repository.findAll().getFirst().pinHash()).startsWith("{bcrypt}$2a$12$").doesNotContain(PIN);

        assertThat(createPin("999999")).hasStatus(409);
    }

    @Test
    void aPinIsMadeOfSixToTenDigits() {
        for (String wrong : new String[] {"12345", "12345678901", "12a456", "", " 123456"}) {
            assertThat(createPin(wrong)).as("'%s'", wrong).hasStatus(400)
                    .bodyJson().extractingPath("$.detail").isEqualTo("Il PIN è fatto di cifre: da 6 a 10.");
        }
        assertThat(createPin("1234567890")).hasStatus(204);
    }

    @Test
    void withoutTheCsrfTokenNothingChanges() {
        assertThat(mvc.post().uri("/api/auth/pin").header("Host", "localhost")
                .contentType(MediaType.APPLICATION_JSON).content(pinJson(PIN)))
                .hasStatus(403)
                .bodyJson().extractingPath("$.detail").asString().contains("ricarica la pagina");
        assertThat(repository.count()).isZero();
    }

    @Test
    void fiveWrongPinsThenAPauseInWhichEvenTheRightOneIsNotChecked() {
        createPin(PIN);

        for (int left = 4; left >= 1; left--) {
            assertThat(login("000000")).hasStatus(401)
                    .bodyJson().extractingPath("$.attemptsLeft").isEqualTo(left);
        }
        MvcTestResult fifth = login("000000");
        assertThat(fifth).hasStatus(429).hasHeader("Retry-After", "60")
                .bodyJson().extractingPath("$.detail").isEqualTo("Troppi PIN sbagliati: riprova tra 1 minuto.");

        // Durante la pausa anche il PIN giusto riceve 429: chi prova i PIN non capisce se ha indovinato.
        assertThat(login(PIN)).hasStatus(429);

        clock.advance(Duration.ofSeconds(61));
        MvcTestResult right = login(PIN);
        assertThat(right).hasStatus(204);
        assertThat(repository.findAll().getFirst().failedAttempts()).isZero();
    }

    @Test
    void afterTheFirstPauseEveryErrorWaitsLonger() {
        createPin(PIN);
        for (int i = 0; i < 5; i++) {
            login("000000");
        }
        clock.advance(Duration.ofSeconds(61));

        assertThat(login("000000")).hasStatus(429).hasHeader("Retry-After", "300")
                .bodyJson().extractingPath("$.detail").isEqualTo("Troppi PIN sbagliati: riprova tra 5 minuti.");
    }

    @Test
    void loggingInGivesANewSessionIdAndLoggingOutClosesIt() {
        createPin(PIN);
        MockHttpSession before = new MockHttpSession();
        String oldId = before.getId();

        MvcTestResult in = mvc.post().uri("/api/auth/login").session(before).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(pinJson(PIN)).exchange();
        assertThat(in).hasStatus(204);
        MockHttpSession session = (MockHttpSession) in.getRequest().getSession(false);
        // Session fixation: la sessione di prima non diventa quella "dentro".
        assertThat(session.getId()).isNotEqualTo(oldId);
        assertThat(mvc.get().uri("/api/accounts").session(session)).hasStatusOk();

        assertThat(mvc.post().uri("/api/auth/logout").session(session).with(csrf())).hasStatus(204);
        assertThat(session.isInvalid()).isTrue();
        assertThat(mvc.get().uri("/api/accounts")).hasStatus(401);
    }

    @Test
    void theHealthCheckStaysOpenForDocker() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
    }

    private MvcTestResult createPin(String pin) {
        return mvc.post().uri("/api/auth/pin").header("Host", "localhost").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(pinJson(pin)).exchange();
    }

    private MvcTestResult login(String pin) {
        return mvc.post().uri("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(pinJson(pin)).exchange();
    }

    private static String pinJson(String pin) {
        return "{\"pin\":\"" + pin + "\"}";
    }
}
