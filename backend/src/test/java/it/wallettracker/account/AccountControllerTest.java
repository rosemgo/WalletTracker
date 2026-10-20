package it.wallettracker.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import it.wallettracker.IntegrationTestConfiguration;
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

/**
 * Test delle API dei conti.
 *
 * <p>{@code @AutoConfigureMockMvc} ci dà un {@link MockMvcTester}: simula richieste HTTP e controlla le
 * risposte (stato, JSON), passando per tutto Spring MVC (controller, conversione JSON, errori) ma senza
 * aprire una porta di rete vera. Il database invece è vero (Testcontainers).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class AccountControllerTest {

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

    private int counter;

    @AfterEach
    void cleanDatabase() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    @Test
    void listsTheAccountsWithBankRoleAndSyncStatus() {
        Account account = account("ING");
        accountRepository.recordSync(account.getId(), Instant.now(), "HTTP 429 troppe letture");

        assertThat(mvc.get().uri("/api/accounts"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$[0].bank").isEqualTo("ING");
                    json.assertThat().extractingPath("$[0].role").isEqualTo("UNASSIGNED");
                    json.assertThat().extractingPath("$[0].lastSyncError").isEqualTo("HTTP 429 troppe letture");
                    // I dati interni del backend non escono dalle API.
                    json.assertThat().doesNotHavePath("$[0].providerUid");
                    json.assertThat().doesNotHavePath("$[0].externalKey");
                });
    }

    @Test
    void changingTheRoleReclassifiesTheTransactions() {
        // Un trasferimento tra due conti normali: INTERNAL_TRANSFER.
        Account main = account("Banca A");
        Account broker = account("Banca B");
        BankTransaction out = transaction(main, "-500.00");
        transaction(broker, "500.00");
        classificationService.classifyAll();
        assertThat(typeOf(out)).isEqualTo(TransactionType.INTERNAL_TRANSFER);

        // Il secondo conto diventa di investimento: lo stesso trasferimento ora è un versamento.
        assertThat(mvc.put().uri("/api/accounts/{id}/role", broker.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"INVESTMENT\"}"))
                .hasStatusOk()
                .bodyJson().extractingPath("$.role").isEqualTo("INVESTMENT");

        assertThat(typeOf(out)).isEqualTo(TransactionType.INVESTMENT_DEPOSIT);
    }

    @Test
    void theLabelUsesTheIbanInsteadOfTheOwnerNameAndAChosenNameWins() {
        // Molte banche chiamano il conto con il nome del titolare: uguale per tutti i suoi conti.
        BankConnection connection = connectionRepository.save(new BankConnection("ING", "IT", "session-label",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account withIban = accountRepository.save(new Account(connection, "key-l1", "uid-l1",
                "IT60X0542811101000000123456", "MARIO ROSSI", "EUR"));
        Account card = accountRepository.save(new Account(connection, "key-l2", "uid-l2", null, "MARIO ROSSI", "EUR"));
        Account manual = accountRepository.save(Account.manual("Trade Republic", "Conto titoli", null, "EUR",
                AccountRole.UNASSIGNED));

        assertThat(mvc.get().uri("/api/accounts")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$[?(@.id == %d)].label".formatted(withIban.getId()))
                    .asArray().containsExactly("ING ··3456");
            json.assertThat().extractingPath("$[?(@.id == %d)].label".formatted(card.getId()))
                    .asArray().containsExactly("ING · MARIO ROSSI"); // senza IBAN non c'è di meglio
            json.assertThat().extractingPath("$[?(@.id == %d)].label".formatted(manual.getId()))
                    .asArray().containsExactly("Trade Republic · Conto titoli");
        });

        // Un nome scelto vince; vuoto lo toglie.
        assertThat(rename(card, "{\"displayName\": \"  Carta di credito \"}")).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.label").isEqualTo("Carta di credito");
                    json.assertThat().extractingPath("$.displayName").isEqualTo("Carta di credito");
                    json.assertThat().extractingPath("$.name").isEqualTo("MARIO ROSSI"); // quello della banca resta
                });
        assertThat(rename(card, "{\"displayName\": \"\"}")).hasStatusOk().bodyJson()
                .extractingPath("$.label").isEqualTo("ING · MARIO ROSSI");
        assertThat(rename(card, "{\"displayName\": \"%s\"}".formatted("x".repeat(101)))).hasStatus(400);
        assertThat(mvc.put().uri("/api/accounts/999999/display-name").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\": \"x\"}")).hasStatus(404);
    }

    private MvcTestResult rename(Account account, String json) {
        return mvc.put().uri("/api/accounts/{id}/display-name", account.getId())
                .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    @Test
    void anUnknownAccountIs404WithTheReason() {
        assertThat(mvc.put().uri("/api/accounts/999999/role")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"MAIN\"}"))
                .hasStatus(404)
                .bodyJson().extractingPath("$.detail").isEqualTo("Conto 999999 non trovato");
    }

    @Test
    void aMissingOrUnknownRoleIs400() {
        Account account = account("ING");

        assertThat(mvc.put().uri("/api/accounts/{id}/role", account.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .hasStatus(400);
        assertThat(mvc.put().uri("/api/accounts/{id}/role", account.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"BOH\"}"))
                .hasStatus(400);
    }

    @Test
    void aManualAccountIsCreatedWithItsBankNameAndNoConsent() {
        assertThat(mvc.post().uri("/api/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"bankName": "Trade Republic", "name": "Conto Trade Republic",
                         "iban": "de12 3456 7890 1234 5678 90", "currency": "EUR", "role": "INVESTMENT"}
                        """))
                .hasStatus(201)
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.source").isEqualTo("MANUAL");
                    json.assertThat().extractingPath("$.bank").isEqualTo("Trade Republic");
                    json.assertThat().extractingPath("$.iban").isEqualTo("DE12345678901234567890"); // senza spazi
                    json.assertThat().extractingPath("$.role").isEqualTo("INVESTMENT");
                    json.assertThat().extractingPath("$.consentValidUntil").isNull(); // nessun consenso PSD2
                });

        // Nell'elenco, insieme ai conti collegati, in ordine di banca.
        account("ING");
        assertThat(mvc.get().uri("/api/accounts"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$[0].bank").isEqualTo("ING");
                    json.assertThat().extractingPath("$[0].source").isEqualTo("ENABLE_BANKING");
                    json.assertThat().extractingPath("$[1].bank").isEqualTo("Trade Republic");
                });
    }

    @Test
    void anInvalidManualAccountIs400() {
        // Banca mancante, valuta non ISO, IBAN troppo corto.
        assertThat(mvc.post().uri("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bankName\": \" \", \"name\": \"Conto\", \"currency\": \"EUR\"}"))
                .hasStatus(400);
        assertThat(mvc.post().uri("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bankName\": \"TR\", \"name\": \"Conto\", \"currency\": \"euro\"}"))
                .hasStatus(400);
        assertThat(mvc.post().uri("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bankName\": \"TR\", \"name\": \"Conto\", \"iban\": \"IT12\", \"currency\": \"EUR\"}"))
                .hasStatus(400);
        assertThat(accountRepository.count()).isZero();
    }

    @Test
    void deletingAManualAccountDeletesItsTransactionsAndUnpairsTheTransfer() {
        // Un trasferimento tra un conto collegato e un conto manuale.
        Account main = account("ING");
        Account manual = accountRepository.save(Account.manual("Trade Republic", "Conto TR", null, "EUR",
                AccountRole.UNASSIGNED));
        BankTransaction out = transaction(main, "-500.00");
        transaction(manual, "500.00");
        classificationService.classifyAll();
        assertThat(typeOf(out)).isEqualTo(TransactionType.INTERNAL_TRANSFER);

        assertThat(mvc.delete().uri("/api/accounts/{id}", manual.getId())).hasStatus(204);

        assertThat(accountRepository.findById(manual.getId())).isEmpty();
        assertThat(transactionRepository.count()).isEqualTo(1);
        // Il movimento rimasto non ha più l'altra metà: viene riclassificato.
        assertThat(typeOf(out)).isEqualTo(TransactionType.TO_REVIEW);
    }

    @Test
    void aBankAccountCannotBeDeleted() {
        // I suoi movimenti tornerebbero alla prossima sincronizzazione.
        Account account = account("ING");

        assertThat(mvc.delete().uri("/api/accounts/{id}", account.getId()))
                .hasStatus(400)
                .bodyJson().extractingPath("$.detail")
                .isEqualTo("Si possono eliminare solo i conti manuali (caricati da file CSV).");
        assertThat(accountRepository.findById(account.getId())).isPresent();
    }

    private Account account(String bank) {
        counter++;
        BankConnection connection = connectionRepository.save(new BankConnection(bank, "IT", "session-" + counter,
                Instant.now().plus(90, ChronoUnit.DAYS)));
        return accountRepository.save(new Account(connection, "key-" + counter, "uid-" + counter, null,
                "Conto " + counter, "EUR"));
    }

    /** Un movimento senza testo, come quelli di Trade Republic. */
    private BankTransaction transaction(Account account, String amount) {
        return transactionRepository.save(new BankTransaction(account, "tx-" + (++counter), TransactionStatus.BOOKED,
                LocalDate.of(2026, 9, 1), null, new BigDecimal(amount), "EUR", null, null, "{}"));
    }

    private TransactionType typeOf(BankTransaction transaction) {
        return transactionRepository.findById(transaction.getId()).orElseThrow().getType();
    }
}
