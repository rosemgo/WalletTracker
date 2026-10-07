package it.wallettracker.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
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
    void wrongRequestsAreRejected() {
        assertThat(mvc.get().uri("/api/transactions?type=BOH")).hasStatus(400);
        assertThat(mvc.put().uri("/api/transactions/999999/manual-type")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\": \"EXPENSE\"}"))
                .hasStatus(404)
                .bodyJson().extractingPath("$.detail").isEqualTo("Movimento 999999 non trovato");
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
}
