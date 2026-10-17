package it.wallettracker.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.connection.BankConnection;
import it.wallettracker.connection.BankConnectionRepository;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.TransactionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonContent;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Test delle API degli abbonamenti, con i movimenti nel database (le date sono relative a oggi). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class SubscriptionControllerTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ClassificationService classificationService;

    @Autowired
    SubscriptionRepository subscriptionRepository;

    @Autowired
    BankTransactionRepository transactionRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    BankConnectionRepository connectionRepository;

    private Account account;
    private int counter;

    @BeforeEach
    void createAccount() {
        BankConnection connection = connectionRepository.save(new BankConnection("Banca", "IT", "session",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        account = accountRepository.save(new Account(connection, "key", "uid", null, "Conto", "EUR"));
    }

    @AfterEach
    void cleanDatabase() {
        subscriptionRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    @Test
    void aRegularPaymentIsSuggestedAndTheSupermarketIsNot() {
        monthly("NETFLIX.COM", "-12.99", "-12.99", "-12.99", "-12.99");
        monthly("ESSELUNGA", "-85.40", "-120.10", "-42.00", "-96.70");
        classificationService.classifyAll();

        assertThat(mvc.get().uri("/api/subscriptions/suggestions"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.length()").isEqualTo(1);
                    json.assertThat().extractingPath("$[0].merchantKey").isEqualTo("NETFLIX COM");
                    json.assertThat().extractingPath("$[0].name").isEqualTo("NETFLIX.COM");
                    json.assertThat().extractingPath("$[0].frequency").isEqualTo("MONTHLY");
                    json.assertThat().extractingPath("$[0].occurrences").isEqualTo(4);
                    amount(json, "$[0].typicalAmount", "12.99");
                });
    }

    @Test
    void aConfirmedSubscriptionIsListedWithItsCostAndIsNoLongerSuggested() {
        monthly("NETFLIX.COM", "-12.99", "-12.99", "-13.99", "-13.99"); // rincaro
        classificationService.classifyAll();

        assertThat(confirm("NETFLIX COM", "Netflix", "MONTHLY", "12.99")).hasStatus(201);

        assertThat(mvc.get().uri("/api/subscriptions/suggestions")).hasStatusOk()
                .bodyJson().extractingPath("$.length()").isEqualTo(0);
        assertThat(mvc.get().uri("/api/subscriptions"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.subscriptions[0].name").isEqualTo("Netflix");
                    amount(json, "$.subscriptions[0].lastAmount", "13.99");
                    amount(json, "$.subscriptions[0].monthlyCost", "13.99");
                    json.assertThat().extractingPath("$.subscriptions[0].priceChanged").isEqualTo(true);
                    json.assertThat().extractingPath("$.subscriptions[0].late").isEqualTo(false);
                    json.assertThat().extractingPath("$.subscriptions[0].nextDate")
                            .isEqualTo(TODAY.minusDays(2).plusMonths(1).toString());
                    amount(json, "$.totals[0].monthly", "13.99");
                    amount(json, "$.totals[0].yearly", "167.88");
                });
    }

    @Test
    void aSubscriptionWithoutRecentPaymentsIsLateAndDoesNotCount() {
        // L'ultimo pagamento è di 3 mesi fa: forse è stato disdetto.
        payment("PALESTRA FIT", TODAY.minusMonths(3), "-35.00");
        classificationService.classifyAll();
        confirm("PALESTRA FIT", "Palestra", "MONTHLY", "35");

        assertThat(mvc.get().uri("/api/subscriptions"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.subscriptions[0].late").isEqualTo(true);
                    json.assertThat().extractingPath("$.totals.length()").isEqualTo(0);
                });
    }

    @Test
    void aDismissedMerchantIsNotSuggestedUntilTheDecisionIsCancelled() {
        monthly("ASSICURAZIONE AUTO", "-40.00", "-40.00", "-40.00");
        classificationService.classifyAll();

        assertThat(mvc.post().uri("/api/subscriptions/dismissed").contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchantKey\": \"ASSICURAZIONE AUTO\", \"currency\": \"EUR\"}")).hasStatus(201);
        assertThat(mvc.get().uri("/api/subscriptions/suggestions")).bodyJson()
                .extractingPath("$.length()").isEqualTo(0);

        // Decidere due volte per lo stesso esercente non si può.
        assertThat(confirm("ASSICURAZIONE AUTO", "Assicurazione", "MONTHLY", "40")).hasStatus(409);

        Long id = subscriptionRepository.findAll().getFirst().getId();
        assertThat(mvc.get().uri("/api/subscriptions")).bodyJson()
                .extractingPath("$.dismissed[0].merchantKey").isEqualTo("ASSICURAZIONE AUTO");
        assertThat(mvc.delete().uri("/api/subscriptions/{id}", id)).hasStatus(204);
        assertThat(mvc.get().uri("/api/subscriptions/suggestions")).bodyJson()
                .extractingPath("$.length()").isEqualTo(1);
    }

    @Test
    void invalidRequestsAreRejected() {
        assertThat(confirm("NETFLIX COM", " ", "MONTHLY", "12.99")).hasStatus(400);         // nome vuoto
        assertThat(confirm("NETFLIX COM", "Netflix", "WEEKLY", "12.99")).hasStatus(400);    // frequenza sconosciuta
        assertThat(confirm("NETFLIX COM", "Netflix", "MONTHLY", "0")).hasStatus(400);       // importo zero
        assertThat(confirm("<script>", "Netflix", "MONTHLY", "12.99")).hasStatus(400);      // chiave non valida
        assertThat(subscriptionRepository.count()).isZero();
    }

    private MvcTestResult confirm(String merchantKey, String name, String frequency, String amount) {
        return mvc.post().uri("/api/subscriptions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchantKey\": \"" + merchantKey + "\", \"currency\": \"EUR\", \"name\": \"" + name
                        + "\", \"frequency\": \"" + frequency + "\", \"expectedAmount\": " + amount + "}")
                .exchange();
    }

    /** Un pagamento al mese, dal più vecchio; l'ultimo è di due giorni fa. */
    private void monthly(String counterparty, String... amounts) {
        for (int i = 0; i < amounts.length; i++) {
            payment(counterparty, TODAY.minusDays(2).minusMonths(amounts.length - 1 - i), amounts[i]);
        }
    }

    private void payment(String counterparty, LocalDate date, String amount) {
        transactionRepository.save(new BankTransaction(account, "tx-" + (++counter), TransactionStatus.BOOKED, date,
                null, new BigDecimal(amount), "EUR", counterparty, "Pagamento carta", "{}"));
    }

    private static void amount(JsonContent json, String path, String expected) {
        json.assertThat().extractingPath(path).satisfies(value ->
                assertThat(new BigDecimal(value.toString())).isEqualByComparingTo(expected));
    }
}
