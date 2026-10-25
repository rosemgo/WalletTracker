package it.wallettracker.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.account.AccountRole;
import it.wallettracker.category.Category;
import it.wallettracker.category.CategoryRepository;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.connection.BankConnection;
import it.wallettracker.connection.BankConnectionRepository;
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

/**
 * Il dettaglio di un movimento, i movimenti simili e la correzione in blocco con l'Annulla (docs/37). I dati sono
 * inventati: quattro acquisti da Amazon (tre in Shopping, uno in Istruzione e formazione), una pizzeria, il canone del
 * conto, un movimento dal file di Trade Republic e uno da un CSV.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class TransactionDetailTest {

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

    private Account account;
    private int counter;
    private final LocalDate today = LocalDate.now();

    private BankTransaction book;
    private BankTransaction headphones;
    private BankTransaction mouse;
    private BankTransaction cable;
    private BankTransaction course;

    @BeforeEach
    void history() {
        BankConnection connection = connectionRepository.save(new BankConnection("Banca Alfa", "IT", "session-1",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        account = accountRepository.save(new Account(connection, "key-1", "uid-1", null, "Carta di credito", "EUR"));
        book = purchase(1, "-9.98", "Shopping");
        headphones = purchase(20, "-23.40", "Shopping");
        mouse = purchase(40, "-14.90", "Shopping");
        course = purchase(60, "-30.00", "Istruzione e formazione");
        cable = purchase(500, "-6.00", "Shopping"); // più di un anno fa: fuori dal riepilogo, non dai simili
        classificationService.classifyAll();
    }

    @AfterEach
    void cleanDatabase() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    @Test
    void theDetailHasEverythingAboutTheMovementAndItsMerchant() {
        assertThat(mvc.get().uri("/api/transactions/{id}", book.getId()))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.transaction.id").isEqualTo(book.getId().intValue());
                    json.assertThat().extractingPath("$.transaction.category").isEqualTo("Shopping");
                    json.assertThat().extractingPath("$.categoryOrigin").isEqualTo("MANUAL");
                    json.assertThat().extractingPath("$.origin").isEqualTo("BANK");
                    json.assertThat().extractingPath("$.rawJson").asString().contains("AMAZON");
                    // L'esercente: quattro movimenti nell'ultimo anno (questo compreso), in due categorie.
                    json.assertThat().extractingPath("$.merchant.key").isEqualTo("PAGAMENTO POS AMAZON IT");
                    json.assertThat().extractingPath("$.merchant.movements").isEqualTo(4);
                    json.assertThat().extractingPath("$.merchant.total").isEqualTo(-78.28);
                    json.assertThat().extractingPath("$.merchant.categories").isEqualTo(2);
                    // Gli altri, dal più recente; non questo.
                    json.assertThat().extractingPath("$.merchant.recent[*].id").asArray().containsExactly(
                            headphones.getId().intValue(), mouse.getId().intValue(), course.getId().intValue());
                    json.assertThat().extractingPath("$.suggestion").isNull();
                });
    }

    @Test
    void theOriginOfTheCategoryIsARuleOrNothing() {
        // "Canone Mensile" è una regola di partenza: categoria "Costi bancari".
        BankTransaction fee = save(account, 3, "-2.00", "Canone Mensile conto", "tx-" + (++counter));
        BankTransaction unknown = save(account, 3, "-5.00", "NEGOZIO SENZA NOME", "tx-" + (++counter));
        classificationService.classifyAll();

        assertThat(mvc.get().uri("/api/transactions/{id}", fee.getId())).bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.categoryOrigin").isEqualTo("RULE");
            json.assertThat().extractingPath("$.ruleName").asString().isNotBlank();
        });
        assertThat(mvc.get().uri("/api/transactions/{id}", unknown.getId())).bodyJson()
                .extractingPath("$.categoryOrigin").isEqualTo("NONE");
    }

    @Test
    void aMovementWithoutCategoryShowsItsSuggestion() {
        // Due scelte a mano per la pizzeria, poi un movimento nuovo senza categoria: il suggerimento è "Bar e ristoranti".
        for (int days : List.of(30, 60)) {
            BankTransaction pizza = save(account, days, "-20.00", "PIZZERIA DA GINO", "tx-" + (++counter));
            pizza.setManualCategory(category("Bar e ristoranti"));
            transactionRepository.save(pizza);
        }
        BankTransaction tonight = save(account, 0, "-26.00", "PIZZERIA DA GINO", "tx-" + (++counter));
        classificationService.classifyAll();

        assertThat(mvc.get().uri("/api/transactions/{id}", tonight.getId())).bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.suggestion.category").isEqualTo("Bar e ristoranti");
            json.assertThat().extractingPath("$.suggestion.merchantKey").isEqualTo("PIZZERIA DA GINO");
        });
    }

    @Test
    void movementsFromAFileSayWhereTheyCameFrom() {
        BankTransaction fromFile = save(account, 2, "-1.00", "Interest", BankTransaction.FILE_KEY_PREFIX + "abc");
        Account manual = accountRepository.save(Account.manual("Banca Beta", "Conto CSV", null, "EUR",
                AccountRole.MAIN));
        BankTransaction fromCsv = save(manual, 2, "-1.00", "BAR", "csv:abc");

        assertThat(mvc.get().uri("/api/transactions/{id}", fromFile.getId())).bodyJson()
                .extractingPath("$.origin").isEqualTo("TRADE_REPUBLIC_FILE");
        assertThat(mvc.get().uri("/api/transactions/{id}", fromCsv.getId())).bodyJson()
                .extractingPath("$.origin").isEqualTo("CSV");
        assertThat(mvc.get().uri("/api/transactions/999999")).hasStatus(404);
    }

    @Test
    void theSimilarAreTheOtherMovementsOfTheMerchantWithTheOldCategory() {
        Long shopping = category("Shopping").getId();

        // Gli altri tre in Shopping (anche quello di più di un anno fa), non quello in Istruzione e non questo.
        assertThat(similarIds(book, shopping)).containsExactly(headphones.getId().intValue(),
                mouse.getId().intValue(), cable.getId().intValue());
        assertThat(similarIds(book, category("Istruzione e formazione").getId()))
                .containsExactly(course.getId().intValue());
    }

    @Test
    void severalMovementsChangeTogetherAndTheAnswerUndoesIt() {
        Long multimedia = category("Multimedia ed elettronica").getId();
        Long shopping = category("Shopping").getId();

        MvcTestResult moved = changes("[{\"id\": %d, \"categoryId\": %d}, {\"id\": %d, \"categoryId\": %d}]"
                .formatted(book.getId(), multimedia, headphones.getId(), multimedia));
        assertThat(moved).hasStatusOk();
        assertThat(categoryOf(book)).isEqualTo("Multimedia ed elettronica");
        assertThat(categoryOf(headphones)).isEqualTo("Multimedia ed elettronica");

        // La risposta è la scelta di prima: rimandarla annulla.
        String previous = body(moved);
        assertThat(JsonPath.<List<Integer>>read(previous, "$[*].categoryId"))
                .containsExactly(shopping.intValue(), shopping.intValue());
        assertThat(changes(previous)).hasStatusOk();
        assertThat(categoryOf(book)).isEqualTo("Shopping");
        assertThat(categoryOf(headphones)).isEqualTo("Shopping");
    }

    @Test
    void aWrongBlockChangesNothing() {
        Long multimedia = category("Multimedia ed elettronica").getId();

        assertThat(changes("[]")).hasStatus(400);
        assertThat(changes("[{\"id\": %d, \"categoryId\": %d}, {\"id\": %d, \"categoryId\": null}]"
                .formatted(book.getId(), multimedia, book.getId()))).hasStatus(400); // lo stesso due volte
        // Un movimento che non esiste: 404, e nemmeno l'altro è cambiato.
        assertThat(changes("[{\"id\": %d, \"categoryId\": %d}, {\"id\": 999999, \"categoryId\": %d}]"
                .formatted(book.getId(), multimedia, multimedia))).hasStatus(404);
        assertThat(changes("[{\"id\": %d, \"categoryId\": 999999}]".formatted(book.getId()))).hasStatus(400);
        assertThat(categoryOf(book)).isEqualTo("Shopping");
    }

    private List<Integer> similarIds(BankTransaction transaction, Long categoryId) {
        return JsonPath.read(body(mvc.get().uri("/api/transactions/{id}/similar?categoryId={c}", transaction.getId(),
                categoryId).exchange()), "$[*].id");
    }

    private MvcTestResult changes(String json) {
        return mvc.put().uri("/api/transactions/manual-categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String categoryOf(BankTransaction transaction) {
        return JsonPath.read(body(mvc.get().uri("/api/transactions/{id}", transaction.getId()).exchange()),
                "$.transaction.category");
    }

    /** Un acquisto su Amazon di {@code daysAgo} giorni fa, con la categoria scelta a mano. */
    private BankTransaction purchase(int daysAgo, String amount, String category) {
        BankTransaction transaction = save(account, daysAgo, amount, "PAGAMENTO POS AMAZON.IT " + daysAgo,
                "tx-" + (++counter));
        transaction.setManualCategory(category(category));
        return transactionRepository.save(transaction);
    }

    private BankTransaction save(Account owner, int daysAgo, String amount, String description, String dedupKey) {
        return transactionRepository.save(new BankTransaction(owner, dedupKey, TransactionStatus.BOOKED,
                today.minusDays(daysAgo), null, new BigDecimal(amount), "EUR", null, description,
                "{\"remittance_information\": [\"" + description + "\"]}"));
    }

    private Category category(String name) {
        return categoryRepository.findByNameIgnoreCase(name).orElseThrow();
    }
}
