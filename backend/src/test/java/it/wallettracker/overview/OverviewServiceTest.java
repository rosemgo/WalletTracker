package it.wallettracker.overview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.category.CategoryRepository;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.connection.BankConnection;
import it.wallettracker.connection.BankConnectionRepository;
import it.wallettracker.overview.OverviewDto.CategoryRow;
import it.wallettracker.subscription.SubscriptionDtos.ConfirmRequest;
import it.wallettracker.subscription.SubscriptionDtos.Suggestion;
import it.wallettracker.subscription.SubscriptionRepository;
import it.wallettracker.subscription.SubscriptionService;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.TransactionStatus;
import it.wallettracker.transaction.TransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.server.ResponseStatusException;

/**
 * Il Riepilogo con un database vero. "Oggi" è il 15 ottobre 2026: il servizio lo riceve come parametro, così il test
 * non dipende dal giorno in cui gira.
 *
 * <p>La storia, inventata: da aprile a settembre, ogni mese, Spesa alimentare 100 € il giorno 1 e 100 € il 20, Bar e
 * ristoranti 30 € il 10, un abbonamento da 12,99 € il 18, lo stipendio il 27. A ottobre, fino al 15: 120 € di spesa il
 * 5 e 50 € al bar il 10, più un giroconto, un movimento da verificare e una spesa in dollari.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class OverviewServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    @Autowired
    OverviewService overviewService;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ClassificationService classificationService;

    @Autowired
    SubscriptionService subscriptionService;

    @Autowired
    SubscriptionRepository subscriptionRepository;

    @Autowired
    BankTransactionRepository transactionRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    BankConnectionRepository connectionRepository;

    @Autowired
    CategoryRepository categoryRepository;

    private Account account;
    private int counter;

    @BeforeEach
    void history() {
        BankConnection connection = connectionRepository.save(new BankConnection("Banca Alfa", "IT", "session-1",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        account = accountRepository.save(new Account(connection, "key-1", "uid-1", null, "Conto corrente", "EUR"));
        for (int month = 4; month <= 9; month++) {
            expense(LocalDate.of(2026, month, 1), "-100.00", "SUPERMERCATO ALFA", "Spesa alimentare");
            expense(LocalDate.of(2026, month, 10), "-30.00", "BAR CENTRALE", "Bar e ristoranti");
            expense(LocalDate.of(2026, month, 18), "-12.99", "NETFLIX.COM", "Intrattenimento e tempo libero");
            expense(LocalDate.of(2026, month, 20), "-100.00", "SUPERMERCATO ALFA", "Spesa alimentare");
            save(LocalDate.of(2026, month, 27), "2000.00", "EUR", "STIPENDIO ACME SRL", TransactionType.INCOME, null);
        }
        expense(LocalDate.of(2026, 10, 5), "-120.00", "SUPERMERCATO ALFA", "Spesa alimentare");
        expense(LocalDate.of(2026, 10, 10), "-50.00", "BAR CENTRALE", "Bar e ristoranti");
        save(LocalDate.of(2026, 10, 3), "-500.00", "EUR", "GIROCONTO", TransactionType.INTERNAL_TRANSFER, null);
        save(LocalDate.of(2026, 10, 12), "-7.00", "EUR", null, null, null); // nessun testo: da verificare
        save(LocalDate.of(2026, 10, 8), "-20.00", "USD", "SHOP NEW YORK", TransactionType.EXPENSE, "Shopping");
        classificationService.classifyAll();
    }

    @AfterEach
    void cleanDatabase() {
        subscriptionRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    @Test
    void theMonthSoFarIsComparedWithTheUsualAtTheSameDay() {
        OverviewDto overview = overviewService.overview(OCTOBER, null, TODAY);

        assertThat(overview.day()).isEqualTo(TODAY);
        assertThat(overview.daysInMonth()).isEqualTo(31);
        assertThat(overview.currency()).isEqualTo("EUR"); // più spese in euro che in dollari
        assertThat(overview.currencies()).containsExactly("EUR", "USD");
        // Solo le spese vere: il giroconto e il movimento da verificare non contano.
        assertThat(overview.spent()).isEqualByComparingTo("170.00");
        assertThat(overview.income()).isEqualByComparingTo("0");
        assertThat(overview.spentByDay()).hasSize(15);
        assertThat(overview.spentByDay().get(3)).isEqualByComparingTo("0"); // il 4
        assertThat(overview.spentByDay().get(4)).isEqualByComparingTo("120.00"); // il 5
        assertThat(overview.spentByDay().get(14)).isEqualByComparingTo("170.00"); // oggi

        // Il solito: sei mesi completi (aprile-settembre). Il 15, di solito, 100 + 30 = 130 €; in un mese intero 242,99 €.
        assertThat(overview.usualMonths()).isEqualTo(6);
        assertThat(overview.usualSoFar()).isEqualByComparingTo("130.00");
        assertThat(overview.usualMonth()).isEqualByComparingTo("242.99");
        assertThat(overview.usualByDay()).hasSize(31);
        assertThat(overview.usualByDay().get(30)).isEqualByComparingTo("242.99"); // il 31: settembre è già intero

        // Le categorie del mese, dalla più alta, ognuna con il suo solito allo stesso giorno.
        assertThat(overview.categories()).extracting(CategoryRow::name)
                .containsExactly("Spesa alimentare", "Bar e ristoranti");
        CategoryRow bar = overview.categories().get(1);
        assertThat(bar.amount()).isEqualByComparingTo("50.00");
        assertThat(bar.movements()).isEqualTo(1);
        assertThat(bar.usualSoFar()).isEqualByComparingTo("30.00");
        assertThat(overview.categories().getFirst().usualSoFar()).isEqualByComparingTo("100.00");
        assertThat(overview.categories().getFirst().usualMonth()).isEqualByComparingTo("200.00");

        assertThat(overview.toReview()).isEqualTo(1);
        assertThat(overview.suggestions()).isZero();
        assertThat(overview.upcoming()).isEmpty(); // nessun abbonamento confermato
    }

    @Test
    void aConfirmedSubscriptionExpectedWithinAWeekIsUpcoming() {
        Suggestion netflix = subscriptionService.suggestions(TODAY).stream()
                .filter(s -> s.name().contains("NETFLIX"))
                .findFirst()
                .orElseThrow();
        subscriptionService.confirm(new ConfirmRequest(netflix.merchantKey(), "EUR", "Netflix", netflix.frequency(),
                netflix.typicalAmount()));

        OverviewDto overview = overviewService.overview(OCTOBER, null, TODAY);
        assertThat(overview.upcoming()).singleElement().satisfies(upcoming -> {
            assertThat(upcoming.name()).isEqualTo("Netflix");
            assertThat(upcoming.date()).isEqualTo(LocalDate.of(2026, 10, 18)); // un mese dopo l'ultimo pagamento
            assertThat(upcoming.amount()).isEqualByComparingTo("12.99");
        });
        // Una settimana prima non è ancora "in arrivo"; in un mese passato non c'è niente in arrivo.
        assertThat(overviewService.overview(OCTOBER, null, LocalDate.of(2026, 10, 10)).upcoming()).isEmpty();
        assertThat(overviewService.overview(YearMonth.of(2026, 9), null, TODAY).upcoming()).isEmpty();
    }

    @Test
    void aPastMonthCountsWholeAndItsUsualComesFromTheMonthsBefore() {
        OverviewDto september = overviewService.overview(YearMonth.of(2026, 9), null, TODAY);

        assertThat(september.day()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(september.spent()).isEqualByComparingTo("242.99");
        assertThat(september.income()).isEqualByComparingTo("2000.00");
        assertThat(september.spentByDay()).hasSize(30);
        assertThat(september.usualMonths()).isEqualTo(5); // aprile-agosto: la storia comincia il primo aprile
        assertThat(september.usualSoFar()).isEqualByComparingTo("242.99");
    }

    @Test
    void anotherCurrencyHasItsOwnNumbersAndNoUsualWithoutHistory() {
        OverviewDto dollars = overviewService.overview(OCTOBER, "usd", TODAY);

        assertThat(dollars.currency()).isEqualTo("USD");
        assertThat(dollars.spent()).isEqualByComparingTo("20.00");
        assertThat(dollars.usualMonths()).isZero(); // i dollari cominciano l'8 ottobre
        assertThat(dollars.usualSoFar()).isNull();
        assertThat(dollars.usualByDay()).isEmpty();
        assertThat(dollars.categories()).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo("Shopping");
            assertThat(row.usualSoFar()).isNull();
        });
    }

    @Test
    void theFutureAndWrongRequestsAreRejected() {
        assertThatThrownBy(() -> overviewService.overview(YearMonth.of(2026, 11), null, TODAY))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("non è ancora cominciato");
        assertThatThrownBy(() -> overviewService.overview(YearMonth.of(2016, 9), null, TODAY))
                .hasMessageContaining("al massimo di 120 mesi");

        assertThat(mvc.get().uri("/api/overview")).hasStatusOk().bodyJson()
                .extractingPath("$.currency").isEqualTo("EUR");
        assertThat(mvc.get().uri("/api/overview?month=2099-01")).hasStatus(400);
        assertThat(mvc.get().uri("/api/overview?month=2026-13")).hasStatus(400);
        assertThat(mvc.get().uri("/api/overview?currency=EURO")).hasStatus(400)
                .bodyJson().extractingPath("$.detail").isEqualTo("La valuta è un codice di tre lettere, come EUR.");
    }

    private void expense(LocalDate date, String amount, String description, String category) {
        save(date, amount, "EUR", description, TransactionType.EXPENSE, category);
    }

    /** Un movimento con il tipo e la categoria scelti a mano (così il test non dipende dalle regole di partenza). */
    private void save(LocalDate date, String amount, String currency, String description, TransactionType type,
            String category) {
        BankTransaction transaction = new BankTransaction(account, "tx-" + (++counter), TransactionStatus.BOOKED, date,
                null, new BigDecimal(amount), currency, null, description, "{}");
        transaction.setManualType(type);
        if (category != null) {
            transaction.setManualCategory(categoryRepository.findByNameIgnoreCase(category).orElseThrow());
        }
        transactionRepository.save(transaction);
    }
}
