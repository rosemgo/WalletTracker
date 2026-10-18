package it.wallettracker.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;

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
import org.springframework.test.json.JsonContent;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Test del riepilogo mensile (vedi AccountControllerTest per MockMvcTester). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class SummaryControllerTest {

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

    private int counter;

    @AfterEach
    void cleanDatabase() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    @Test
    void onlyRealIncomeAndExpensesCountAndEmptyMonthsAreFilled() {
        Account account = account();
        // Ottobre
        transaction(account, "2026-10-01", "2000.00", "STIPENDIO", "EUR");            // INCOME
        transaction(account, "2026-10-05", "-500.00", "SUPERMERCATO", "EUR");         // EXPENSE
        transaction(account, "2026-10-08", "-100.00", "BAR", "EUR");                  // EXPENSE
        pending(account, "2026-10-14", "-50.00", "BENZINA");                          // EXPENSE in attesa: conta
        transaction(account, "2026-10-10", "-300.00", "Trasferimento a mio conto", "EUR"); // INTERNAL_TRANSFER: non conta
        transaction(account, "2026-10-12", "50.00", "Div.su AZIONI XYZ", "EUR");      // INVESTMENT_INCOME
        transaction(account, "2026-10-12", "-13.00", "Rit.div AZIONI XYZ", "EUR");    // INVESTMENT_TAX
        transaction(account, "2026-10-13", "-7.00", null, "EUR");                     // TO_REVIEW: non conta, ma si segnala
        // Settembre
        transaction(account, "2026-09-20", "-200.00", "RISTORANTE", "EUR");
        // Fuori dal periodo: non conta
        transaction(account, "2026-11-01", "-999.00", "FUORI PERIODO", "EUR");
        classificationService.classifyAll();

        assertThat(mvc.get().uri("/api/summary/monthly?from=2026-08&to=2026-10"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.from").isEqualTo("2026-08");
                    json.assertThat().extractingPath("$.currencies.length()").isEqualTo(1);
                    json.assertThat().extractingPath("$.currencies[0].currency").isEqualTo("EUR");
                    json.assertThat().extractingPath("$.currencies[0].months.length()").isEqualTo(3);

                    // Agosto non ha movimenti: c'è comunque, con zero.
                    json.assertThat().extractingPath("$.currencies[0].months[0].month").isEqualTo("2026-08");
                    amount(json, "$.currencies[0].months[0].expenses", "0");

                    amount(json, "$.currencies[0].months[1].expenses", "200");
                    amount(json, "$.currencies[0].months[1].savings", "-200");

                    json.assertThat().extractingPath("$.currencies[0].months[2].month").isEqualTo("2026-10");
                    amount(json, "$.currencies[0].months[2].income", "2000");
                    amount(json, "$.currencies[0].months[2].expenses", "650");
                    amount(json, "$.currencies[0].months[2].savings", "1350");
                    amount(json, "$.currencies[0].months[2].investmentNet", "37");
                    json.assertThat().extractingPath("$.currencies[0].months[2].toReview").isEqualTo(1);

                    amount(json, "$.currencies[0].total.income", "2000");
                    amount(json, "$.currencies[0].total.expenses", "850");
                    amount(json, "$.currencies[0].total.savings", "1150");
                    json.assertThat().extractingPath("$.currencies[0].total.toReview").isEqualTo(1);
                });
    }

    @Test
    void expensesAreGroupedByCategoryAndRefundsReduceTheirCategory() {
        Account account = account();
        BankTransaction shoes = transaction(account, "2026-10-02", "-120.00", "NEGOZIO SCARPE", "EUR");
        BankTransaction refund = transaction(account, "2026-10-09", "40.00", "RIMBORSO NEGOZIO SCARPE", "EUR");
        BankTransaction groceries = transaction(account, "2026-10-03", "-60.00", "SUPERMERCATO", "EUR");
        transaction(account, "2026-10-04", "-15.00", "EDICOLA", "EUR"); // senza categoria
        transaction(account, "2026-10-05", "-2.00", "Canone Mensile", "EUR"); // regola: "Costi bancari"
        shoes.setManualCategory(category("Shopping"));
        refund.setManualCategory(category("Shopping"));
        refund.setManualType(TransactionType.EXPENSE); // un rimborso è una spesa "al contrario"
        groceries.setManualCategory(category("Spesa alimentare"));
        transactionRepository.saveAll(List.of(shoes, refund, groceries));
        classificationService.classifyAll();

        assertThat(mvc.get().uri("/api/summary/monthly?from=2026-10&to=2026-10"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    // Dalla spesa più alta: 120 - 40 di rimborso = 80 in Shopping.
                    json.assertThat().extractingPath("$.currencies[0].expensesByCategory.length()").isEqualTo(4);
                    json.assertThat().extractingPath("$.currencies[0].expensesByCategory[0].category")
                            .isEqualTo("Shopping");
                    amount(json, "$.currencies[0].expensesByCategory[0].amount", "80");
                    json.assertThat().extractingPath("$.currencies[0].expensesByCategory[0].movements").isEqualTo(2);
                    json.assertThat().extractingPath("$.currencies[0].expensesByCategory[1].category")
                            .isEqualTo("Spesa alimentare");
                    json.assertThat().extractingPath("$.currencies[0].expensesByCategory[2].category").isNull();
                    amount(json, "$.currencies[0].expensesByCategory[2].amount", "15");
                    json.assertThat().extractingPath("$.currencies[0].expensesByCategory[3].category")
                            .isEqualTo("Costi bancari");
                    // Il rimborso riduce anche le spese del mese: 120 - 40 + 60 + 15 + 2.
                    amount(json, "$.currencies[0].total.expenses", "157");
                });
    }

    @Test
    void differentCurrenciesAreNeverAddedTogether() {
        Account account = account();
        transaction(account, "2026-10-05", "-100.00", "SUPERMERCATO", "EUR");
        transaction(account, "2026-10-06", "-40.00", "TAXI NEW YORK", "USD");
        classificationService.classifyAll();

        assertThat(mvc.get().uri("/api/summary/monthly?from=2026-10&to=2026-10"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.currencies.length()").isEqualTo(2);
                    json.assertThat().extractingPath("$.currencies[0].currency").isEqualTo("EUR");
                    amount(json, "$.currencies[0].total.expenses", "100");
                    json.assertThat().extractingPath("$.currencies[1].currency").isEqualTo("USD");
                    amount(json, "$.currencies[1].total.expenses", "40");
                });
    }

    @Test
    void withoutParametersTheLastTwelveMonthsAreReturned() {
        transaction(account(), LocalDate.now().toString(), "-10.00", "BAR", "EUR");
        classificationService.classifyAll();

        assertThat(mvc.get().uri("/api/summary/monthly"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.to").isEqualTo(YearMonth.now().toString());
                    json.assertThat().extractingPath("$.currencies[0].months.length()").isEqualTo(12);
                });
    }

    @Test
    void wrongPeriodsAreRejected() {
        assertThat(mvc.get().uri("/api/summary/monthly?from=2026-10&to=2026-01")).hasStatus(400)
                .bodyJson().extractingPath("$.detail").isEqualTo("Il mese iniziale viene dopo quello finale.");
        assertThat(mvc.get().uri("/api/summary/monthly?from=2000-01&to=2026-10")).hasStatus(400)
                .bodyJson().extractingPath("$.detail").isEqualTo("Puoi chiedere al massimo 120 mesi alla volta.");
        assertThat(mvc.get().uri("/api/summary/monthly?from=2026-13")).hasStatus(400);
    }

    /**
     * Controlla un importo. Nel JSON 2000.00 e 2000 sono lo stesso numero, ma Java li legge in tipi diversi:
     * confrontarli come BigDecimal con {@code isEqualByComparingTo} ignora i decimali a zero.
     */
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

    private BankTransaction transaction(Account account, String date, String amount, String description,
            String currency) {
        return transactionRepository.save(new BankTransaction(account, "tx-" + (++counter), TransactionStatus.BOOKED,
                LocalDate.parse(date), null, new BigDecimal(amount), currency, null, description, "{}"));
    }

    private void pending(Account account, String date, String amount, String description) {
        transactionRepository.save(new BankTransaction(account, "tx-" + (++counter), TransactionStatus.PENDING,
                LocalDate.parse(date), null, new BigDecimal(amount), "EUR", null, description, "{}"));
    }

    /** Una categoria dell'elenco di partenza, per nome. */
    private Category category(String name) {
        return categoryRepository.findByNameIgnoreCase(name).orElseThrow();
    }
}
