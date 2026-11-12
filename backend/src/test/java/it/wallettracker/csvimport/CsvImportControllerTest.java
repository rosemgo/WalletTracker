package it.wallettracker.csvimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.account.AccountRole;
import it.wallettracker.category.CategoryRepository;
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

    @Autowired
    CategoryRepository categoryRepository;

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
    void theTinabaFileGetsTheSignsAndTheTypesRight() {
        // Il conto corrente (collegato) e il wallet Tinaba (manuale, ruolo Investimenti). Tutto inventato.
        BankConnection connection = connectionRepository.save(new BankConnection("Banca Alfa", "IT", "s-1",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account bank = accountRepository.save(new Account(connection, "k-1", "uid-1", null, "MARIO ROSSI", "EUR"));
        transactionRepository.save(new BankTransaction(bank, "b-1", TransactionStatus.BOOKED, LocalDate.of(2026, 10, 28),
                null, new BigDecimal("-200.00"), "EUR", null, "Bonifico istantaneo da voi disposto N. ABC A favore di "
                + "MARIO ROSSI IBAN beneficiario IT00X0000000000000000000009 Note: Trasferimento a mio conto", "{}"));
        Account tinaba = accountRepository.save(Account.manual("Tinaba", "Wallet", null, "EUR",
                AccountRole.INVESTMENT));
        String csv = """
                Data,Categoria,Controparte,Descrizione,Causale/Messaggio,CRO/NOP,Importo,Community
                28/10/2026 01:32:31,Bonifici,Mario Rossi,Ricevuto da Mario Rossi,Trasferimento a mio conto,ABC,200,no
                13/10/2026 15:52:28,PAYMENT_TRANSFER,Tinaba Cashback,Trasferimento ricevuto per campagna cashback,Cashback Power,,"46,74",no
                02/10/2026 17:04:33,Robo advisor,,Nuovo versamento Robo advisor,,,200,no
                07/04/2026 14:21:03,PAYMENT_TRANSFER,Tinaba Power,Ricarica effettuata,Credito per le commissioni,,50,no
                """;

        // La proposta riconosce il file: importi senza segno, entrate le righe "ricevuto".
        assertThat(preview(tinaba, csv, null))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.mapping.amountMode").isEqualTo("UNSIGNED");
                    json.assertThat().extractingPath("$.parsedRows[*].amount").asArray()
                            .containsExactly(200.0, 46.74, -200.0, -50.0);
                });
        assertThat(importCsv(tinaba, csv, """
                {"delimiter": ",", "dateColumn": 0, "dateFormat": "dd/MM/yyyy", "amountMode": "UNSIGNED",
                 "amountColumn": 6, "decimalSeparator": "COMMA", "descriptionColumn": 3, "counterpartyColumn": 2,
                 "signColumn": 3, "incomeWords": "ricevuto, ricevuta", "detailColumn": 4}
                """)).hasStatusOk();

        Map<String, BankTransaction> byText = transactionRepository.findAll().stream()
                .collect(Collectors.toMap(t -> t.getAccount().getId().equals(bank.getId()) ? "ING"
                        : t.getDescription(), t -> t));
        // Il bonifico: le due metà si abbinano, ed è un versamento negli investimenti da tutte e due le parti.
        assertThat(byText.get("ING").getType()).isEqualTo(TransactionType.INVESTMENT_DEPOSIT);
        assertThat(byText.get("Ricevuto da Mario Rossi · Trasferimento a mio conto").getType()).isEqualTo(TransactionType.INVESTMENT_DEPOSIT);
        // Il versamento nel Robo advisor: acquisto titoli, non una spesa (regola di partenza V31).
        assertThat(byText.get("Nuovo versamento Robo advisor").getType()).isEqualTo(TransactionType.SECURITIES_BUY);
        // Il credito Power: una spesa in "Costi bancari", anche con un messaggio tra la causale e la controparte
        // (V32: "tutte le parole", non una frase attaccata). Il cashback: un bonus, quindi un'entrata (V33).
        BankTransaction power = byText.get("Ricarica effettuata · Credito per le commissioni");
        BankTransaction cashback = byText.get("Trasferimento ricevuto per campagna cashback · Cashback Power");
        assertThat(power.getType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(cashback.getType()).isEqualTo(TransactionType.INCOME);
        assertThat(cashback.getAmount()).isPositive();
        // L'id della categoria si legge senza database (il nome no: qui siamo fuori da una transazione).
        Long bankCosts = categoryRepository.findByNameIgnoreCase("Costi bancari").orElseThrow().getId();
        Long bonus = categoryRepository.findByNameIgnoreCase("Rimborsi fiscali e bonus").orElseThrow().getId();
        assertThat(power.getCategory().getId()).isEqualTo(bankCosts);
        assertThat(cashback.getCategory().getId()).isEqualTo(bonus);
    }

    @Test
    void reimportingWithTheSecondTextColumnCompletesTheDescriptionsWithoutDuplicates() {
        // Un file importato quando la seconda colonna non si leggeva (docs/tinaba.md, parte H). Dati inventati.
        Account account = manualAccount();
        String csv = """
                Data;Descrizione;Messaggio;Importo
                28/10/2026;Ricevuto da Mario Rossi;Trasferimento a mio conto;200,00
                02/10/2026;Nuovo versamento;;-200,00
                """;
        String before = """
                {"delimiter": ";", "dateColumn": 0, "dateFormat": "dd/MM/yyyy", "amountMode": "SINGLE",
                 "amountColumn": 3, "decimalSeparator": "COMMA", "descriptionColumn": 1}
                """;
        String after = before.replace("\"descriptionColumn\": 1", "\"descriptionColumn\": 1, \"detailColumn\": 2");
        assertThat(importCsv(account, csv, before)).hasStatusOk().bodyJson().extractingPath("$.imported").isEqualTo(2);

        // Reimportandolo con la seconda colonna: niente doppioni, e la causale del bonifico si completa.
        assertThat(importCsv(account, csv, after))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.imported").isEqualTo(0);
                    json.assertThat().extractingPath("$.duplicates").isEqualTo(2);
                    json.assertThat().extractingPath("$.completed").isEqualTo(1);
                });
        assertThat(transactionRepository.findAll()).extracting(BankTransaction::getDescription)
                .containsExactlyInAnyOrder("Ricevuto da Mario Rossi · Trasferimento a mio conto", "Nuovo versamento");

        // Una terza volta non cambia più niente.
        assertThat(importCsv(account, csv, after)).hasStatusOk().bodyJson().extractingPath("$.completed").isEqualTo(0);
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

    /** Un estratto conto come quello di Fineco, in CSV: note sopra la tabella, entrate e uscite. Dati inventati. */
    private static final String FINECO_CSV = """
            Conto Corrente: 0000001
            Intestazione Conto Corrente: MARIO ROSSI
            Periodo Dal: 01/01/2026 Al: 10/11/2026
            Risultati Ricerca
            Data_Operazione;Data_Valuta;Entrate;Uscite;Descrizione;Descrizione_Completa;Stato
            01/08/2026;01/08/2026;;-20,00;Bonifico;Bonifico ad Anna Bianchi;Contabilizzato
            22/07/2026;22/07/2026;;-10,00;Canone;Canone Mensile Conto Giugno 2026;Contabilizzato
            20/07/2026;20/07/2026;;-7,50;Pagamento;Pagamento POS BAR CENTRALE;Contabilizzato
            19/07/2026;20/07/2026;;-5,00;Bollo;Imposta di bollo di conto corrente;Contabilizzato
            25/03/2026;25/03/2026;60,80;;Ritenuta Fiscale;Ritenuta Fiscale ROMANIA-7OT45 6,5% Qta/Val.nom. 16000,000000;Contabilizzato
            """;

    /** La sua mappatura, come la propone l'app. */
    private static final String FINECO_MAPPING = """
            {"delimiter": ";", "dateColumn": 0, "dateFormat": "dd/MM/yyyy", "amountMode": "SPLIT",
             "incomeColumn": 2, "expenseColumn": 3, "decimalSeparator": "COMMA", "descriptionColumn": 5}
            """;

    @Test
    void aLinkedAccountReceivesOnlyTheMonthsBeforeTheBank() {
        // Un conto collegato: la banca ha dato i movimenti dal 22 luglio (lo storico di quando è stato collegato).
        Account bank = linkedAccount();
        bankMovement(bank, "bank-1", "2026-07-22", "-10.00", "Canone Mensile Conto Giugno 2026");
        bankMovement(bank, "bank-2", "2026-07-22", "-7.50", "Pagamento POS BAR CENTRALE"); // il 20 nel file
        bankMovement(bank, "bank-3", "2026-08-01", "-20.00", "Bonifico ad Anna Bianchi");

        // L'anteprima: l'intestazione trovata sotto le note, che cosa si aggiungerebbe e perché.
        assertThat(preview(bank, FINECO_CSV, null)).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.headers[0]").isEqualTo("Data_Operazione");
            json.assertThat().extractingPath("$.mapping.amountMode").isEqualTo("SPLIT");
            json.assertThat().extractingPath("$.mapping.descriptionColumn").isEqualTo(5);
            json.assertThat().extractingPath("$.linkedAccount").isEqualTo(true);
            json.assertThat().extractingPath("$.bankFrom").isEqualTo("2026-07-22");
            json.assertThat().extractingPath("$.newRows").isEqualTo(2);
            json.assertThat().extractingPath("$.coveredRows").isEqualTo(3);
            // le righe mostrate sono quelle che si aggiungono
            json.assertThat().extractingPath("$.parsedRows[0].date").isEqualTo("2026-07-19");
        });

        assertThat(importCsv(bank, FINECO_CSV, FINECO_MAPPING)).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.imported").isEqualTo(2);
            json.assertThat().extractingPath("$.covered").isEqualTo(3);
        });
        Map<String, BankTransaction> byText = transactionRepository.findAll().stream()
                .collect(Collectors.toMap(BankTransaction::getDescription, t -> t));
        assertThat(byText).hasSize(5); // 3 della banca + 2 dal file, nessun doppione
        // Il bollo del 19 luglio (prima della banca) e il giro titoli di marzo, con l'impronta dei movimenti presi da
        // un file: la sincronizzazione li riconosce, se un giorno la banca li mandasse.
        BankTransaction stamp = byText.get("Imposta di bollo di conto corrente");
        assertThat(stamp.getBookingDate()).isEqualTo(LocalDate.of(2026, 7, 19));
        assertThat(stamp.getDedupKey()).startsWith(BankTransaction.FILE_KEY_PREFIX);
        BankTransaction transfer = byText.get("Ritenuta Fiscale ROMANIA-7OT45 6,5% Qta/Val.nom. 16000,000000");
        assertThat(transfer.getAmount()).isEqualByComparingTo("60.80");
        assertThat(transfer.getType()).isEqualTo(TransactionType.INTERNAL_TRANSFER); // classificato (V39)

        // Lo stesso file un'altra volta: niente di nuovo.
        assertThat(importCsv(bank, FINECO_CSV, FINECO_MAPPING)).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.imported").isEqualTo(0);
            json.assertThat().extractingPath("$.duplicates").isEqualTo(2);
            json.assertThat().extractingPath("$.covered").isEqualTo(3);
        });
        assertThat(transactionRepository.count()).isEqualTo(5);
    }

    @Test
    void aLinkedAccountWithoutMovementsFromTheBankWaitsForThem() {
        // Senza movimenti della banca non si sa da quale giorno li porterà: si aggiorna prima il conto.
        Account bank = linkedAccount();

        assertThat(importCsv(bank, FINECO_CSV, FINECO_MAPPING))
                .hasStatus(400)
                .bodyJson().extractingPath("$.detail").asString().startsWith("Questo conto non ha ancora movimenti");
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    void anExcelFileIsReadLikeACsv() {
        // Lo stesso estratto conto, in Excel (come lo esporta Fineco), in un conto manuale: tutte le righe.
        Account account = manualAccount();
        byte[] excel = XlsxFixture.build(XlsxFixture.finecoLike(List.of(
                XlsxFixture.finecoRow(LocalDate.of(2026, 11, 5), "3.95", "Sconto", "Sconto Canone Mensile Ottobre"),
                XlsxFixture.finecoRow(LocalDate.of(2026, 11, 5), "-3.95", "Canone", "Canone Mensile Conto Ottobre"),
                XlsxFixture.finecoRow(LocalDate.of(2026, 7, 8), "-1", "Bollo", "Addebito imposta di bollo Dossier"))));
        MockMultipartFile file = new MockMultipartFile("file", "movimenti.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excel);

        assertThat(mvc.post().uri("/api/imports/preview").multipart().file(file)
                .param("accountId", account.getId().toString()))
                .hasStatusOk().bodyJson().satisfies(json -> {
                    json.assertThat().extractingPath("$.spreadsheet").isEqualTo(true);
                    json.assertThat().extractingPath("$.linkedAccount").isEqualTo(false);
                    json.assertThat().extractingPath("$.mapping.decimalSeparator").isEqualTo("DOT");
                    json.assertThat().extractingPath("$.newRows").isEqualTo(3);
                    json.assertThat().extractingPath("$.errorCount").isEqualTo(0);
                });
        String mapping = """
                {"delimiter": ";", "dateColumn": 0, "dateFormat": "dd/MM/yyyy", "amountMode": "SPLIT",
                 "incomeColumn": 2, "expenseColumn": 3, "decimalSeparator": "DOT", "descriptionColumn": 5}
                """;
        assertThat(mvc.post().uri("/api/imports").multipart().file(file).file(mappingPart(mapping))
                .param("accountId", account.getId().toString()))
                .hasStatusOk().bodyJson().extractingPath("$.imported").isEqualTo(3);
        assertThat(transactionRepository.findAll()).extracting(BankTransaction::getAmount)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactlyInAnyOrder(new BigDecimal("3.95"), new BigDecimal("-3.95"), new BigDecimal("-1"));
    }

    /**
     * Una mappatura qualsiasi, come quella che il browser rimanda: con il file di Trade Republic non conta. Letta con
     * questa, la riga dell'operazione sui titoli (importo vuoto) sarebbe un errore.
     */
    private static final String TRADE_REPUBLIC_MAPPING = """
            {"delimiter": ",", "dateColumn": 1, "dateFormat": "yyyy-MM-dd", "amountMode": "SINGLE",
             "amountColumn": 10, "decimalSeparator": "DOT", "descriptionColumn": 17}
            """;

    @Test
    void theTradeRepublicFileIsRecognizedAndReadWithNetAmounts() {
        Account account = manualAccount();

        assertThat(preview(account, TradeRepublicCsvTest.CSV, null))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.tradeRepublic").isEqualTo(true);
                    json.assertThat().extractingPath("$.errorCount").isEqualTo(0);
                    json.assertThat().extractingPath("$.skippedRows").isEqualTo(1); // l'operazione sui titoli
                    json.assertThat().extractingPath("$.newRows").isEqualTo(5);
                    json.assertThat().extractingPath("$.parsedRows[0].amount").isEqualTo(7.40); // 10,00 - 2,60
                    json.assertThat().extractingPath("$.parsedRows[2].amount").isEqualTo(-34.20); // il bollo
                    json.assertThat().extractingPath("$.parsedRows[1].counterparty").isEqualTo("Mario Rossi");
                });

        assertThat(importCsv(account, TradeRepublicCsvTest.CSV, TRADE_REPUBLIC_MAPPING))
                .hasStatusOk()
                .bodyJson().extractingPath("$.imported").isEqualTo(5);
        Map<String, BankTransaction> saved = transactionRepository.findAll().stream()
                .collect(Collectors.toMap(BankTransaction::getDedupKey, t -> t));
        assertThat(saved).containsOnlyKeys("csv:tr:id-1", "csv:tr:id-2", "csv:tr:id-3", "csv:tr:id-4", "csv:tr:id-5");
        BankTransaction dividend = saved.get("csv:tr:id-4");
        assertThat(dividend.getAmount()).isEqualByComparingTo("148.00");
        assertThat(dividend.getCounterparty()).isEqualTo("Alfa SpA");
        assertThat(dividend.getDescription()).isEqualTo("Cash Dividend for ISIN IT0000000001");
        assertThat(dividend.getRawJson()).contains("\"tax\": \"-52.00\"").contains("\"symbol\": \"IT0000000001\"");

        // Ricaricando lo stesso file non si aggiunge niente.
        assertThat(importCsv(account, TradeRepublicCsvTest.CSV, TRADE_REPUBLIC_MAPPING))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.imported").isEqualTo(0);
                    json.assertThat().extractingPath("$.duplicates").isEqualTo(5);
                });
    }

    @Test
    void theTradeRepublicFileInALinkedAccountAlsoCompletesTheMovementsOfTheBank() {
        // Come "Completa i movimenti" (docs/importa-file.md, parte I). La banca porta i movimenti dal 1° luglio, senza
        // testo: quello del 1° luglio riceve causale e controparte dal file; le righe più vecchie del 1° luglio si
        // aggiungono con l'impronta trcsv:<transaction_id>, anche quella del 29 giugno (nessun movimento della banca
        // ha quell'importo: docs/31, parte M); quelle di luglio senza un movimento no.
        Account bank = linkedAccount();
        bankMovement(bank, "bank-1", "2026-07-01", "7.40", null);

        assertThat(preview(bank, TradeRepublicCsvTest.LONG_CSV, null))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.tradeRepublic").isEqualTo(true);
                    json.assertThat().extractingPath("$.newRows").isEqualTo(4);
                    json.assertThat().extractingPath("$.completeRows").isEqualTo(1);
                    json.assertThat().extractingPath("$.coveredRows").isEqualTo(4);
                    json.assertThat().extractingPath("$.duplicateRows").isEqualTo(0);
                    json.assertThat().extractingPath("$.bankFrom").isEqualTo("2026-07-01");
                    // Le righe che cambiano qualcosa, per data: le quattro aggiunte e il movimento completato.
                    json.assertThat().extractingPath("$.parsedRows").asArray().hasSize(5);
                    json.assertThat().extractingPath("$.parsedRows[4].description")
                            .isEqualTo("Interest payment for payout collection aaaa-1111");
                });
        assertThat(transactionRepository.count()).isEqualTo(1); // l'anteprima non cambia niente

        assertThat(importCsv(bank, TradeRepublicCsvTest.LONG_CSV, TRADE_REPUBLIC_MAPPING))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.imported").isEqualTo(4);
                    json.assertThat().extractingPath("$.completed").isEqualTo(1);
                    json.assertThat().extractingPath("$.covered").isEqualTo(4);
                });
        Map<String, BankTransaction> saved = transactionRepository.findAll().stream()
                .collect(Collectors.toMap(BankTransaction::getDedupKey, t -> t));
        assertThat(saved).containsOnlyKeys("bank-1", "trcsv:id-10", "trcsv:id-11", "trcsv:id-12", "trcsv:id-13");
        assertThat(saved.get("bank-1").getDescription()).isEqualTo("Interest payment for payout collection aaaa-1111");
        assertThat(saved.get("bank-1").getAmount()).isEqualByComparingTo("7.40"); // l'importo della banca non cambia

        // Ricaricando lo stesso file: tutto già fatto.
        assertThat(importCsv(bank, TradeRepublicCsvTest.LONG_CSV, TRADE_REPUBLIC_MAPPING))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.imported").isEqualTo(0);
                    json.assertThat().extractingPath("$.completed").isEqualTo(0);
                    json.assertThat().extractingPath("$.duplicates").isEqualTo(5);
                });
    }

    @Test
    void theTradeRepublicFileInALinkedAccountWithoutMovementsFromTheBankWaitsForThem() {
        assertThat(importCsv(linkedAccount(), TradeRepublicCsvTest.CSV, TRADE_REPUBLIC_MAPPING))
                .hasStatus(400)
                .bodyJson().extractingPath("$.detail").asString().contains("aggiornalo prima");
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    void wrongCellsOfTheTradeRepublicFileAreReportedWithTheirLine() {
        String broken = TradeRepublicCsvTest.CSV.replace("\"10.000000\"", "\"dieci\"");

        assertThat(importCsv(manualAccount(), broken, TRADE_REPUBLIC_MAPPING))
                .hasStatus(400)
                .bodyJson().extractingPath("$.errors[0]").isEqualTo("Riga 2: amount \"dieci\" non è un numero.");
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    void anOldExcelFileIsRefusedWithAHint() {
        Account account = manualAccount();
        MockMultipartFile file = new MockMultipartFile("file", "movimenti.xls", "application/vnd.ms-excel",
                new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0, 0, 0, 0});

        assertThat(mvc.post().uri("/api/imports/preview").multipart().file(file)
                .param("accountId", account.getId().toString()))
                .hasStatus(400).bodyJson().extractingPath("$.detail").asString().contains("salvalo come .xlsx");
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
        // Una virgoletta mai chiusa: il messaggio della libreria (in inglese) arriva tradotto.
        assertThat(importCsv(account, "Data;Descrizione;Importo\n03/10/2026;\"NETFLIX;-1,00\n", ITALIAN_MAPPING))
                .hasStatus(400)
                .bodyJson().extractingPath("$.detail").asString().startsWith("Il file non è un CSV valido");
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

    /** Un conto collegato con Enable Banking (inventato). */
    private Account linkedAccount() {
        BankConnection connection = connectionRepository.save(new BankConnection("Banca Alfa", "IT", "session-1",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        return accountRepository.save(new Account(connection, "key-1", "uid-1", null, "Conto", "EUR"));
    }

    /** Un movimento arrivato dalla banca. */
    private void bankMovement(Account account, String key, String date, String amount, String description) {
        transactionRepository.save(new BankTransaction(account, key, TransactionStatus.BOOKED, LocalDate.parse(date),
                null, new BigDecimal(amount), "EUR", null, description, "{}"));
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
