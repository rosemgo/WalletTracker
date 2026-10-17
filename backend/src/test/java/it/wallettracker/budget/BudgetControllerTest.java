package it.wallettracker.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.category.Category;
import it.wallettracker.category.CategoryRepository;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.connection.BankConnection;
import it.wallettracker.connection.BankConnectionRepository;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.TransactionStatus;
import it.wallettracker.transaction.TransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonContent;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Test dei budget (vedi AccountControllerTest per MockMvcTester). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class BudgetControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ClassificationService classificationService;

    @Autowired
    BudgetRepository budgetRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    BankTransactionRepository transactionRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    BankConnectionRepository connectionRepository;

    private int counter;

    @AfterEach
    void cleanDatabase() {
        budgetRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    @Test
    void aBudgetShowsTheExpensesOfItsCategoryInTheMonthRefundsIncluded() {
        Account account = account();
        expense(account, "2026-09-03", "-150.00", "Spesa alimentare");
        expense(account, "2026-09-20", "-200.00", "Spesa alimentare");
        BankTransaction refund = expense(account, "2026-09-22", "30.00", "Spesa alimentare"); // rimborso: riduce la spesa
        refund.setManualType(TransactionType.EXPENSE);
        transactionRepository.save(refund);
        expense(account, "2026-09-10", "-500.00", "Spese fisse e utenze");  // altra categoria: non conta
        expense(account, "2026-10-01", "-99.00", "Spesa alimentare");  // altro mese: non conta
        // I 3 mesi prima di settembre, per la media: 300 + 0 + 150 = 450 → 150 al mese.
        expense(account, "2026-06-15", "-300.00", "Spesa alimentare");
        expense(account, "2026-08-15", "-150.00", "Spesa alimentare");
        classificationService.classifyAll();

        assertThat(create(category("Spesa alimentare"), "EUR", "400"))
                .hasStatus(201)
                .bodyJson().extractingPath("$.category").isEqualTo("Spesa alimentare"); // il nome, da mostrare

        assertThat(mvc.get().uri("/api/budgets?month=2026-09"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.length()").isEqualTo(1);
                    amount(json, "$[0].spent", "320"); // 150 + 200 - 30
                    amount(json, "$[0].remaining", "80");
                    amount(json, "$[0].averageLast3Months", "150");
                    amount(json, "$[0].expectedSoFar", "400"); // mese passato: tutto il budget
                    json.assertThat().extractingPath("$[0].status").isEqualTo("WARNING"); // 80%
                });
    }

    @Test
    void theStatusDependsOnHowMuchOfTheBudgetIsSpent() {
        Account account = account();
        expense(account, "2026-09-03", "-79.00", "Bar e ristoranti");
        expense(account, "2026-09-03", "-100.00", "Shopping");
        expense(account, "2026-09-03", "-100.01", "Viaggi e vacanze");
        classificationService.classifyAll();
        for (String category : new String[] {"Bar e ristoranti", "Shopping", "Viaggi e vacanze"}) {
            create(category(category), "EUR", "100");
        }

        assertThat(mvc.get().uri("/api/budgets?month=2026-09"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$[0].status").isEqualTo("OK");      // 79%
                    json.assertThat().extractingPath("$[1].status").isEqualTo("WARNING"); // 100%: al limite
                    json.assertThat().extractingPath("$[2].status").isEqualTo("OVER");    // superato
                    amount(json, "$[2].remaining", "-0.01");
                });
    }

    @Test
    void aBudgetCanBeChangedAndDeletedButNotDuplicated() {
        create(category("Spesa alimentare"), "EUR", "400");
        Long budgetId = budgetRepository.findAll().getFirst().getId();

        assertThat(create(category("Spesa alimentare"), "EUR", "100"))
                .hasStatus(409)
                .bodyJson().extractingPath("$.detail").isEqualTo("Esiste già un budget per \"Spesa alimentare\" in EUR.");
        // La stessa categoria in un'altra valuta è un budget diverso.
        assertThat(create(category("Spesa alimentare"), "USD", "100")).hasStatus(201);

        assertThat(mvc.put().uri("/api/budgets/{id}", budgetId).contentType(MediaType.APPLICATION_JSON)
                .content(json(category("Spesa alimentare"), "EUR", "450.50")))
                .hasStatusOk()
                .bodyJson().satisfies(json -> amount(json, "$.monthlyAmount", "450.50"));

        assertThat(mvc.delete().uri("/api/budgets/{id}", budgetId)).hasStatus(204);
        assertThat(mvc.delete().uri("/api/budgets/{id}", budgetId)).hasStatus(404);
    }

    @Test
    void invalidBudgetsAreRejected() {
        Category groceries = category("Spesa alimentare");
        assertThat(create(groceries, "EUR", "0")).hasStatus(400);
        assertThat(create(groceries, "eur", "10")).hasStatus(400);
        assertThat(create(groceries, "EUR", "10.123"))
                .hasStatus(400);
        // Senza categoria, o con una categoria che non esiste.
        assertThat(mvc.post().uri("/api/budgets").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\": \"EUR\", \"monthlyAmount\": 10}")).hasStatus(400);
        assertThat(mvc.post().uri("/api/budgets").contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryId\": 999999, \"currency\": \"EUR\", \"monthlyAmount\": 10}"))
                .hasStatus(400)
                .bodyJson().extractingPath("$.detail")
                .isEqualTo("La categoria 999999 non esiste: forse è stata eliminata. Ricarica la pagina.");
        assertThat(mvc.get().uri("/api/budgets?month=2026-13")).hasStatus(400);
        assertThat(budgetRepository.count()).isZero();
    }

    @Test
    void theExpectedSpendingGrowsDayByDayInTheCurrentMonth() {
        BigDecimal amount = new BigDecimal("300.00");
        YearMonth september = YearMonth.of(2026, 9); // 30 giorni

        assertThat(BudgetService.expectedSoFar(amount, september, LocalDate.of(2026, 9, 10)))
                .isEqualByComparingTo("100");   // 10 giorni su 30
        assertThat(BudgetService.expectedSoFar(amount, september, LocalDate.of(2026, 9, 30)))
                .isEqualByComparingTo("300");
        assertThat(BudgetService.expectedSoFar(amount, september, LocalDate.of(2026, 10, 5)))
                .isEqualByComparingTo("300");   // mese passato
        assertThat(BudgetService.expectedSoFar(amount, september, LocalDate.of(2026, 8, 5)))
                .isEqualByComparingTo("0");     // mese futuro
    }

    private MvcTestResult create(Category category, String currency, String amount) {
        return mvc.post().uri("/api/budgets").contentType(MediaType.APPLICATION_JSON)
                .content(json(category, currency, amount)).exchange();
    }

    /** Il corpo JSON di un budget: la categoria si indica con il suo id. */
    private static String json(Category category, String currency, String amount) {
        return "{\"categoryId\": " + category.getId() + ", \"currency\": \"" + currency
                + "\", \"monthlyAmount\": " + amount + "}";
    }

    /** Confronta un importo del JSON come BigDecimal (vedi SummaryControllerTest). */
    private static void amount(JsonContent json, String path, String expected) {
        json.assertThat().extractingPath(path).satisfies(value ->
                assertThat(new BigDecimal(value.toString())).isEqualByComparingTo(expected));
    }

    private Account account() {
        counter++;
        BankConnection connection = connectionRepository.save(new BankConnection("Banca", "IT", "session-" + counter,
                Instant.now().plus(90, ChronoUnit.DAYS)));
        return accountRepository.save(new Account(connection, "key-" + counter, "uid-" + counter, null,
                "Conto " + counter, "EUR"));
    }

    /** Un movimento con la categoria scelta a mano: dopo classifyAll è una spesa (o un'entrata) di quella categoria. */
    private BankTransaction expense(Account account, String date, String amount, String category) {
        BankTransaction transaction = new BankTransaction(account, "tx-" + (++counter), TransactionStatus.BOOKED,
                LocalDate.parse(date), null, new BigDecimal(amount), "EUR", null, "NEGOZIO " + counter, "{}");
        transaction.setManualCategory(category(category));
        return transactionRepository.save(transaction);
    }

    /** Una categoria dell'elenco di partenza, per nome. */
    private Category category(String name) {
        return categoryRepository.findByNameIgnoreCase(name).orElseThrow();
    }
}
