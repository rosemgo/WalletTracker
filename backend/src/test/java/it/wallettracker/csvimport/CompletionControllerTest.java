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
import it.wallettracker.account.AccountRole;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Il completamento di un conto collegato con il file di Trade Republic (docs/31), dal punto di vista delle API: i
 * movimenti arrivano "da Enable Banking" senza testo, come succede davvero, e il file li completa.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class CompletionControllerTest {

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
        assertThat(post("/api/imports/completion/preview", TradeRepublicCsvTest.CSV)).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.fileRows").isEqualTo(5);
                    json.assertThat().extractingPath("$.skippedRows").isEqualTo(1);
                    json.assertThat().extractingPath("$.toComplete.length()").isEqualTo(5);
                    json.assertThat().extractingPath("$.toComplete[1].counterparty").isEqualTo("Mario Rossi");
                    json.assertThat().extractingPath("$.movementsWithoutRow.length()").isEqualTo(1);
                    json.assertThat().extractingPath("$.movementsWithoutRow[0].amount").isEqualTo(-9.99);
                    json.assertThat().extractingPath("$.rowsWithoutMovement.length()").isEqualTo(0);
                });
        assertThat(transactionRepository.findAll()).allSatisfy(t -> assertThat(t.getDescription()).isNull());
    }

    @Test
    void theMovementsGetTheirTextAndAreClassifiedAgain() {
        assertThat(post("/api/imports/completion", TradeRepublicCsvTest.CSV)).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.completed").isEqualTo(5);
                    json.assertThat().extractingPath("$.alreadyComplete").isEqualTo(0);
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
        assertThat(post("/api/imports/completion", TradeRepublicCsvTest.CSV)).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.completed").isEqualTo(0);
                    json.assertThat().extractingPath("$.alreadyComplete").isEqualTo(5);
                });
    }

    @Test
    void rowsOlderThanTheBankHistoryBecomeMovements() {
        // La banca ha dato i movimenti dal 1° luglio; il file parte da maggio.
        assertThat(post("/api/imports/completion/preview", TradeRepublicCsvTest.LONG_CSV)).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.bankFrom").isEqualTo("2026-07-01");
                    json.assertThat().extractingPath("$.toComplete.length()").isEqualTo(5);
                    json.assertThat().extractingPath("$.toAdd.length()").isEqualTo(3);
                    json.assertThat().extractingPath("$.toAdd[0].amount").isEqualTo(-1003.0);
                    // Il 29 giugno è a due giorni dal 1° luglio: potrebbe essere un movimento della banca con la data
                    // spostata, quindi non si aggiunge. Si mostra.
                    json.assertThat().extractingPath("$.rowsWithoutMovement.length()").isEqualTo(1);
                    json.assertThat().extractingPath("$.rowsWithoutMovement[0].date").isEqualTo("2026-06-29");
                });

        assertThat(post("/api/imports/completion", TradeRepublicCsvTest.LONG_CSV)).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.completed").isEqualTo(5);
                    json.assertThat().extractingPath("$.added").isEqualTo(3);
                });

        BankTransaction buy = find("2026-05-04", "-1003.00");
        assertThat(buy.getDedupKey()).isEqualTo("trcsv:id-10");            // l'identificativo di Trade Republic
        assertThat(buy.getRawJson()).contains("\"symbol\"", "IT0000000002"); // la riga del file
        assertThat(buy.getType()).isEqualTo(TransactionType.SECURITIES_BUY); // regola di V26
        BankTransaction distribution = find("2026-05-20", "74.00");
        assertThat(distribution.getCategory().getId()).isEqualTo(categoryId("Dividendi"));
        assertThat(find("2026-06-01", "500.00").getCounterparty()).isNull();

        // Ricaricato: niente di nuovo, niente di doppio.
        assertThat(post("/api/imports/completion", TradeRepublicCsvTest.LONG_CSV)).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.completed").isEqualTo(0);
                    json.assertThat().extractingPath("$.added").isEqualTo(0);
                    json.assertThat().extractingPath("$.alreadyComplete").isEqualTo(8);
                });
        assertThat(transactionRepository.count()).isEqualTo(6 + 3);
    }

    @Test
    void withoutMovementsFromTheBankNothingIsAdded() {
        // Senza movimenti della banca non sappiamo dove inizia il suo storico: aggiungere potrebbe creare doppioni.
        BankConnection connection = connectionRepository.save(new BankConnection("Trade Republic", "IT", "session-2",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account empty = accountRepository.save(new Account(connection, "key-2", "uid-2", null, "Conto", "EUR"));

        assertThat(mvc.post().uri("/api/imports/completion/preview").multipart()
                .file(csvPart(TradeRepublicCsvTest.LONG_CSV)).param("accountId", empty.getId().toString()))
                .hasStatusOk().bodyJson().satisfies(json -> {
                    json.assertThat().extractingPath("$.bankFrom").isNull();
                    json.assertThat().extractingPath("$.toAdd.length()").isEqualTo(0);
                    json.assertThat().extractingPath("$.rowsWithoutMovement.length()").isEqualTo(9);
                });
    }

    @Test
    void whatTheBankSentIsNeverOverwritten() {
        BankTransaction withText = transactionRepository.save(new BankTransaction(tradeRepublic, "ref-x",
                TransactionStatus.BOOKED, LocalDate.parse("2026-07-07"), null, new BigDecimal("-150.00"), "EUR",
                "Dalla banca", "Causale della banca", "{}"));
        transactionRepository.delete(find("2026-07-07", "-150.00", withText.getId()));

        post("/api/imports/completion", TradeRepublicCsvTest.CSV);

        BankTransaction after = transactionRepository.findById(withText.getId()).orElseThrow();
        assertThat(after.getCounterparty()).isEqualTo("Dalla banca");
        assertThat(after.getDescription()).isEqualTo("Causale della banca");
    }

    @Test
    void wrongFilesAndAccountsAre400() {
        assertThat(post("/api/imports/completion/preview", "Data;Descrizione;Importo\n03/10/2026;NETFLIX;-12,99\n"))
                .hasStatus(400).bodyJson().extractingPath("$.detail").asString()
                .startsWith("Il file non è un'esportazione delle transazioni di Trade Republic");

        Account manual = accountRepository.save(Account.manual("Banca", "Conto", null, "EUR", AccountRole.MAIN));
        assertThat(mvc.post().uri("/api/imports/completion").multipart().file(csvPart(TradeRepublicCsvTest.CSV))
                .param("accountId", manual.getId().toString()))
                .hasStatus(400).bodyJson().extractingPath("$.detail").asString().startsWith("Questo è un conto manuale");

        String broken = TradeRepublicCsvTest.CSV.replace("\"10.000000\"", "\"dieci\"");
        assertThat(post("/api/imports/completion", broken)).hasStatus(400).bodyJson()
                .extractingPath("$.errors[0]").isEqualTo("Riga 2: amount \"dieci\" non è un numero.");
        assertThat(transactionRepository.findAll()).allSatisfy(t -> assertThat(t.getDescription()).isNull());
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

    private MvcTestResult post(String uri, String csv) {
        return mvc.post().uri(uri).multipart().file(csvPart(csv))
                .param("accountId", tradeRepublic.getId().toString()).exchange();
    }

    private static MockMultipartFile csvPart(String csv) {
        return new MockMultipartFile("file", "transactions.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }
}
