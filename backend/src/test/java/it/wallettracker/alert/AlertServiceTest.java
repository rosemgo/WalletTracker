package it.wallettracker.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.alert.AlertDto.AlertKind;
import it.wallettracker.alert.AlertDto.Severity;
import it.wallettracker.budget.Budget;
import it.wallettracker.budget.BudgetRepository;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.connection.BankConnection;
import it.wallettracker.connection.BankConnectionRepository;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.TransactionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Test degli avvisi. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class AlertServiceTest {

    /** "Adesso", fissato: i test non dipendono dal giorno in cui girano. */
    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    @Autowired
    AlertService alertService;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    BankConnectionRepository connectionRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    BankTransactionRepository transactionRepository;

    @Autowired
    BudgetRepository budgetRepository;

    @Autowired
    ClassificationService classificationService;

    private int counter;

    @AfterEach
    void cleanDatabase() {
        budgetRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    @Test
    void consentsAreReportedWhenExpiredOrExpiringAndTheMostUrgentComesFirst() {
        account(connection("ING", NOW.plus(100, ChronoUnit.DAYS)));         // lontano: nessun avviso
        account(connection("Revolut", NOW.plus(5, ChronoUnit.DAYS)));       // tra 5 giorni
        account(connection("Fineco", NOW.plus(13, ChronoUnit.DAYS)));       // tra 13 giorni
        account(connection("BBVA", NOW.minus(3, ChronoUnit.DAYS)));         // scaduto

        List<AlertDto> alerts = alertService.alerts(NOW);

        assertThat(alerts).extracting(AlertDto::bank).containsExactly("BBVA", "Revolut", "Fineco");
        assertThat(alerts.get(0).kind()).isEqualTo(AlertKind.CONSENT_EXPIRED);
        assertThat(alerts.get(0).severity()).isEqualTo(Severity.CRITICAL);
        assertThat(alerts.get(0).daysLeft()).isEqualTo(-3);
        assertThat(alerts.get(1).kind()).isEqualTo(AlertKind.CONSENT_EXPIRING);
        assertThat(alerts.get(1).daysLeft()).isEqualTo(5);
        assertThat(alerts.get(1).country()).isEqualTo("IT"); // serve per ricollegare la banca
    }

    @Test
    void daysAreCountedOnTheCalendarNotInHours() {
        // Scade tra 5 giorni e 23 ore: per una persona sono 6 giorni (dal 17 al 23), non 5.
        account(connection("ING", NOW.plus(6, ChronoUnit.DAYS).minus(1, ChronoUnit.HOURS)));

        assertThat(alertService.alerts(NOW).getFirst().daysLeft()).isEqualTo(6);
    }

    @Test
    void aFailedSyncIsReportedOnlyIfTheConsentIsStillValid() {
        Account valid = account(connection("ING", NOW.plus(100, ChronoUnit.DAYS)));
        Account expired = account(connection("BBVA", NOW.minus(1, ChronoUnit.DAYS)));
        accountRepository.recordSync(valid.getId(), NOW, "HTTP 429 troppe letture");
        accountRepository.recordSync(expired.getId(), NOW, "HTTP 401 sessione scaduta");

        List<AlertDto> alerts = alertService.alerts(NOW);

        assertThat(alerts).extracting(AlertDto::kind)
                .containsExactly(AlertKind.CONSENT_EXPIRED, AlertKind.SYNC_FAILED); // niente doppione per BBVA
        assertThat(alerts.get(1).account()).isEqualTo(valid.getName());
        assertThat(alerts.get(1).message()).isEqualTo("HTTP 429 troppe letture");
    }

    @Test
    void budgetsOverTheLimitThisMonthAreReportedTogether() {
        Account account = account(connection("ING", Instant.now().plus(100, ChronoUnit.DAYS)));
        expense(account, "-120.00", "Ristoranti");
        expense(account, "-60.00", "Viaggi");
        expense(account, "-10.00", "Spesa");
        classificationService.classifyAll();
        budgetRepository.saveAll(List.of(
                new Budget("Ristoranti", "EUR", new BigDecimal("100")),
                new Budget("Viaggi", "EUR", new BigDecimal("50")),
                new Budget("Spesa", "EUR", new BigDecimal("400"))));

        assertThat(mvc.get().uri("/api/alerts"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.length()").isEqualTo(1);
                    json.assertThat().extractingPath("$[0].kind").isEqualTo("BUDGET_OVER");
                    json.assertThat().extractingPath("$[0].items").asArray()
                            .containsExactly("Ristoranti", "Viaggi");
                });
    }

    @Test
    void withNothingToReportThereAreNoAlerts() {
        account(connection("ING", NOW.plus(100, ChronoUnit.DAYS)));

        assertThat(alertService.alerts(NOW)).isEmpty();
    }

    private BankConnection connection(String bank, Instant validUntil) {
        counter++;
        return connectionRepository.save(new BankConnection(bank, "IT", "session-" + counter, validUntil));
    }

    private Account account(BankConnection connection) {
        counter++;
        return accountRepository.save(new Account(connection, "key-" + counter, "uid-" + counter, null,
                "Conto " + counter, "EUR"));
    }

    /** Una spesa di oggi (quindi del mese in corso), con la categoria scelta a mano. */
    private void expense(Account account, String amount, String category) {
        BankTransaction transaction = new BankTransaction(account, "tx-" + (++counter), TransactionStatus.BOOKED,
                LocalDate.now(), null, new BigDecimal(amount), "EUR", null, "NEGOZIO " + counter, "{}");
        transaction.setManualCategory(category);
        transactionRepository.save(transaction);
    }
}
