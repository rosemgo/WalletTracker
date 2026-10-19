package it.wallettracker.csvimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.account.AccountRole;
import it.wallettracker.connection.BankConnection;
import it.wallettracker.connection.BankConnectionRepository;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.TransactionType;
import org.junit.jupiter.api.AfterEach;
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
 * Test dell'importazione CSV (vedi AccountControllerTest per MockMvcTester).
 *
 * <p>Le richieste sono "multipart", come le manda il browser: un pezzo {@code file} con il CSV e un pezzo
 * {@code mapping} con il JSON della mappatura. {@link MockMultipartFile} costruisce ciascun pezzo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class CsvImportControllerTest {

    /** Un estratto conto all'italiana: punto e virgola, date gg/mm/aaaa, virgola per i decimali. */
    private static final String ITALIAN_CSV = """
            Data;Descrizione;Importo
            03/10/2026;NETFLIX.COM;-12,99
            05/10/2026;Stipendio ottobre;2.100,00
            07/10/2026;CONAD ROMA;-45,50
            """;

    /** La mappatura di ITALIAN_CSV, come la conferma l'utente. */
    private static final String ITALIAN_MAPPING = """
            {"delimiter": ";", "dateColumn": 0, "dateFormat": "dd/MM/yyyy", "amountMode": "SINGLE",
             "amountColumn": 2, "decimalSeparator": "COMMA", "descriptionColumn": 1}
            """;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    BankTransactionRepository transactionRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    BankConnectionRepository connectionRepository;

    @AfterEach
    void cleanDatabase() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    @Test
    void thePreviewProposesTheMappingAndShowsTheRowsRead() {
        Account account = manualAccount();

        assertThat(preview(account, ITALIAN_CSV, null))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.headers").asArray().containsExactly("Data", "Descrizione", "Importo");
                    json.assertThat().extractingPath("$.totalRows").isEqualTo(3);
                    json.assertThat().extractingPath("$.mapping.delimiter").isEqualTo(";");
                    json.assertThat().extractingPath("$.mapping.dateColumn").isEqualTo(0);
                    json.assertThat().extractingPath("$.mapping.amountColumn").isEqualTo(2);
                    json.assertThat().extractingPath("$.mapping.decimalSeparator").isEqualTo("COMMA");
                    json.assertThat().extractingPath("$.parsedRows[1].date").isEqualTo("2026-10-05");
                    json.assertThat().extractingPath("$.parsedRows[1].amount").isEqualTo(2100.00);
                    json.assertThat().extractingPath("$.parsedRows[1].currency").isEqualTo("EUR"); // quella del conto
                    json.assertThat().extractingPath("$.errorCount").isEqualTo(0);
                });
        // L'anteprima non salva niente.
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    void anIncompleteMappingIsNotAnErrorOfThePreview() {
        // L'utente non ha ancora scelto la colonna dell'importo: l'anteprima mostra il file e che cosa manca.
        String mapping = """
                {"delimiter": ";", "dateColumn": 0, "dateFormat": "dd/MM/yyyy", "amountMode": "SINGLE",
                 "decimalSeparator": "COMMA"}
                """;

        assertThat(preview(manualAccount(), ITALIAN_CSV, mapping))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.headers").asArray().hasSize(3);
                    json.assertThat().extractingPath("$.errors[0]").isEqualTo("Indica la colonna dell'importo.");
                    json.assertThat().extractingPath("$.parsedRows").asArray().isEmpty();
                });
    }

    @Test
    void theImportSavesAndClassifiesAndAReimportSkipsTheDuplicates() {
        Account account = manualAccount();

        assertThat(importCsv(account, ITALIAN_CSV, ITALIAN_MAPPING))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.imported").isEqualTo(3);
                    json.assertThat().extractingPath("$.duplicates").isEqualTo(0);
                });
        List<BankTransaction> saved = transactionRepository.findAll();
        assertThat(saved).hasSize(3);
        // Classificati come quelli di Enable Banking: lo stipendio è un'entrata, NETFLIX una spesa.
        assertThat(saved).allSatisfy(t -> assertThat(t.getType()).isNotNull());
        BankTransaction salary = saved.stream().filter(t -> t.getAmount().signum() > 0).findFirst().orElseThrow();
        assertThat(salary.getType()).isEqualTo(TransactionType.INCOME);
        assertThat(salary.getDescription()).isEqualTo("Stipendio ottobre");
        // La riga originale resta, come JSON con i nomi delle colonne.
        assertThat(salary.getRawJson()).contains("\"Importo\"").contains("2.100,00");

        // Lo stesso file di nuovo: niente doppioni.
        assertThat(importCsv(account, ITALIAN_CSV, ITALIAN_MAPPING))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.imported").isEqualTo(0);
                    json.assertThat().extractingPath("$.duplicates").isEqualTo(3);
                });
        // Un file del mese che si sovrappone al precedente: solo la riga nuova.
        String overlapping = ITALIAN_CSV + "10/10/2026;SPOTIFY;-10,99\n";
        assertThat(importCsv(account, overlapping, ITALIAN_MAPPING))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.imported").isEqualTo(1);
                    json.assertThat().extractingPath("$.duplicates").isEqualTo(3);
                });
        assertThat(transactionRepository.count()).isEqualTo(4);
    }

    @Test
    void twoIdenticalRowsInTheSameFileAreTwoMovements() {
        // Due caffè uguali nello stesso giorno: sono due spese vere, non un doppione.
        String csv = """
                Data;Descrizione;Importo
                03/10/2026;BAR CENTRALE;-1,20
                03/10/2026;BAR CENTRALE;-1,20
                """;
        Account account = manualAccount();

        assertThat(importCsv(account, csv, ITALIAN_MAPPING))
                .hasStatusOk().bodyJson().extractingPath("$.imported").isEqualTo(2);
        // Reimportandolo, tutti e due sono già presenti.
        assertThat(importCsv(account, csv, ITALIAN_MAPPING))
                .hasStatusOk().bodyJson().extractingPath("$.duplicates").isEqualTo(2);
    }

    @Test
    void aFileWithUnreadableRowsIsRejectedWithTheErrorsAndNothingIsSaved() {
        String csv = """
                Data;Descrizione;Importo
                03/10/2026;NETFLIX.COM;-12,99
                31/02/2026;DATA IMPOSSIBILE;-1,00
                05/10/2026;IMPORTO SBAGLIATO;dodici
                """;

        assertThat(importCsv(manualAccount(), csv, ITALIAN_MAPPING))
                .hasStatus(400)
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.detail").asString().startsWith("2 righe non si possono leggere");
                    json.assertThat().extractingPath("$.errors[0]").asString().startsWith("Riga 3: la data \"31/02/2026\"");
                    json.assertThat().extractingPath("$.errors[1]").asString().startsWith("Riga 4: l'importo \"dodici\"");
                });
        // Tutto o niente: neanche la riga giusta è stata salvata.
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    void aBankAccountCannotReceiveAFile() {
        // I conti collegati si aggiornano da Enable Banking: un file creerebbe doppioni.
        BankConnection connection = connectionRepository.save(new BankConnection("ING", "IT", "session-1",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account bank = accountRepository.save(new Account(connection, "key-1", "uid-1", null, "Conto", "EUR"));

        assertThat(importCsv(bank, ITALIAN_CSV, ITALIAN_MAPPING))
                .hasStatus(400)
                .bodyJson().extractingPath("$.detail").asString().startsWith("Puoi importare un file solo in un conto manuale");
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    void anInvalidMappingOrFileIs400() {
        Account account = manualAccount();
        // Un separatore che non esiste.
        assertThat(importCsv(account, ITALIAN_CSV, ITALIAN_MAPPING.replace("\";\"", "\"x\"")))
                .hasStatus(400);
        // Una colonna oltre la fine del file.
        assertThat(importCsv(account, ITALIAN_CSV, ITALIAN_MAPPING.replace("\"amountColumn\": 2", "\"amountColumn\": 7")))
                .hasStatus(400)
                .bodyJson().extractingPath("$.detail").isEqualTo("Il file ha 3 colonne: la colonna 8 non esiste.");
        // Solo l'intestazione.
        assertThat(importCsv(account, "Data;Descrizione;Importo\n", ITALIAN_MAPPING))
                .hasStatus(400)
                .bodyJson().extractingPath("$.detail")
                .isEqualTo("Il file deve avere una riga di intestazione e almeno un movimento.");
        // Una virgoletta mai chiusa.
        assertThat(importCsv(account, "Data;Descrizione;Importo\n03/10/2026;\"NETFLIX;-1,00\n", ITALIAN_MAPPING))
                .hasStatus(400);
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    void anUnknownAccountIs404() {
        assertThat(mvc.post().uri("/api/imports/preview").multipart()
                .file(csvPart(ITALIAN_CSV))
                .param("accountId", "999999"))
                .hasStatus(404);
    }

    // --- Strumenti ---

    private Account manualAccount() {
        return accountRepository.save(Account.manual("Trade Republic", "Conto Trade Republic", null, "EUR",
                AccountRole.UNASSIGNED));
    }

    private MvcTestResult preview(Account account, String csv, String mapping) {
        var request = mvc.post().uri("/api/imports/preview").multipart()
                .file(csvPart(csv))
                .param("accountId", account.getId().toString());
        if (mapping != null) {
            request = request.file(mappingPart(mapping));
        }
        return request.exchange();
    }

    private MvcTestResult importCsv(Account account, String csv, String mapping) {
        return mvc.post().uri("/api/imports").multipart()
                .file(csvPart(csv))
                .file(mappingPart(mapping))
                .param("accountId", account.getId().toString())
                .exchange();
    }

    private static MockMultipartFile csvPart(String csv) {
        return new MockMultipartFile("file", "estratto.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    /** La mappatura come la manda il browser: un pezzo JSON (un Blob di tipo application/json). */
    private static MockMultipartFile mappingPart(String json) {
        return new MockMultipartFile("mapping", "", MediaType.APPLICATION_JSON_VALUE,
                json.getBytes(StandardCharsets.UTF_8));
    }
}
