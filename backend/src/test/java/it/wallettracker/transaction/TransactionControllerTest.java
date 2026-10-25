package it.wallettracker.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.category.Category;
import it.wallettracker.category.CategoryRepository;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.connection.BankConnection;
import it.wallettracker.connection.BankConnectionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Test delle API dei movimenti (vedi AccountControllerTest per MockMvcTester). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class TransactionControllerTest {

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
    void transactionsCanBeFilteredByAccountAndTypeAndArePaged() {
        Account first = account();
        Account second = account();
        transaction(first, LocalDate.of(2026, 9, 1), "-10.00", "BAR");
        transaction(first, LocalDate.of(2026, 9, 3), "-20.00", "SUPERMERCATO");
        BankTransaction toReview = transaction(first, LocalDate.of(2026, 9, 2), "-7.00", null);
        transaction(second, LocalDate.of(2026, 9, 4), "1500.00", "STIPENDIO");
        classificationService.classifyAll();

        // Filtro per conto: 3 movimenti, dal più recente.
        assertThat(mvc.get().uri("/api/transactions?accountId={id}", first.getId()))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.totalItems").isEqualTo(3);
                    json.assertThat().extractingPath("$.items[0].description").isEqualTo("SUPERMERCATO");
                    json.assertThat().extractingPath("$.items[2].description").isEqualTo("BAR");
                });

        // Filtro per tipo: solo il movimento senza testo è da verificare.
        assertThat(mvc.get().uri("/api/transactions?type=TO_REVIEW"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.totalItems").isEqualTo(1);
                    json.assertThat().extractingPath("$.items[0].id").isEqualTo(toReview.getId().intValue());
                });

        // Pagine da 3: la seconda pagina contiene solo il movimento più vecchio.
        assertThat(mvc.get().uri("/api/transactions?size=3&page=1"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.totalItems").isEqualTo(4);
                    json.assertThat().extractingPath("$.totalPages").isEqualTo(2);
                    json.assertThat().extractingPath("$.items.length()").isEqualTo(1);
                    json.assertThat().extractingPath("$.items[0].description").isEqualTo("BAR");
                });
    }

    @Test
    void aManualCorrectionChangesTheTypeAndCanBeRemoved() {
        BankTransaction purchase = transaction(account(), LocalDate.of(2026, 9, 1), "-30.00", "NEGOZIO");
        classificationService.classifyAll();

        assertThat(correct(purchase, "\"IGNORED\""))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.type").isEqualTo("IGNORED");
                    json.assertThat().extractingPath("$.manualType").isEqualTo("IGNORED");
                });

        // {"type": null} toglie la correzione: decide di nuovo il motore.
        assertThat(correct(purchase, "null"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.type").isEqualTo("EXPENSE");
                    json.assertThat().extractingPath("$.manualType").isNull();
                });
    }

    @Test
    void aManualCategoryWinsOverTheRuleAndCanBeRemoved() {
        // "Canone Mensile" è una regola di partenza: tipo EXPENSE, categoria "Costi bancari".
        BankTransaction fee = transaction(account(), LocalDate.of(2026, 9, 1), "-2.00", "Canone Mensile conto");
        classificationService.classifyAll();

        Long shopping = category("Shopping").getId();
        assertThat(correctCategory(fee, shopping))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.categoryId").isEqualTo(shopping.intValue());
                    json.assertThat().extractingPath("$.category").isEqualTo("Shopping"); // il nome, da mostrare
                    json.assertThat().extractingPath("$.manualCategoryId").isEqualTo(shopping.intValue());
                    json.assertThat().extractingPath("$.type").isEqualTo("EXPENSE");
                });

        // Anche una correzione del tipo (che riclassifica tutto) non la perde.
        assertThat(correct(fee, "\"INCOME\"")).hasStatusOk()
                .bodyJson().extractingPath("$.category").isEqualTo("Shopping");
        correct(fee, "null");

        // Tolta la scelta manuale, torna la categoria della regola.
        assertThat(correctCategory(fee, null))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.category").isEqualTo("Costi bancari");
                    json.assertThat().extractingPath("$.manualCategoryId").isNull();
                });
    }

    @Test
    void transactionsCanBeFilteredByPeriodAndWithoutCategory() {
        Account account = account();
        BankTransaction september = transaction(account, LocalDate.of(2026, 9, 30), "-10.00", "PIZZERIA SETTEMBRE");
        BankTransaction first = transaction(account, LocalDate.of(2026, 10, 1), "-20.00", "PIZZERIA PRIMO OTTOBRE");
        BankTransaction last = transaction(account, LocalDate.of(2026, 10, 31), "-30.00", "PIZZERIA FINE OTTOBRE");
        transaction(account, LocalDate.of(2026, 11, 1), "-40.00", "PIZZERIA NOVEMBRE");
        classificationService.classifyAll();
        last.setManualCategory(category("Bar e ristoranti"));
        transactionRepository.save(last);
        classificationService.classifyAll();

        // Ottobre: il primo e l'ultimo giorno sono inclusi.
        assertThat(mvc.get().uri("/api/transactions?from=2026-10-01&to=2026-10-31"))
                .hasStatusOk().bodyJson().satisfies(json -> {
                    json.assertThat().extractingPath("$.totalItems").isEqualTo(2);
                    json.assertThat().extractingPath("$.items[0].id").isEqualTo(last.getId().intValue());
                    json.assertThat().extractingPath("$.items[1].id").isEqualTo(first.getId().intValue());
                });
        // Solo l'inizio, o solo la fine.
        assertThat(mvc.get().uri("/api/transactions?from=2026-10-31")).hasStatusOk().bodyJson()
                .extractingPath("$.totalItems").isEqualTo(2);
        assertThat(mvc.get().uri("/api/transactions?to=2026-09-30")).hasStatusOk().bodyJson()
                .extractingPath("$.items[0].id").isEqualTo(september.getId().intValue());
        // Senza categoria, anche insieme al periodo.
        assertThat(mvc.get().uri("/api/transactions?uncategorized=true")).hasStatusOk().bodyJson()
                .extractingPath("$.totalItems").isEqualTo(3);
        assertThat(mvc.get().uri("/api/transactions?uncategorized=true&from=2026-10-01&to=2026-10-31"))
                .hasStatusOk().bodyJson().satisfies(json -> {
                    json.assertThat().extractingPath("$.totalItems").isEqualTo(1);
                    json.assertThat().extractingPath("$.items[0].id").isEqualTo(first.getId().intValue());
                });
    }

    @Test
    void transactionsCanBeSearchedByText() {
        Account account = account();
        BankTransaction netflix = transaction(account, LocalDate.of(2026, 10, 3), "-12.99", "PAGAMENTO NETFLIX.COM");
        BankTransaction refund = transaction(account, LocalDate.of(2026, 10, 5), "12.99", "Rimborso Zalando");
        BankTransaction pizza = transaction(account, LocalDate.of(2026, 10, 7), "-24.00", "PIZZERIA DA GINO");
        BankTransaction percent = transaction(account, LocalDate.of(2026, 10, 9), "-5.00", "Sconto 100% su_misura");
        BankTransaction fee = transaction(account, LocalDate.of(2026, 10, 11), "-2.00", "Canone Mensile conto");
        classificationService.classifyAll(); // il canone prende la categoria "Costi bancari" (regola di partenza)

        // Nella causale, senza badare a maiuscole e minuscole.
        assertThat(ids("netflix")).containsExactly(netflix.getId().intValue());
        // Un pezzo di parola.
        assertThat(ids("izzer")).containsExactly(pizza.getId().intValue());
        // Nel nome della categoria.
        assertThat(ids("costi banc")).containsExactly(fee.getId().intValue());
        // Un importo, all'italiana o all'inglese, con o senza segno: la spesa e il rimborso.
        assertThat(ids("12,99")).containsExactly(refund.getId().intValue(), netflix.getId().intValue());
        assertThat(ids("12.99")).hasSize(2);
        assertThat(ids("-12,99 €")).hasSize(2);
        // % e _ sono caratteri come gli altri: "100%" non vuol dire "100 e qualsiasi cosa".
        assertThat(ids("100%")).containsExactly(percent.getId().intValue());
        assertThat(ids("su_m")).containsExactly(percent.getId().intValue());
        // Da soli: senza la protezione "%" e "_" troverebbero tutti e cinque i movimenti.
        assertThat(ids("%")).containsExactly(percent.getId().intValue());
        assertThat(ids("_")).containsExactly(percent.getId().intValue());
        // Niente: nessun risultato; spazi intorno ignorati; vuoto = nessun filtro.
        assertThat(ids("spotify")).isEmpty();
        assertThat(ids("  netflix  ")).containsExactly(netflix.getId().intValue());
        assertThat(mvc.get().uri("/api/transactions?text=")).hasStatusOk().bodyJson()
                .extractingPath("$.totalItems").isEqualTo(5);
        // Insieme agli altri filtri, e il conteggio per le pagine è quello dei risultati.
        assertThat(mvc.get().uri("/api/transactions?text=12,99&from=2026-10-04")).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.totalItems").isEqualTo(1);
                    json.assertThat().extractingPath("$.items[0].id").isEqualTo(refund.getId().intValue());
                });
        // Troppo lungo: 400.
        assertThat(mvc.get().uri("/api/transactions?text=" + "x".repeat(101))).hasStatus(400);
    }

    @Test
    void severalWordsMustAllBeThereInAnyOrderAndAnyField() {
        Account account = account();
        BankTransaction amsterdam = transaction(account, LocalDate.of(2026, 10, 3), "-12.99",
                "PAGAMENTO CARTA NETFLIX.COM AMSTERDAM");
        BankTransaction milano = transaction(account, LocalDate.of(2026, 10, 4), "-12.99", "NETFLIX MILANO");
        BankTransaction fee = transaction(account, LocalDate.of(2026, 10, 11), "-2.00", "Canone Mensile conto");
        BankTransaction pizza = transaction(account, LocalDate.of(2026, 10, 12), "-24.00", "PIZZERIA AMSTERDAM");
        classificationService.classifyAll(); // il canone prende la categoria "Costi bancari"

        // Tutte le parole, in qualsiasi ordine.
        assertThat(ids("netflix amsterdam")).containsExactly(amsterdam.getId().intValue());
        assertThat(ids("amsterdam netflix")).containsExactly(amsterdam.getId().intValue());
        assertThat(ids("AMSTERDAM")).containsExactly(pizza.getId().intValue(), amsterdam.getId().intValue());
        // Una parola nella causale, l'altra nella categoria.
        assertThat(ids("canone bancari")).containsExactly(fee.getId().intValue());
        // Una parola e un importo: "netflix" e 12,99 (anche con "€" staccato).
        assertThat(ids("netflix 12,99 €")).containsExactly(milano.getId().intValue(), amsterdam.getId().intValue());
        assertThat(ids("pizzeria 12,99")).isEmpty();
        // Spazi in più tra le parole non contano.
        assertThat(ids("  netflix    milano ")).containsExactly(milano.getId().intValue());
        // Troppe parole: 400.
        assertThat(mvc.get().uri("/api/transactions").queryParam("text", "a b c d e f g h i j k")).hasStatus(400);
    }

    @Test
    void theSearchTextIsSplitIntoWords() {
        assertThat(TransactionSearch.words("  Netflix   AMSTERDAM ")).containsExactly("netflix", "amsterdam");
        assertThat(TransactionSearch.words("-12,99 €")).containsExactly("-12,99");
        assertThat(TransactionSearch.words("12,99€")).containsExactly("12,99");
        assertThat(TransactionSearch.words("   ")).isEmpty();
        assertThat(TransactionSearch.words(null)).isEmpty();
    }

    @Test
    void amountsAreRecognizedInTheSearch() {
        assertThat(TransactionSearch.amountIn("12,99")).isEqualByComparingTo("12.99");
        assertThat(TransactionSearch.amountIn("12.99")).isEqualByComparingTo("12.99");
        assertThat(TransactionSearch.amountIn("1.234,56")).isEqualByComparingTo("1234.56");
        assertThat(TransactionSearch.amountIn("1.234")).isEqualByComparingTo("1234");
        assertThat(TransactionSearch.amountIn("-45,50 €")).isEqualByComparingTo("45.50");
        assertThat(TransactionSearch.amountIn("2026")).isEqualByComparingTo("2026");
        assertThat(TransactionSearch.amountIn("netflix")).isNull();
        assertThat(TransactionSearch.amountIn("12,999")).isNull();
        assertThat(TransactionSearch.amountIn("")).isNull();
    }

    @Test
    void aPeriodThatEndsBeforeItStartsIs400() {
        assertThat(mvc.get().uri("/api/transactions?from=2026-10-31&to=2026-10-01"))
                .hasStatus(400)
                .bodyJson().extractingPath("$.detail").isEqualTo("La data di inizio è dopo quella di fine.");
        assertThat(mvc.get().uri("/api/transactions?from=31/10/2026")).hasStatus(400); // non è una data ISO
    }

    @Test
    void wrongRequestsAreRejected() {
        assertThat(mvc.get().uri("/api/transactions?type=BOH")).hasStatus(400);
        assertThat(mvc.put().uri("/api/transactions/999999/manual-type")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\": \"EXPENSE\"}"))
                .hasStatus(404)
                .bodyJson().extractingPath("$.detail").isEqualTo("Movimento 999999 non trovato");
        BankTransaction purchase = transaction(account(), LocalDate.of(2026, 9, 1), "-30.00", "NEGOZIO");
        assertThat(correctCategory(purchase, 999_999L)) // una categoria che non esiste
                .hasStatus(400)
                .bodyJson().extractingPath("$.detail")
                .isEqualTo("La categoria 999999 non esiste: forse è stata eliminata. Ricarica la pagina.");
    }

    @Test
    void transactionsCanBeFilteredByCategory() {
        BankTransaction pizza = transaction(account(), LocalDate.of(2026, 9, 1), "-20.00", "PIZZERIA");
        BankTransaction shoes = transaction(account(), LocalDate.of(2026, 9, 2), "-50.00", "SCARPE");
        pizza.setManualCategory(category("Bar e ristoranti"));
        shoes.setManualCategory(category("Shopping"));
        transactionRepository.saveAll(List.of(pizza, shoes));
        classificationService.classifyAll();

        assertThat(mvc.get().uri("/api/transactions?categoryId={id}", category("Shopping").getId()))
                .hasStatusOk()
                .bodyJson().extractingPath("$.items[*].id").asArray()
                .containsExactly(shoes.getId().intValue());
    }

    @Test
    void theNameIsTheCounterpartyOrTheNameWrittenInTheDescription() {
        Account account = account();
        transaction(account, LocalDate.of(2026, 9, 1), "12.00", "Bonifico N. 0000000001IT BIC Ordinante ALFAITMMXXX "
                + "Codifica Ordinante IT00X0000000000000000000001 Anagrafica Ordinante ACME SRL Note: Rimborso");
        transaction(account, LocalDate.of(2026, 9, 2), "-5.00", "BAR");

        assertThat(mvc.get().uri("/api/transactions"))
                .hasStatusOk()
                .bodyJson().extractingPath("$.items[*].name").asArray()
                .containsExactly("BAR", "ACME SRL");
        // La ricerca guarda la causale intera: il nome c'è.
        assertThat(mvc.get().uri("/api/transactions?text=acme"))
                .hasStatusOk()
                .bodyJson().extractingPath("$.items[*].name").asArray()
                .containsExactly("ACME SRL");
    }

    @Test
    void aNoteCanBeWrittenFoundAndRemoved() {
        BankTransaction purchase = transaction(account(), LocalDate.of(2026, 9, 1), "-9.98", "AMAZON");
        BankTransaction other = transaction(account(), LocalDate.of(2026, 9, 2), "-5.00", "BAR");
        classificationService.classifyAll();

        // Gli spazi in più, anche gli a capo, diventano uno spazio solo.
        assertThat(note(purchase, "\"  regalo per\\n  Luca  \""))
                .hasStatusOk()
                .bodyJson().extractingPath("$.note").isEqualTo("regalo per Luca");

        // La classificazione (che riclassifica tutto) non la tocca, e la ricerca la trova.
        assertThat(correct(purchase, "\"IGNORED\"")).hasStatusOk()
                .bodyJson().extractingPath("$.note").isEqualTo("regalo per Luca");
        assertThat(ids("luca")).containsExactly(purchase.getId().intValue());
        assertThat(ids("amazon regalo")).containsExactly(purchase.getId().intValue());
        assertThat(mvc.get().uri("/api/transactions"))
                .bodyJson().extractingPath("$.items[?(@.id == " + other.getId() + ")].note").asArray()
                .containsExactly((Object) null);

        // Vuota o null: la nota viene tolta.
        assertThat(note(purchase, "\"   \"")).hasStatusOk().bodyJson().extractingPath("$.note").isNull();
        assertThat(note(purchase, "\"di nuovo\"")).hasStatusOk();
        assertThat(note(purchase, "null")).hasStatusOk().bodyJson().extractingPath("$.note").isNull();
        assertThat(ids("luca")).isEmpty();
    }

    @Test
    void aWrongNoteIsRejected() {
        BankTransaction purchase = transaction(account(), LocalDate.of(2026, 9, 1), "-9.98", "AMAZON");

        assertThat(note(purchase, "\"" + "a".repeat(TransactionService.MAX_NOTE_LENGTH) + "\"")).hasStatusOk();
        assertThat(note(purchase, "\"" + "a".repeat(TransactionService.MAX_NOTE_LENGTH + 1) + "\""))
                .hasStatus(400)
                .bodyJson().extractingPath("$.detail").isEqualTo("La nota è troppo lunga: al massimo 300 caratteri.");
        assertThat(note(purchase, "\"suono\\u0007\""))
                .hasStatus(400)
                .bodyJson().extractingPath("$.detail").isEqualTo("La nota contiene caratteri non validi.");
        assertThat(mvc.put().uri("/api/transactions/999999/note")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\": \"x\"}"))
                .hasStatus(404);

        // Le note rifiutate non hanno cambiato quella salvata.
        assertThat(transactionRepository.findById(purchase.getId()).orElseThrow().getNote())
                .isEqualTo("a".repeat(TransactionService.MAX_NOTE_LENGTH));
    }

    /** Manda la nota; {@code note} è già in formato JSON (es. {@code "\"ciao\""} oppure {@code "null"}). */
    private MvcTestResult note(BankTransaction transaction, String note) {
        return mvc.put().uri("/api/transactions/{id}/note", transaction.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\": " + note + "}")
                .exchange();
    }

    /** Manda la categoria scelta a mano: il suo id, oppure null per togliere la scelta. */
    private MvcTestResult correctCategory(BankTransaction transaction, Long categoryId) {
        return mvc.put().uri("/api/transactions/{id}/manual-category", transaction.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryId\": " + categoryId + "}")
                .exchange();
    }

    /** Manda la correzione; {@code type} è già in formato JSON (es. {@code "\"IGNORED\""} oppure {@code "null"}). */
    private MvcTestResult correct(BankTransaction transaction, String type) {
        return mvc.put().uri("/api/transactions/{id}/manual-type", transaction.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\": " + type + "}")
                .exchange();
    }

    private Account account() {
        counter++;
        BankConnection connection = connectionRepository.save(new BankConnection("Banca", "IT", "session-" + counter,
                Instant.now().plus(90, ChronoUnit.DAYS)));
        return accountRepository.save(new Account(connection, "key-" + counter, "uid-" + counter, null,
                "Conto " + counter, "EUR"));
    }

    private BankTransaction transaction(Account account, LocalDate date, String amount, String description) {
        return transactionRepository.save(new BankTransaction(account, "tx-" + (++counter), TransactionStatus.BOOKED,
                date, null, new BigDecimal(amount), "EUR", null, description, "{}"));
    }

    /** Gli id dei movimenti trovati cercando questo testo, dal più recente. */
    private List<Integer> ids(String text) {
        try {
            String body = mvc.get().uri("/api/transactions").queryParam("text", text).exchange().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            return JsonPath.read(body, "$.items[*].id");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // UTF-8 c'è sempre: non succede
        }
    }

    /** Una categoria dell'elenco di partenza, per nome. */
    private Category category(String name) {
        return categoryRepository.findByNameIgnoreCase(name).orElseThrow();
    }
}
