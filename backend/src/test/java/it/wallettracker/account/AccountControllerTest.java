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
