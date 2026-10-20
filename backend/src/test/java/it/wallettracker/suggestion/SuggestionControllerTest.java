package it.wallettracker.suggestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.account.AccountRole;
import it.wallettracker.category.Category;
import it.wallettracker.category.CategoryRepository;
import it.wallettracker.classification.ClassificationService;
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
    void theCategoriesUsedForTheMerchantOfAMovementMostUsedFirst() {
        Category shopping = categoryRepository.findByNameIgnoreCase("Shopping").orElseThrow();
        chosen(expense("AMAZON MARKETPLACE 1"), shopping);
        chosen(expense("AMAZON MARKETPLACE 2"), shopping);
        chosen(expense("AMAZON MARKETPLACE 3"), bars);
        BankTransaction itself = expense("AMAZON MARKETPLACE 4");
        chosen(itself, bars);                          // la scelta del movimento stesso non conta
        chosen(expense("PIZZERIA DA GINO"), bars);     // un altro esercente: non conta
        classificationService.classifyAll();

        assertThat(mvc.get().uri("/api/category-suggestions/for-transaction/{id}", itself.getId()))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.length()").isEqualTo(2);
                    json.assertThat().extractingPath("$[0].category").isEqualTo("Shopping");
                    json.assertThat().extractingPath("$[0].times").isEqualTo(2);
                    json.assertThat().extractingPath("$[1].category").isEqualTo("Bar e ristoranti");
                    json.assertThat().extractingPath("$[1].times").isEqualTo(1);
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
        return transactionRepository.save(new BankTransaction(account, "tx-" + (++counter), TransactionStatus.BOOKED,
                LocalDate.of(2026, 10, counter), null, new BigDecimal("-25.00"), "EUR", counterparty, null, "{}"));
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
