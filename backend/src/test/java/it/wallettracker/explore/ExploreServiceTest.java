package it.wallettracker.explore;

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
import it.wallettracker.budget.BudgetRepository;
import it.wallettracker.budget.BudgetRequest;
import it.wallettracker.budget.BudgetService;
import it.wallettracker.category.Category;
import it.wallettracker.category.CategoryRepository;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.connection.BankConnection;
import it.wallettracker.connection.BankConnectionRepository;
import it.wallettracker.explore.ExploreDtos.Bar;
import it.wallettracker.explore.ExploreDtos.By;
import it.wallettracker.explore.ExploreDtos.CategoryOverview;
import it.wallettracker.explore.ExploreDtos.Group;
import it.wallettracker.explore.ExploreDtos.Groups;
import it.wallettracker.explore.ExploreDtos.Metric;
import it.wallettracker.explore.ExploreDtos.MonthValue;
import it.wallettracker.explore.ExploreDtos.Period;
import it.wallettracker.explore.ExploreDtos.Series;
import it.wallettracker.explore.ExploreDtos.Unit;
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
 * Analisi con un database vero; "oggi" è il 15 ottobre 2026. La storia, inventata: da aprile a settembre, ogni mese,
 * Spesa alimentare 100 € il 1° e 100 € il 20 da ESSELUNGA, Bar e ristoranti 30 € il 10 da PIZZERIA, lo stipendio
 * (2.000 €) il 27. A ottobre, fino al 15: 120 € di spesa il 5 e 50 € al bar il 10.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class ExploreServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    @Autowired
    ExploreService exploreService;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ClassificationService classificationService;

    @Autowired
    BankTransactionRepository transactionRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    BankConnectionRepository connectionRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    BudgetService budgetService;

    @Autowired
    BudgetRepository budgetRepository;

    private Account account;
    private int counter;

    @BeforeEach
    void history() {
        BankConnection connection = connectionRepository.save(new BankConnection("Banca Alfa", "IT", "session-1",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        account = accountRepository.save(new Account(connection, "key-1", "uid-1", null, "Conto corrente", "EUR"));
        for (int month = 4; month <= 9; month++) {
            save(LocalDate.of(2026, month, 1), "-100.00", "ESSELUNGA MILANO", TransactionType.EXPENSE, "Spesa alimentare");
            save(LocalDate.of(2026, month, 10), "-30.00", "PIZZERIA DA GINO", TransactionType.EXPENSE, "Bar e ristoranti");
            save(LocalDate.of(2026, month, 20), "-100.00", "ESSELUNGA MILANO", TransactionType.EXPENSE, "Spesa alimentare");
            save(LocalDate.of(2026, month, 27), "2000.00", "STIPENDIO ACME SRL", TransactionType.INCOME,
                    "Stipendio e pensione");
        }
        save(LocalDate.of(2026, 10, 5), "-120.00", "ESSELUNGA MILANO", TransactionType.EXPENSE, "Spesa alimentare");
        save(LocalDate.of(2026, 10, 10), "-50.00", "PIZZERIA DA GINO", TransactionType.EXPENSE, "Bar e ristoranti");
        classificationService.classifyAll();
    }

    @AfterEach
    void cleanDatabase() {
        budgetRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    @Test
    void theYearHasOneBarPerMonthAndTheUsualMonthlyAverage() {
        Series series = exploreService.series(Metric.EXPENSES, Period.YEAR, TODAY, null, null, TODAY);

        assertThat(series.unit()).isEqualTo(Unit.MONTH);
        assertThat(series.bars()).hasSize(12);
        assertThat(series.bars()).extracting(Bar::value).extracting(BigDecimal::intValue)
                .containsExactly(0, 0, 0, 0, 0, 230, 230, 230, 230, 230, 230, 170);
        assertThat(series.averagePerUnit()).isEqualByComparingTo("230.00");
        assertThat(series.usualMonths()).isEqualTo(6);
        assertThat(series.currency()).isEqualTo("EUR");

        // Il netto: 2.000 − 230 nei mesi pieni; una categoria sola.
        assertThat(exploreService.series(Metric.NET, Period.YEAR, TODAY, null, null, TODAY).bars().get(10).value())
                .isEqualByComparingTo("1770.00");
        Long bar = category("Bar e ristoranti").getId();
        Series bars = exploreService.series(Metric.EXPENSES, Period.YEAR, TODAY, bar, null, TODAY);
        assertThat(bars.bars().getLast().value()).isEqualByComparingTo("50.00");
        assertThat(bars.averagePerUnit()).isEqualByComparingTo("30.00");
    }

    @Test
    void theMonthHasOneBarPerWeekAndTheFutureOnesAreMarked() {
        Series series = exploreService.series(Metric.EXPENSES, Period.MONTH, TODAY, null, null, TODAY);

        assertThat(series.unit()).isEqualTo(Unit.WEEK);
        assertThat(series.bars()).extracting(Bar::value).extracting(BigDecimal::intValue)
                .containsExactly(120, 50, 0, 0, 0);
        assertThat(series.bars()).extracting(Bar::future).containsExactly(false, false, false, true, true);
    }

    @Test
    void theGroupsOfAMonthHaveTheirUsualAtTheSameDay() {
        Groups groups = exploreService.groups(Metric.EXPENSES, LocalDate.of(2026, 10, 1), TODAY, By.CATEGORY, null,
                null, TODAY);

        assertThat(groups.total()).isEqualByComparingTo("170.00");
        assertThat(groups.groups()).extracting(Group::name).containsExactly("Spesa alimentare", "Bar e ristoranti");
        // Il 15 del mese, di solito: 100 € di spesa (il 1°) e 30 € al bar (il 10).
        assertThat(groups.groups().getFirst().usual()).isEqualByComparingTo("100.00");
        assertThat(groups.groups().get(1).usual()).isEqualByComparingTo("30.00");

        // La barra del mese in corso arriva al 31: il periodo si accorcia a oggi, e il solito resta allo stesso giorno.
        Groups wholeMonth = exploreService.groups(Metric.EXPENSES, LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31), By.CATEGORY, null, null, TODAY);
        assertThat(wholeMonth.to()).isEqualTo(TODAY);
        assertThat(wholeMonth.groups().getFirst().usual()).isEqualByComparingTo("100.00");

        // Una settimana non è un mese: niente solito. Per esercente, e con una categoria scelta.
        Groups week = exploreService.groups(Metric.EXPENSES, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11),
                By.MERCHANT, null, null, TODAY);
        assertThat(week.groups()).extracting(Group::name).containsExactly("ESSELUNGA MILANO", "PIZZERIA DA GINO");
        assertThat(week.groups().getFirst().usual()).isNull();

        // Il netto: le entrate e le spese in due elenchi.
        Groups net = exploreService.groups(Metric.NET, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                By.CATEGORY, null, null, TODAY);
        assertThat(net.total()).isEqualByComparingTo("1770.00");
        assertThat(net.income()).extracting(Group::name).containsExactly("Stipendio e pensione");
        assertThat(net.expenses()).extracting(Group::name).containsExactly("Spesa alimentare", "Bar e ristoranti");
    }

    @Test
    void thePageOfACategoryHasTheMonthTheYearTheMerchantsAndTheBudget() {
        Long groceries = category("Spesa alimentare").getId();
        budgetService.create(new BudgetRequest(groceries, "EUR", new BigDecimal("250.00")));

        CategoryOverview overview = exploreService.category(groceries, YearMonth.of(2026, 10), TODAY);

        assertThat(overview.name()).isEqualTo("Spesa alimentare");
        assertThat(overview.metric()).isEqualTo(Metric.EXPENSES);
        assertThat(overview.amount()).isEqualByComparingTo("120.00");
        assertThat(overview.usualSoFar()).isEqualByComparingTo("100.00");
        assertThat(overview.usualMonth()).isEqualByComparingTo("200.00");
        assertThat(overview.months()).hasSize(12);
        assertThat(overview.months().getLast()).isEqualTo(new MonthValue(YearMonth.of(2026, 10),
                new BigDecimal("120.00")));
        assertThat(overview.merchants()).singleElement().satisfies(m -> {
            assertThat(m.name()).isEqualTo("ESSELUNGA MILANO");
            assertThat(m.movements()).isEqualTo(1);
        });
        assertThat(overview.budget()).isNotNull();
        assertThat(overview.budget().monthlyAmount()).isEqualByComparingTo("250.00");

        // Una categoria di entrata guarda le entrate.
        CategoryOverview salary = exploreService.category(category("Stipendio e pensione").getId(),
                YearMonth.of(2026, 9), TODAY);
        assertThat(salary.metric()).isEqualTo(Metric.INCOME);
        assertThat(salary.amount()).isEqualByComparingTo("2000.00");
        assertThat(salary.budget()).isNull();
    }

    @Test
    void wrongRequestsAreRejected() {
        assertThatThrownBy(() -> exploreService.series(Metric.EXPENSES, Period.YEAR, TODAY.plusDays(1), null, null,
                TODAY)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("non è ancora arrivato");
        assertThatThrownBy(() -> exploreService.groups(Metric.EXPENSES, TODAY, TODAY.minusDays(1), By.CATEGORY, null,
                null, TODAY)).hasMessageContaining("al massimo un anno");
        assertThatThrownBy(() -> exploreService.groups(Metric.EXPENSES, TODAY.plusDays(1), TODAY.plusDays(7),
                By.CATEGORY, null, null, TODAY)).hasMessageContaining("non è ancora arrivato");
        assertThatThrownBy(() -> exploreService.groups(Metric.EXPENSES, TODAY.minusYears(2), TODAY, By.CATEGORY, null,
                null, TODAY)).hasMessageContaining("al massimo un anno");

        assertThat(mvc.get().uri("/api/explore/series?metric=EXPENSES&period=YEAR")).hasStatusOk();
        assertThat(mvc.get().uri("/api/explore/series?metric=BOH&period=YEAR")).hasStatus(400);
        assertThat(mvc.get().uri("/api/explore/groups?metric=EXPENSES&by=CATEGORY&from=2026-10-01&to=2026-10-02"
                + "&currency=EURO")).hasStatus(400);
        assertThat(mvc.get().uri("/api/categories/999999/overview")).hasStatus(404);
    }

    private void save(LocalDate date, String amount, String description, TransactionType type, String category) {
        BankTransaction transaction = new BankTransaction(account, "tx-" + (++counter), TransactionStatus.BOOKED, date,
                null, new BigDecimal(amount), "EUR", null, description, "{}");
        transaction.setManualType(type);
        transaction.setManualCategory(category(category));
        transactionRepository.save(transaction);
    }

    private Category category(String name) {
        return categoryRepository.findByNameIgnoreCase(name).orElseThrow();
    }
}
