package it.wallettracker.csvimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.category.CategoryRepository;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Il completamento di un conto collegato con il file di Trade Republic, dalla pagina Importa (docs/31 e
 * docs/importa-file.md, parti I e J): i movimenti arrivano "da Enable Banking" senza testo, come succede davvero, e il
 * file li completa.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class TradeRepublicCompletionTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    BankTransactionRepository transactionRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    BankConnectionRepository connectionRepository;

    @Autowired
    ClassificationService classificationService;

    @Autowired
    CategoryRepository categoryRepository;

    Account tradeRepublic;

    @BeforeEach
    void linkedAccountWithBareMovements() {
        BankConnection connection = connectionRepository.save(new BankConnection("Trade Republic", "IT", "session-1",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        tradeRepublic = accountRepository.save(new Account(connection, "key-tr", "uid-tr", null, "Conto", "EUR"));
        // Come arrivano da Enable Banking: data e importo, niente controparte né causale.
        bare("2026-07-01", "7.40");      // interessi
        bare("2026-07-07", "-150.00");   // bonifico
        bare("2026-07-09", "-34.20");    // bollo
        bare("2026-07-20", "148.00");    // dividendo
        bare("2026-07-20", "-148.00");   // dividendo annullato
        bare("2026-07-15", "-9.99");     // un movimento che nel file non c'è
        classificationService.classifyAll();
    }

    @AfterEach
    void cleanDatabase() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    @Test
    void thePreviewShowsWhatWillChangeWithoutChangingIt() {
        assertThat(preview(TradeRepublicCsvTest.CSV)).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.tradeRepublic").isEqualTo(true);
                    json.assertThat().extractingPath("$.totalRows").isEqualTo(6);
                    json.assertThat().extractingPath("$.skippedRows").isEqualTo(1);
                    json.assertThat().extractingPath("$.completeRows").isEqualTo(5);
                    json.assertThat().extractingPath("$.newRows").isEqualTo(0);
                    json.assertThat().extractingPath("$.parsedRows[1].counterparty").isEqualTo("Mario Rossi");
                    // Le anomalie, una per una: il movimento di -9,99 € della banca non è nel file.
                    json.assertThat().extractingPath("$.movementsWithoutRow.length()").isEqualTo(1);
                    json.assertThat().extractingPath("$.movementsWithoutRow[0].amount").isEqualTo(-9.99);
                    json.assertThat().extractingPath("$.rowsWithoutMovement.length()").isEqualTo(0);
                });
        assertThat(transactionRepository.findAll()).allSatisfy(t -> assertThat(t.getDescription()).isNull());
    }

    @Test
    void theMovementsGetTheirTextAndAreClassifiedAgain() {
        assertThat(importFile(TradeRepublicCsvTest.CSV)).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.completed").isEqualTo(5);
                    json.assertThat().extractingPath("$.imported").isEqualTo(0);
                    json.assertThat().extractingPath("$.duplicates").isEqualTo(0);
                });

        BankTransaction interest = find("2026-07-01", "7.40");
        assertThat(interest.getDescription()).startsWith("Interest payment");
        // Con il testo, le regole di partenza di V25 lavorano: prima era "Da verificare".
        assertThat(interest.getType()).isEqualTo(TransactionType.INVESTMENT_INCOME);
        assertThat(interest.getCategory().getId()).isEqualTo(categoryId("Interessi"));
        assertThat(find("2026-07-09", "-34.20").getCategory().getId()).isEqualTo(categoryId("Imposta di bollo"));
        assertThat(find("2026-07-20", "148.00").getCounterparty()).isEqualTo("Alfa SpA");
        assertThat(find("2026-07-20", "-148.00").getDescription()).startsWith("Cancellation");
        assertThat(find("2026-07-15", "-9.99").getType()).isEqualTo(TransactionType.TO_REVIEW);

        // Lo stesso file una seconda volta: non cambia niente.
        assertThat(importFile(TradeRepublicCsvTest.CSV)).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.completed").isEqualTo(0);
                    json.assertThat().extractingPath("$.duplicates").isEqualTo(5);
                });
    }

    @Test
    void rowsOlderThanTheBankHistoryBecomeMovements() {
        // La banca ha dato i movimenti dal 1° luglio; il file parte da maggio.
        assertThat(preview(TradeRepublicCsvTest.LONG_CSV)).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.bankFrom").isEqualTo("2026-07-01");
                    json.assertThat().extractingPath("$.completeRows").isEqualTo(5);
                    json.assertThat().extractingPath("$.newRows").isEqualTo(4);
                    json.assertThat().extractingPath("$.parsedRows[0].amount").isEqualTo(-1003.0);
                    // Il 29 giugno è a due giorni dal 1° luglio, ma nessun movimento della banca ha quei 5,00 €: è un
                    // movimento che la banca non ha dato, e si aggiunge (docs/31, parte M).
                    json.assertThat().extractingPath("$.parsedRows[3].date").isEqualTo("2026-06-29");
                    json.assertThat().extractingPath("$.rowsWithoutMovement.length()").isEqualTo(0);
                });

        assertThat(importFile(TradeRepublicCsvTest.LONG_CSV)).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.completed").isEqualTo(5);
                    json.assertThat().extractingPath("$.imported").isEqualTo(4);
                });

        BankTransaction buy = find("2026-05-04", "-1003.00");
        assertThat(buy.getDedupKey()).isEqualTo("trcsv:id-10");            // l'identificativo di Trade Republic
        assertThat(buy.getRawJson()).contains("\"symbol\"", "IT0000000002"); // la riga del file
        assertThat(buy.getType()).isEqualTo(TransactionType.SECURITIES_BUY); // regola di V26
        BankTransaction distribution = find("2026-05-20", "74.00");
        assertThat(distribution.getCategory().getId()).isEqualTo(categoryId("Dividendi"));
        assertThat(find("2026-06-01", "500.00").getCounterparty()).isNull();

        // Ricaricato: niente di nuovo, niente di doppio.
        assertThat(importFile(TradeRepublicCsvTest.LONG_CSV)).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.completed").isEqualTo(0);
                    json.assertThat().extractingPath("$.imported").isEqualTo(0);
                    json.assertThat().extractingPath("$.duplicates").isEqualTo(9);
                });
        assertThat(transactionRepository.count()).isEqualTo(6 + 4);
    }

    @Test
    void aRowJustBeforeTheBankWithTheSameAmountIsTheSameMovementWithAShiftedDate() {
        // La banca ha un movimento di 5,00 € il 1° luglio (il suo primo giorno); nel file c'è il 29 giugno. È la
        // stessa operazione con la data spostata: l'abbinamento la completa, e non nasce un doppione.
        bare("2026-07-01", "5.00");

        assertThat(importFile(TradeRepublicCsvTest.LONG_CSV)).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.completed").isEqualTo(6);
                    json.assertThat().extractingPath("$.imported").isEqualTo(3); // solo maggio e il 1° giugno
                });
        BankTransaction interest = find("2026-07-01", "5.00");
        assertThat(interest.getDescription()).isEqualTo("Interest payment for payout collection bbbb-2222");
        assertThat(transactionRepository.count()).isEqualTo(7 + 3);
    }

    @Test
    void withoutMovementsFromTheBankItWaitsForThem() {
        // Senza movimenti della banca non sappiamo dove inizia il suo storico: aggiungere potrebbe creare doppioni.
        BankConnection connection = connectionRepository.save(new BankConnection("Trade Republic", "IT", "session-2",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account empty = accountRepository.save(new Account(connection, "key-2", "uid-2", null, "Conto", "EUR"));

        assertThat(mvc.post().uri("/api/imports/preview").multipart()
                .file(csvPart(TradeRepublicCsvTest.LONG_CSV)).param("accountId", empty.getId().toString()))
                .hasStatus(400).bodyJson().extractingPath("$.detail").asString().contains("aggiornalo prima");
    }

    @Test
    void whatTheBankSentIsNeverOverwritten() {
        BankTransaction withText = transactionRepository.save(new BankTransaction(tradeRepublic, "ref-x",
                TransactionStatus.BOOKED, LocalDate.parse("2026-07-07"), null, new BigDecimal("-150.00"), "EUR",
                "Dalla banca", "Causale della banca", "{}"));
        transactionRepository.delete(find("2026-07-07", "-150.00", withText.getId()));

        importFile(TradeRepublicCsvTest.CSV);

        BankTransaction after = transactionRepository.findById(withText.getId()).orElseThrow();
        assertThat(after.getCounterparty()).isEqualTo("Dalla banca");
        assertThat(after.getDescription()).isEqualTo("Causale della banca");
    }

    @Test
    void aFileWithUnreadableRowsChangesNothing() {
        String broken = TradeRepublicCsvTest.CSV.replace("\"10.000000\"", "\"dieci\"");
        assertThat(importFile(broken)).hasStatus(400).bodyJson()
                .extractingPath("$.errors[0]").isEqualTo("Riga 2: amount \"dieci\" non è un numero.");
        assertThat(transactionRepository.findAll()).allSatisfy(t -> assertThat(t.getDescription()).isNull());
    }

    @Test
    void theOldAddressOfTheCompletionIsGone() {
        // La pagina "Completa i movimenti" è stata unita a Importa (docs/importa-file.md, parte J): le sue API non
        // ci sono più.
        assertThat(mvc.post().uri("/api/imports/completion").multipart().file(csvPart(TradeRepublicCsvTest.CSV))
                .param("accountId", tradeRepublic.getId().toString())).hasStatus4xxClientError();
    }

    // --- Strumenti ---

    private void bare(String date, String amount) {
        transactionRepository.save(new BankTransaction(tradeRepublic, "fp:" + date + amount + Math.random(),
                TransactionStatus.BOOKED, LocalDate.parse(date), null, new BigDecimal(amount), "EUR", null, null, "{}"));
    }

    private BankTransaction find(String date, String amount, Long... except) {
        return transactionRepository.findAll().stream()
                .filter(t -> t.getBookingDate().equals(LocalDate.parse(date))
                        && t.getAmount().compareTo(new BigDecimal(amount)) == 0
                        && (except.length == 0 || !t.getId().equals(except[0])))
                .findFirst().orElseThrow();
    }

    private Long categoryId(String name) {
        return categoryRepository.findByNameIgnoreCase(name).orElseThrow().getId();
    }

    private MvcTestResult preview(String csv) {
        return mvc.post().uri("/api/imports/preview").multipart().file(csvPart(csv))
                .param("accountId", tradeRepublic.getId().toString()).exchange();
    }

    /** L'importazione, con una mappatura qualsiasi come la manda il browser: con il file di Trade Republic non conta. */
    private MvcTestResult importFile(String csv) {
        MockMultipartFile mapping = new MockMultipartFile("mapping", "", MediaType.APPLICATION_JSON_VALUE, """
                {"delimiter": ",", "dateColumn": 1, "dateFormat": "yyyy-MM-dd", "amountMode": "SINGLE",
                 "amountColumn": 10, "decimalSeparator": "DOT", "descriptionColumn": 17}
                """.getBytes(StandardCharsets.UTF_8));
        return mvc.post().uri("/api/imports").multipart().file(csvPart(csv)).file(mapping)
                .param("accountId", tradeRepublic.getId().toString()).exchange();
    }

    private static MockMultipartFile csvPart(String csv) {
        return new MockMultipartFile("file", "transactions.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }
}
