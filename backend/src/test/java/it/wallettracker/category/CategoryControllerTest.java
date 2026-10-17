package it.wallettracker.category;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.budget.Budget;
import it.wallettracker.budget.BudgetRepository;
import it.wallettracker.classification.ClassificationRule;
import it.wallettracker.classification.ClassificationRuleRepository;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Test dell'elenco delle categorie: le categorie di partenza, aggiunta, rinomina "a cascata", eliminazione. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class CategoryControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    ClassificationRuleRepository ruleRepository;

    @Autowired
    ClassificationService classificationService;

    @Autowired
    BudgetRepository budgetRepository;

    @Autowired
    BankTransactionRepository transactionRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    BankConnectionRepository connectionRepository;

    @AfterEach
    void cleanDatabase() {
        budgetRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
        ruleRepository.findAll().stream().filter(rule -> !rule.isBuiltin()).forEach(ruleRepository::delete);
        categoryRepository.findAll().stream().filter(c -> !c.isBuiltin()).forEach(categoryRepository::delete);
    }

    @Test
    void theStarterListIsGroupedAndOrdered() {
        assertThat(mvc.get().uri("/api/categories"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    // Prima le spese, nell'ordine dell'elenco; poi entrate, investimenti, altro.
                    json.assertThat().extractingPath("$[0].name").isEqualTo("Spesa alimentare");
                    json.assertThat().extractingPath("$[0].kind").isEqualTo("EXPENSE");
                    json.assertThat().extractingPath("$[0].builtin").isEqualTo(true);
                    json.assertThat().extractingPath("$[?(@.name == 'Stipendio e pensione')].kind")
                            .asArray().containsExactly("INCOME");
                    json.assertThat().extractingPath("$[?(@.name == 'Dividendi')].kind")
                            .asArray().containsExactly("INVESTMENT");
                    json.assertThat().extractingPath("$[-1:].name").asArray().containsExactly("Saldo carta");
                });
    }

    @Test
    void aCategoryIsAddedOnceIgnoringCase() {
        assertThat(save(null, "{\"name\": \"  Animali domestici \", \"kind\": \"EXPENSE\"}"))
                .hasStatus(201)
                .bodyJson().extractingPath("$.name").isEqualTo("Animali domestici");

        assertThat(save(null, "{\"name\": \"animali DOMESTICI\", \"kind\": \"EXPENSE\"}"))
                .hasStatus(409)
                .bodyJson().extractingPath("$.detail").isEqualTo("Esiste già una categoria \"Animali domestici\".");
        assertThat(save(null, "{\"name\": \" \", \"kind\": \"EXPENSE\"}")).hasStatus(400);
        assertThat(save(null, "{\"name\": \"Prova\", \"kind\": \"BOH\"}")).hasStatus(400);
    }

    @Test
    void renamingACategoryUpdatesMovementsRulesAndBudgetsByItself() {
        // Una categoria creata apposta: i test non devono cambiare l'elenco di partenza, che usano anche gli altri.
        save(null, "{\"name\": \"Animali\", \"kind\": \"EXPENSE\"}");
        BankTransaction purchase = purchase();
        purchase.setManualCategory("Animali");
        transactionRepository.save(purchase);
        ClassificationRule rule = ruleRepository.save(new ClassificationRule("Zoo shop", 100,
                ClassificationRule.MatchMode.CONTAINS, "ZOO", ClassificationRule.AmountSign.ANY,
                TransactionType.EXPENSE, "Animali"));
        budgetRepository.save(new Budget("Animali", "EUR", new BigDecimal("100")));
        classificationService.classifyAll();

        Long id = categoryRepository.findByNameIgnoreCase("Animali").orElseThrow().getId();
        assertThat(save(id, "{\"name\": \"Animali domestici\", \"kind\": \"EXPENSE\"}"))
                .hasStatusOk()
                .bodyJson().extractingPath("$.uses").isEqualTo(1);

        // Nessuna riga di Java ha toccato movimenti, regole e budget: ci ha pensato ON UPDATE CASCADE.
        BankTransaction reloaded = transactionRepository.findById(purchase.getId()).orElseThrow();
        assertThat(reloaded.getManualCategory()).isEqualTo("Animali domestici");
        assertThat(reloaded.getCategory()).isEqualTo("Animali domestici");
        assertThat(ruleRepository.findById(rule.getId()).orElseThrow().getCategory()).isEqualTo("Animali domestici");
        assertThat(budgetRepository.findAll().getFirst().getCategory()).isEqualTo("Animali domestici");
    }

    @Test
    void deletingACategoryLeavesMovementsWithoutItButNotIfItHasABudget() {
        save(null, "{\"name\": \"Animali domestici\", \"kind\": \"EXPENSE\"}");
        Long id = categoryRepository.findByNameIgnoreCase("Animali domestici").orElseThrow().getId();
        BankTransaction purchase = purchase();
        purchase.setManualCategory("Animali domestici");
        transactionRepository.save(purchase);
        budgetRepository.save(new Budget("Animali domestici", "EUR", new BigDecimal("50")));

        assertThat(mvc.delete().uri("/api/categories/{id}", id))
                .hasStatus(409)
                .bodyJson().extractingPath("$.detail")
                .isEqualTo("C'è un budget per \"Animali domestici\": eliminalo prima dalla pagina Budget.");

        budgetRepository.deleteAll();
        assertThat(mvc.delete().uri("/api/categories/{id}", id)).hasStatus(204);
        // ON DELETE SET NULL: il movimento resta, senza categoria.
        assertThat(transactionRepository.findById(purchase.getId()).orElseThrow().getManualCategory()).isNull();
    }

    private MvcTestResult save(Long id, String json) {
        return (id == null ? mvc.post().uri("/api/categories") : mvc.put().uri("/api/categories/{id}", id))
                .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private BankTransaction purchase() {
        BankConnection connection = connectionRepository.save(new BankConnection("Banca", "IT", "session",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account account = accountRepository.save(new Account(connection, "key", "uid", null, "Conto", "EUR"));
        return transactionRepository.save(new BankTransaction(account, "tx-1", TransactionStatus.BOOKED,
                LocalDate.of(2026, 10, 1), null, new BigDecimal("-30.00"), "EUR", "ZALANDO", "Pagamento", "{}"));
    }
}
