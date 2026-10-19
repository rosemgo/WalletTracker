package it.wallettracker.classification;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.category.Category;
import it.wallettracker.category.CategoryRepository;
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

/** Test delle API delle regole: elenco, creazione, modifica, cancellazione, prova. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class RuleControllerTest {


    @Autowired
    MockMvcTester mvc;

    @Autowired
    ClassificationService classificationService;

    @Autowired
    ClassificationRuleRepository ruleRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    BankTransactionRepository transactionRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    BankConnectionRepository connectionRepository;

    private BankTransaction groceries;

    @BeforeEach
    void someTransactions() {
        BankConnection connection = connectionRepository.save(new BankConnection("Banca", "IT", "s-1",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account account = accountRepository.save(new Account(connection, "k-1", "uid-1", null, "Conto", "EUR"));
        groceries = transaction(account, "t-1", "-54.20", "ESSELUNGA MILANO");
        transaction(account, "t-2", "-9.90", "NETFLIX");
        transaction(account, "t-3", "120.00", "Div.su 100 ENI");   // regola di partenza "Dividendo"
        classificationService.classifyAll();
    }

    @AfterEach
    void cleanDatabase() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
        ruleRepository.findAll().stream().filter(r -> !r.isBuiltin()).forEach(ruleRepository::delete);
    }

    @Test
    void listsTheStarterRulesWithHowManyTimesTheyWereUsed() {
        assertThat(mvc.get().uri("/api/rules"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$[?(@.name == 'Dividendo')].uses").asArray().containsExactly(1);
                    json.assertThat().extractingPath("$[?(@.name == 'Dividendo')].builtin").asArray().containsExactly(true);
                });
    }

    @Test
    void aNewRuleIsCreatedAndAppliedToTheExistingTransactions() {
        assertThat(post("/api/rules", groceries()))
                .hasStatus(201)
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.uses").isEqualTo(1);
                    json.assertThat().extractingPath("$.builtin").isEqualTo(false);
                });

        BankTransaction reloaded = transactionRepository.findById(groceries.getId()).orElseThrow();
        // Il movimento tiene l'id della categoria (leggerne l'id non richiede di caricarla).
        assertThat(reloaded.getCategory().getId()).isEqualTo(category("Spesa alimentare").getId());
    }

    @Test
    void disablingARuleReclassifies() {
        ClassificationRule dividend = ruleRepository.findAll().stream()
                .filter(r -> r.getName().equals("Dividendo")).findFirst().orElseThrow();
        String disabled = """
                {"name": "Dividendo", "priority": 10, "enabled": false, "matchMode": "STARTS_WITH",
                 "pattern": "Div.su", "amountSign": "POSITIVE", "resultType": "INVESTMENT_INCOME", "categoryId": %d}
                """.formatted(category("Dividendi").getId());

        assertThat(mvc.put().uri("/api/rules/{id}", dividend.getId()).contentType(MediaType.APPLICATION_JSON).content(disabled))
                .hasStatusOk()
                .bodyJson().extractingPath("$.uses").isEqualTo(0);

        // Senza la regola, il dividendo diventa una semplice entrata.
        assertThat(transactionRepository.findAll()).filteredOn(t -> t.getAmount().signum() > 0)
                .singleElement().satisfies(t -> assertThat(t.getType()).isEqualTo(TransactionType.INCOME));
        dividend.setEnabled(true);   // rimettiamo a posto la regola di partenza per gli altri test
        ruleRepository.save(dividend);
    }

    @Test
    void userRulesCanBeDeletedButStarterRulesCannot() {
        ClassificationRule starter = ruleRepository.findAll().stream().filter(ClassificationRule::isBuiltin)
                .findFirst().orElseThrow();
        assertThat(mvc.delete().uri("/api/rules/{id}", starter.getId()))
                .hasStatus(400)
                .bodyJson().extractingPath("$.detail").asString().contains("disattivarle");

        post("/api/rules", groceries());
        ClassificationRule created = ruleRepository.findAll().stream().filter(r -> !r.isBuiltin())
                .findFirst().orElseThrow();
        assertThat(mvc.delete().uri("/api/rules/{id}", created.getId())).hasStatus(204);
        assertThat(ruleRepository.findById(created.getId())).isEmpty();
    }

    @Test
    void aPreviewShowsTheMatchingTransactionsWithoutSaving() {
        long rulesBefore = ruleRepository.count();

        assertThat(post("/api/rules/preview", groceries()))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.matches").isEqualTo(1);
                    json.assertThat().extractingPath("$.examples[0].description").isEqualTo("ESSELUNGA MILANO");
                });
        assertThat(ruleRepository.count()).isEqualTo(rulesBefore);
    }

    @Test
    void thePreviewFindsAMovementWhateverTheNumberOfSpaces() {
        // Un pagamento con carta in campi a larghezza fissa, come lo mandano molte banche. In pagina si vede
        // "MAR GROUP SPA FOGLIANISE ITA": la regola scritta così deve trovarlo.
        Account account = accountRepository.findAll().getFirst();
        transaction(account, "t-4", "-6.56", "MAR GROUP SPA   FOGLIANISE    ITA");

        assertThat(post("/api/rules/preview", """
                {"name": "Decò", "priority": 100, "enabled": true, "matchMode": "CONTAINS",
                 "pattern": "MAR GROUP SPA FOGLIANISE", "amountSign": "NEGATIVE", "resultType": "EXPENSE"}
                """))
                .hasStatusOk()
                .bodyJson().extractingPath("$.matches").isEqualTo(1);
    }

    @Test
    void anIncompleteRuleIsRejected() {
        assertThat(post("/api/rules", """
                {"name": "", "priority": 100, "enabled": true, "matchMode": "CONTAINS",
                 "pattern": "x", "amountSign": "ANY", "resultType": "EXPENSE"}
                """)).hasStatus(400);
    }

    private MvcTestResult post(String uri, String json) {
        return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private BankTransaction transaction(Account account, String key, String amount, String description) {
        return transactionRepository.save(new BankTransaction(account, key, TransactionStatus.BOOKED,
                LocalDate.of(2026, 10, 1), null, new BigDecimal(amount), "EUR", null, description, "{}"));
    }

    /** Una categoria dell'elenco di partenza, per nome. */
    private Category category(String name) {
        return categoryRepository.findByNameIgnoreCase(name).orElseThrow();
    }

    /** Una regola nuova: "se il testo contiene ESSELUNGA ed è un'uscita, è una spesa (Spesa alimentare)". */
    private String groceries() {
        return """
                {"name": "Supermercato", "priority": 100, "enabled": true, "matchMode": "CONTAINS",
                 "pattern": "esselunga", "amountSign": "NEGATIVE", "resultType": "EXPENSE", "categoryId": %d}
                """.formatted(category("Spesa alimentare").getId());
    }
}
