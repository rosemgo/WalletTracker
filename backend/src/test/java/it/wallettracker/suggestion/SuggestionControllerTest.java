package it.wallettracker.suggestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.account.AccountRole;
import it.wallettracker.category.Category;
import it.wallettracker.category.CategoryRepository;
import it.wallettracker.classification.ClassificationRule;
import it.wallettracker.classification.ClassificationRule.AmountSign;
import it.wallettracker.classification.ClassificationRule.MatchMode;
import it.wallettracker.classification.ClassificationRuleRepository;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.connection.BankConnection;
import it.wallettracker.connection.BankConnectionRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Test dei suggerimenti di categoria dalla storia (vedi AccountControllerTest per MockMvcTester). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class SuggestionControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ClassificationService classificationService;

    @Autowired
    BankTransactionRepository transactionRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    SuggestionDismissalRepository dismissalRepository;

    @Autowired
    BankConnectionRepository connectionRepository;

    @Autowired
    ClassificationRuleRepository ruleRepository;

    private Account account;
    private Category bars;
    private int counter;

    @BeforeEach
    void anAccount() {
        account = accountRepository.save(Account.manual("Banca", "Conto", null, "EUR", AccountRole.MAIN));
        bars = categoryRepository.findByNameIgnoreCase("Bar e ristoranti").orElseThrow();
    }

    @AfterEach
    void cleanDatabase() {
        dismissalRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
        ruleRepository.findAll().stream().filter(r -> !r.isBuiltin()).forEach(ruleRepository::delete);
    }

    @Test
    void aMerchantYouCategorisedSuggestsTheSameCategoryForItsOtherMovements() {
        chosen(expense("PIZZERIA DA GINO 12/10"), bars);
        expense("PIZZERIA DA GINO 19/10");
        expense("PIZZERIA DA GINO 26/10");
        expense("BAR CENTRALE");                       // mai categorizzato: nessun suggerimento
        classificationService.classifyAll();

        assertThat(mvc.get().uri("/api/category-suggestions"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.length()").isEqualTo(1);
                    json.assertThat().extractingPath("$[0].merchantKey").isEqualTo("PIZZERIA DA GINO");
                    json.assertThat().extractingPath("$[0].merchantName").isEqualTo("PIZZERIA DA GINO");
                    json.assertThat().extractingPath("$[0].category").isEqualTo("Bar e ristoranti");
                    json.assertThat().extractingPath("$[0].transactions").isEqualTo(2);
                    json.assertThat().extractingPath("$[0].examples").isEqualTo(1);
                });
    }

    @Test
    void acceptingGivesTheCategoryToAllTheMovementsOfTheMerchant() {
        chosen(expense("PIZZERIA DA GINO 12/10"), bars);
        BankTransaction second = expense("PIZZERIA DA GINO 19/10");
        BankTransaction third = expense("PIZZERIA DA GINO 26/10");
        BankTransaction other = expense("BAR CENTRALE");
        classificationService.classifyAll();

        assertThat(post("/accept", "PIZZERIA DA GINO", bars.getId()))
                .hasStatusOk()
                .bodyJson().extractingPath("$.updated").isEqualTo(2);

        // Come una scelta a mano: categoria scelta e categoria del movimento.
        for (BankTransaction t : new BankTransaction[] {second, third}) {
            BankTransaction saved = transactionRepository.findById(t.getId()).orElseThrow();
            assertThat(saved.getManualCategory().getId()).isEqualTo(bars.getId());
            assertThat(saved.getCategory().getId()).isEqualTo(bars.getId());
        }
        assertThat(transactionRepository.findById(other.getId()).orElseThrow().getCategory()).isNull();
        // Non resta niente da suggerire.
        assertThat(mvc.get().uri("/api/category-suggestions")).hasStatusOk().bodyJson()
                .extractingPath("$.length()").isEqualTo(0);
    }

    @Test
    void aDismissedSuggestionDoesNotComeBack() {
        chosen(expense("PIZZERIA DA GINO 12/10"), bars);
        BankTransaction second = expense("PIZZERIA DA GINO 19/10");
        classificationService.classifyAll();

        assertThat(post("/dismissed", "PIZZERIA DA GINO", bars.getId())).hasStatus(204);
        assertThat(post("/dismissed", "PIZZERIA DA GINO", bars.getId())).hasStatus(204); // ripeterlo non fa danni

        assertThat(dismissalRepository.count()).isEqualTo(1);
        assertThat(mvc.get().uri("/api/category-suggestions")).hasStatusOk().bodyJson()
                .extractingPath("$.length()").isEqualTo(0);
        // Il movimento resta com'era.
        assertThat(transactionRepository.findById(second.getId()).orElseThrow().getCategory()).isNull();
    }

    @Test
    void differentChoicesForTheSameMerchantGiveNoSuggestion() {
        chosen(expense("AMAZON MARKETPLACE"), bars);
        chosen(expense("AMAZON MARKETPLACE"), categoryRepository.findByNameIgnoreCase("Shopping").orElseThrow());
        expense("AMAZON MARKETPLACE");
        classificationService.classifyAll();

        assertThat(mvc.get().uri("/api/category-suggestions")).hasStatusOk().bodyJson()
                .extractingPath("$.length()").isEqualTo(0);
    }

    @Test
    void amazonAsTheBankWritesIt() {
        // I testi veri (anonimizzati) di un conto collegato, intestato a "MARIO ROSSI".
        BankConnection connection = connectionRepository.save(new BankConnection("Banca", "IT", "s-1",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account owned = accountRepository.save(new Account(connection, "k-1", "uid-1", null, "MARIO ROSSI", "EUR"));
        Category shopping = categoryRepository.findByNameIgnoreCase("Shopping").orElseThrow();
        Category groceries = categoryRepository.findByNameIgnoreCase("Spesa alimentare").orElseThrow();
        ruleRepository.save(new ClassificationRule("Amazon", 100, MatchMode.CONTAINS, "amazon.it", AmountSign.NEGATIVE,
                TransactionType.EXPENSE, shopping));
        chosen(expense(owned, null, "Amazon.it              LUXEMBOURG     LUX"), shopping);
        chosen(expense(owned, null, "AMAZON* BT9YM4924      LUXEMBOURG     LUX"), groceries);
        expense(owned, null, "AMAZON* BT2BJ4CA4      LUXEMBOURG     LUX");   // stesso esercente del precedente
        expense(owned, "Amazon.it", "amazon.it");                         // categoria dalla regola
        BankTransaction withOwnerName = expense(owned, "MARIO ROSSI", "amazon.it");
        expense(owned, "MARIO ROSSI", "ESSELUNGA MILANO");                // altro negozio, stessa controparte
        classificationService.classifyAll();

        // Il menu di un movimento con il titolare come controparte: l'esercente è nella causale (AMAZON),
        // e contano le categorie degli altri movimenti AMAZON…, scelte a mano o messe dalla regola.
        assertThat(mvc.get().uri("/api/category-suggestions/for-transaction/{id}", withOwnerName.getId()))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.word").isEqualTo("AMAZON");
                    json.assertThat().extractingPath("$.categories[0].category").isEqualTo("Shopping");
                    json.assertThat().extractingPath("$.categories[0].times").isEqualTo(2); // a mano + regola
                    json.assertThat().extractingPath("$.categories[1].category").isEqualTo("Spesa alimentare");
                    json.assertThat().extractingPath("$.categories.length()").isEqualTo(2);
                });

        // Il suggerimento "per tutti" usa la chiave precisa: i due AMAZON* con codici diversi ora coincidono;
        // i movimenti con il titolare come controparte non finiscono tutti insieme sotto il suo nome.
        assertThat(mvc.get().uri("/api/category-suggestions"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.length()").isEqualTo(1);
                    json.assertThat().extractingPath("$[0].merchantKey").isEqualTo("AMAZON LUXEMBOURG LUX");
                    json.assertThat().extractingPath("$[0].category").isEqualTo("Spesa alimentare");
                    json.assertThat().extractingPath("$[0].transactions").isEqualTo(1);
                });
        assertThat(mvc.get().uri("/api/category-suggestions/for-transaction/999999")).hasStatus(404);
    }

    @Test
    void anUnknownCategoryOrAnEmptyMerchantIs400() {
        assertThat(post("/accept", "PIZZERIA DA GINO", 999999L)).hasStatus(400);
        assertThat(post("/dismissed", "PIZZERIA DA GINO", 999999L)).hasStatus(400);
        assertThat(post("/accept", " ", bars.getId())).hasStatus(400);
        assertThat(dismissalRepository.count()).isZero();
    }

    // --- Strumenti ---

    private BankTransaction expense(String counterparty) {
        return expense(account, counterparty, null);
    }

    private BankTransaction expense(Account on, String counterparty, String description) {
        return transactionRepository.save(new BankTransaction(on, "tx-" + (++counter), TransactionStatus.BOOKED,
                LocalDate.of(2026, 10, counter), null, new BigDecimal("-25.00"), "EUR", counterparty, description,
                "{}"));
    }

    /** La categoria scelta a mano, come dalla pagina Movimenti. */
    private void chosen(BankTransaction transaction, Category category) {
        transaction.setManualCategory(category);
        transactionRepository.save(transaction);
    }

    private MvcTestResult post(String path, String merchantKey, Long categoryId) {
        return mvc.post().uri("/api/category-suggestions" + path)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchantKey\": \"%s\", \"categoryId\": %d}".formatted(merchantKey, categoryId))
                .exchange();
    }
}
