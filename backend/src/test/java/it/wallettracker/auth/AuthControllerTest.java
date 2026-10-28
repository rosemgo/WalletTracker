package it.wallettracker.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Come si entra, attraverso le API (docs/46, parte D): la scelta senza PIN, il PIN, gli errori e le pause, il codice
 * di recupero, il PIN tolto, le sessioni che si chiudono.
 *
 * <p>Qui le richieste partono <b>senza</b> PIN: il MockMvc è costruito a mano, senza le richieste "già dentro"
 * di {@link IntegrationTestConfiguration}. {@code springSecurity()} fa passare le richieste dai filtri di Spring
 * Security, come quelle vere.
 *
 * <p>"Dal PC" e "da un altro dispositivo": in produzione lo dice nginx, con l'header {@link RequestOrigin#HEADER}
 * (docs/46, parte C). Qui lo mettiamo noi ({@link #fromThePc}), o non lo mettiamo.
 *
 * <p>L'orologio è finto ({@link TestClock}): per vedere il blocco finire non aspettiamo un minuto, lo spostiamo avanti.
 */
@SpringBootTest
@Import({IntegrationTestConfiguration.class, AuthControllerTest.Clocks.class})
class AuthControllerTest {

    private static final String PIN = "246810";
    private static final String CODE_PATTERN = "[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}";

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
    void nothingChosenAndAFreshMockMvc() {
        repository.deleteAll();
        MockMvc plain = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc = MockMvcTester.create(plain);
    }

    // --- La prima apertura ---------------------------------------------------------------------------------------

    @Test
    void atTheFirstOpeningTheApiIs401AndThePcIsAskedToChoose() {
        assertThat(mvc.get().uri("/api/accounts").header(RequestOrigin.HEADER, "local")).hasStatus(401)
                .bodyJson().extractingPath("$.detail").isEqualTo("Inserisci il PIN.");

        MvcTestResult status = mvc.get().uri("/api/auth/status").header(RequestOrigin.HEADER, "local").exchange();
        assertThat(status).hasStatusOk().bodyJson().isLenientlyEqualTo(
                "{\"pinSet\":false,\"withoutPin\":false,\"authenticated\":false,\"local\":true}");
        // Lo stato dà anche il gettone CSRF, nel cookie che Angular legge.
        assertThat(status).cookies().containsCookie("XSRF-TOKEN");
    }

    @Test
    void fromAnotherDeviceNothingCanBeChosenEvenWritingLocalhost() {
        // Il nome "localhost" lo scrive chi manda la richiesta: non conta. Conta la porta di nginx (l'header).
        assertThat(mvc.get().uri("/api/auth/status").header("Host", "localhost"))
                .bodyJson().extractingPath("$.local").isEqualTo(false);
        assertThat(mvc.post().uri("/api/auth/no-pin").header("Host", "localhost").with(csrf()))
                .hasStatus(403).bodyJson().extractingPath("$.detail").asString().contains("solo dal PC");
        assertThat(createPin(PIN, false)).hasStatus(403)
                .bodyJson().extractingPath("$.detail").asString().contains("solo dal PC");
        assertThat(repository.count()).isZero();
    }

    // --- Senza PIN -------------------------------------------------------------------------------------------------

    @Test
    void withoutPinThePcEntersAtOnceAndOtherDevicesDoNot() {
        assertThat(fromThePc(mvc.post().uri("/api/auth/no-pin").with(csrf()))).hasStatus(204);

        MvcTestResult fromPc = fromThePc(mvc.get().uri("/api/accounts")).exchange();
        assertThat(fromPc).hasStatusOk();
        // Nessuna sessione "dentro": ogni richiesta dal PC entra da sola. (Una sessione qui c'è, ma solo perché il
        // supporto ai test di Spring Security ci tiene il gettone CSRF; che il server vero non dia nessun cookie di
        // sessione lo prova SessionCookieTest.)
        var session = fromPc.getRequest().getSession(false);
        assertThat(session == null ? null : session.getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
        assertThat(fromThePc(mvc.get().uri("/api/auth/status"))).bodyJson().isLenientlyEqualTo(
                "{\"pinSet\":false,\"withoutPin\":true,\"authenticated\":true,\"local\":true}");

        assertThat(mvc.get().uri("/api/accounts")).hasStatus(401);
        assertThat(mvc.get().uri("/api/auth/status")).bodyJson().isLenientlyEqualTo(
                "{\"pinSet\":false,\"withoutPin\":true,\"authenticated\":false,\"local\":false}");

        assertThat(fromThePc(mvc.post().uri("/api/auth/no-pin").with(csrf()))).hasStatus(409);
    }

    @Test
    void whoWasWithoutPinCanAddOneFromThePcAndThenThePcTooNeedsIt() {
        fromThePc(mvc.post().uri("/api/auth/no-pin").with(csrf())).exchange();

        MvcTestResult created = createPin(PIN, true);
        assertThat(created).hasStatusOk().bodyJson().extractingPath("$.recoveryCode").asString().matches(CODE_PATTERN);

        assertThat(fromThePc(mvc.get().uri("/api/accounts"))).hasStatus(401);
        assertThat(fromThePc(mvc.get().uri("/api/accounts")).session(session(created))).hasStatusOk();
    }

    // --- Il PIN ----------------------------------------------------------------------------------------------------

    @Test
    void creatingThePinOpensTheSessionAndGivesTheRecoveryCodeOnce() {
        MvcTestResult created = createPin(PIN, true);
        assertThat(created).hasStatusOk().bodyJson().extractingPath("$.recoveryCode").asString().matches(CODE_PATTERN);
        MockHttpSession session = session(created);

        assertThat(mvc.get().uri("/api/accounts").session(session)).hasStatusOk();
        assertThat(mvc.get().uri("/api/auth/status").session(session)).bodyJson().isLenientlyEqualTo(
                "{\"pinSet\":true,\"withoutPin\":false,\"authenticated\":true,\"local\":false}");
        // Con il PIN, anche il PC deve inserirlo.
        assertThat(fromThePc(mvc.get().uri("/api/accounts"))).hasStatus(401);

        // PIN e codice si salvano come hash bcrypt, mai in chiaro.
        AppPin saved = repository.findAll().getFirst();
        String code = recoveryCode(created);
        assertThat(saved.pinHash()).startsWith("{bcrypt}$2a$12$").doesNotContain(PIN);
        assertThat(saved.recoveryHash()).startsWith("{bcrypt}$2a$12$").doesNotContain(code.replace("-", ""));

        assertThat(createPin("999999", true)).hasStatus(409);
    }

    @Test
    void aPinIsMadeOfSixToTenDigits() {
        for (String wrong : new String[] {"12345", "12345678901", "12a456", "", " 123456"}) {
            assertThat(createPin(wrong, true)).as("'%s'", wrong).hasStatus(400)
                    .bodyJson().extractingPath("$.detail").isEqualTo("Il PIN è fatto di cifre: da 6 a 10.");
        }
        assertThat(createPin("1234567890", true)).hasStatusOk();
    }

    @Test
    void withoutTheCsrfTokenNothingChanges() {
        assertThat(fromThePc(mvc.post().uri("/api/auth/pin")
                .contentType(MediaType.APPLICATION_JSON).content(pinJson(PIN))))
                .hasStatus(403)
                .bodyJson().extractingPath("$.detail").asString().contains("ricarica la pagina");
        assertThat(repository.count()).isZero();
    }

    @Test
    void fiveWrongPinsThenAPauseInWhichEvenTheRightOneIsNotChecked() {
        createPin(PIN, true);

        for (int left = 4; left >= 1; left--) {
            assertThat(login("000000")).hasStatus(401)
                    .bodyJson().extractingPath("$.attemptsLeft").isEqualTo(left);
        }
        MvcTestResult fifth = login("000000");
        assertThat(fifth).hasStatus(429).hasHeader("Retry-After", "60")
                .bodyJson().extractingPath("$.detail").isEqualTo("Troppi tentativi sbagliati: riprova tra 1 minuto.");

        // Durante la pausa anche il PIN giusto riceve 429: chi prova i PIN non capisce se ha indovinato.
        assertThat(login(PIN)).hasStatus(429);

        clock.advance(Duration.ofSeconds(61));
        assertThat(login(PIN)).hasStatus(204);
        assertThat(repository.findAll().getFirst().failedAttempts()).isZero();
    }

    @Test
    void afterTheFirstPauseEveryErrorWaitsLonger() {
        createPin(PIN, true);
        for (int i = 0; i < 5; i++) {
            login("000000");
        }
        clock.advance(Duration.ofSeconds(61));

        assertThat(login("000000")).hasStatus(429).hasHeader("Retry-After", "300")
                .bodyJson().extractingPath("$.detail").isEqualTo("Troppi tentativi sbagliati: riprova tra 5 minuti.");
    }

    @Test
    void loggingInGivesANewSessionIdAndLoggingOutClosesIt() {
        createPin(PIN, true);
        MockHttpSession before = new MockHttpSession();
        String oldId = before.getId();

        MvcTestResult in = mvc.post().uri("/api/auth/login").session(before).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(pinJson(PIN)).exchange();
        assertThat(in).hasStatus(204);
        MockHttpSession session = session(in);
        // Session fixation: la sessione di prima non diventa quella "dentro".
        assertThat(session.getId()).isNotEqualTo(oldId);
        assertThat(mvc.get().uri("/api/accounts").session(session)).hasStatusOk();

        assertThat(mvc.post().uri("/api/auth/logout").session(session).with(csrf())).hasStatus(204);
        assertThat(session.isInvalid()).isTrue();
        assertThat(mvc.get().uri("/api/accounts")).hasStatus(401);
    }

    // --- Il codice di recupero -------------------------------------------------------------------------------------

    @Test
    void theRecoveryCodeSetsANewPinOnceAndClosesTheOtherSessions() {
        String firstCode = recoveryCode(createPin(PIN, true));
        MockHttpSession phone = session(login(PIN)); // una sessione su un altro dispositivo (un telefono perso)
        assertThat(mvc.get().uri("/api/accounts").session(phone)).hasStatusOk();

        assertThat(recover("AAAA-BBBB-CCCC-DDDD", "112233")).hasStatus(401)
                .bodyJson().extractingPath("$.detail")
                .isEqualTo("Codice di recupero sbagliato. Ancora 4 tentativi prima di una pausa.");

        // Il codice si scrive come si vuole: minuscole, spazi al posto dei trattini.
        String written = firstCode.toLowerCase().replace("-", " ");
        MvcTestResult recovered = recover(written, "112233");
        assertThat(recovered).hasStatusOk();
        String secondCode = recoveryCode(recovered);
        assertThat(secondCode).matches(CODE_PATTERN).isNotEqualTo(firstCode);

        // Il telefono perso è fuori: la sua sessione era di un PIN che non c'è più.
        assertThat(mvc.get().uri("/api/accounts").session(phone)).hasStatus(401);
        assertThat(phone.isInvalid()).isTrue();
        // Chi ha recuperato è dentro, con il PIN nuovo.
        assertThat(mvc.get().uri("/api/accounts").session(session(recovered))).hasStatusOk();
        assertThat(login(PIN)).hasStatus(401);
        assertThat(login("112233")).hasStatus(204);
        // Il primo codice è consumato.
        assertThat(recover(firstCode, "445566")).hasStatus(401);
    }

    @Test
    void aRecoveryCodeHasSixteenSymbolsAndOnlyAPinCanBeRecovered() {
        assertThat(recover("AAAA-BBBB-CCCC-DDDD", "112233")).hasStatus(409); // non c'è nessun PIN da recuperare
        createPin(PIN, true);
        assertThat(recover("ABC", "112233")).hasStatus(400)
                .bodyJson().extractingPath("$.detail").asString().contains("16 lettere e cifre");
        assertThat(repository.findAll().getFirst().failedAttempts()).isZero(); // un codice malformato non conta
    }

    // --- Togliere il PIN -------------------------------------------------------------------------------------------

    @Test
    void thePinIsRemovedOnlyFromThePcWithThePinAndOtherSessionsClose() {
        createPin(PIN, true);
        MockHttpSession phone = session(login(PIN));

        assertThat(removePin(PIN, false)).hasStatus(403);
        assertThat(removePin("000000", true)).hasStatus(401);
        assertThat(removePin(PIN, true)).hasStatus(204);

        assertThat(fromThePc(mvc.get().uri("/api/accounts"))).hasStatusOk();
        assertThat(mvc.get().uri("/api/accounts").session(phone)).hasStatus(401);
        assertThat(mvc.get().uri("/api/auth/status")).bodyJson().isLenientlyEqualTo(
                "{\"pinSet\":false,\"withoutPin\":true,\"authenticated\":false,\"local\":false}");
    }

    @Test
    void theHealthCheckStaysOpenForDocker() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
    }

    // --- Aiuti -----------------------------------------------------------------------------------------------------

    /** Una richiesta "dal PC": l'header che in produzione scrive nginx, sulla porta 8000. */
    private static MockMvcTester.MockMvcRequestBuilder fromThePc(MockMvcTester.MockMvcRequestBuilder request) {
        return request.header(RequestOrigin.HEADER, "local");
    }

    private MvcTestResult createPin(String pin, boolean fromThePc) {
        MockMvcTester.MockMvcRequestBuilder request = mvc.post().uri("/api/auth/pin").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(pinJson(pin));
        return (fromThePc ? fromThePc(request) : request).exchange();
    }

    private MvcTestResult login(String pin) {
        return mvc.post().uri("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(pinJson(pin)).exchange();
    }

    private MvcTestResult recover(String code, String newPin) {
        return mvc.post().uri("/api/auth/recover").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"pin\":\"" + newPin + "\"}").exchange();
    }

    private MvcTestResult removePin(String pin, boolean fromThePc) {
        MockMvcTester.MockMvcRequestBuilder request = mvc.post().uri("/api/auth/pin/remove").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(pinJson(pin));
        return (fromThePc ? fromThePc(request) : request).exchange();
    }

    private static MockHttpSession session(MvcTestResult result) {
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).as("la sessione aperta").isNotNull();
        return session;
    }

    private static String recoveryCode(MvcTestResult result) {
        return JsonPath.read(new String(result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8), "$.recoveryCode");
    }

    private static String pinJson(String pin) {
        return "{\"pin\":\"" + pin + "\"}";
    }
}
