package it.wallettracker.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.bank.enablebanking.EnableBankingApi.RawTransaction;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Transaction;
import it.wallettracker.bank.enablebanking.EnableBankingClient;
import it.wallettracker.connection.BankConnection;
import it.wallettracker.connection.BankConnectionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test dell'importazione con un database vero (Testcontainers).
 *
 * <p>Enable Banking invece è <b>finto</b>: {@code @MockitoBean} sostituisce il vero
 * {@link EnableBankingClient} con un oggetto Mockito, a cui diciamo noi cosa restituire
 * ({@code when(...).thenReturn(...)}). Così ogni test sceglie esattamente i movimenti "ricevuti".
 */
@SpringBootTest
@Import(IntegrationTestConfiguration.class)
class TransactionImportServiceTest {

    private static final JsonMapper JSON = new JsonMapper();

    @MockitoBean
    EnableBankingClient client;

    @Autowired
    TransactionImportService importService;

    @Autowired
    BankTransactionRepository transactionRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    BankConnectionRepository connectionRepository;

    private Account account;

    @BeforeEach
    void createAccount() {
        BankConnection connection = connectionRepository.save(
                new BankConnection("ING", "IT", "session-1", Instant.now().plus(90, ChronoUnit.DAYS)));
        account = accountRepository.save(new Account(connection, "key-1", "uid-1", "IT60X001", "Conto", "EUR"));
    }

    @AfterEach
    void cleanDatabase() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    @Test
    void savesTransactionsWithSignedAmountAndOriginalJson() {
        bankReturns(json("t1", "BOOK", "2026-09-10", "12.50", "DBIT", "ESSELUNGA"),
                json("t2", "BOOK", "2026-09-27", "1500.00", "CRDT", "STIPENDIO"));

        var result = importService.importAccount(account, null);

        assertThat(result.inserted()).isEqualTo(2);
        List<BankTransaction> saved = transactionRepository.findAll();
        assertThat(saved).extracting(BankTransaction::getAmount)
                .containsExactlyInAnyOrder(new BigDecimal("-12.50"), new BigDecimal("1500.00"));
        assertThat(saved).allSatisfy(t -> assertThat(t.getRawJson()).contains("entry_reference"));
    }

    @Test
    void importingTheSameDataTwiceCreatesNoDuplicates() {
        bankReturns(json("t1", "BOOK", "2026-09-10", "12.50", "DBIT", "ESSELUNGA"));

        importService.importAccount(account, null);
        var second = importService.importAccount(account, null);

        assertThat(second.inserted()).isZero();
        assertThat(second.alreadyPresent()).isEqualTo(1);
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    void repeatedPagesAreSavedOnce() {
        // Come Trade Republic: lo stesso movimento (stesso JSON, senza entry_reference) arriva due volte.
        String interest = json(null, "BOOK", "2026-10-01", "7.770000", "CRDT", null);
        bankReturns(interest, interest);

        var result = importService.importAccount(account, null);

        assertThat(result.repeatedCopies()).isEqualTo(1);
        assertThat(transactionRepository.count()).isEqualTo(1);
        // 6 decimali riportati a 2.
        assertThat(transactionRepository.findAll().getFirst().getAmount()).isEqualByComparingTo("7.77");
    }

    @Test
    void identicalLookingButDistinctTransactionsAreBothSaved() {
        // Due caffè uguali lo stesso giorno: i JSON differiscono (es. un id interno), quindi non sono copie.
        String coffee1 = json(null, "BOOK", "2026-09-10", "1.20", "DBIT", "BAR").replace("}", ",\"x\":1}");
        String coffee2 = json(null, "BOOK", "2026-09-10", "1.20", "DBIT", "BAR").replace("}", ",\"x\":2}");
        bankReturns(coffee1, coffee2);

        importService.importAccount(account, null);
        importService.importAccount(account, null); // e reimportandoli non si duplicano

        assertThat(transactionRepository.count()).isEqualTo(2);
    }

    @Test
    void pendingTransactionsAreReplacedWhenTheyGetBooked() {
        bankReturns(json(null, "PDNG", "2026-10-04", "30.00", "DBIT", "AMAZON"));
        var first = importService.importAccount(account, null);
        assertThat(first.pending()).isEqualTo(1);

        // Il giorno dopo lo stesso acquisto è contabilizzato.
        bankReturns(json(null, "BOOK", "2026-10-05", "30.00", "DBIT", "AMAZON"));
        importService.importAccount(account, null);

        assertThat(transactionRepository.findAll())
                .singleElement()
                .extracting(BankTransaction::getStatus)
                .isEqualTo(TransactionStatus.BOOKED);
    }

    @Test
    void nextImportStartsFromTheLastSavedTransactionMinusTheOverlap() {
        bankReturns(json("t1", "BOOK", "2026-09-10", "12.50", "DBIT", "ESSELUNGA"));
        importService.importAccount(account, null);

        var second = importService.importAccount(account, null);

        assertThat(second.from()).isEqualTo(LocalDate.of(2026, 9, 10).minusDays(TransactionImportService.OVERLAP_DAYS));
    }

    @Test
    void retriesWithNinetyDaysWhenTheBankRejectsThePeriod() {
        // Prima chiamata: la banca rifiuta un anno di storico. Seconda: risponde normalmente.
        var wrongPeriod = HttpClientErrorException.create(HttpStatusCode.valueOf(422), "Unprocessable",
                HttpHeaders.EMPTY,
                "{\"error\":\"WRONG_TRANSACTIONS_PERIOD\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        RawTransaction transaction = raw(json("t1", "BOOK", "2026-09-10", "12.50", "DBIT", "ESSELUNGA"));
        when(client.getTransactions(eq("uid-1"), any(), any(), any()))
                .thenThrow(wrongPeriod)
                .thenReturn(List.of(transaction));

        var result = importService.importAccount(account, null);

        assertThat(result.from())
                .isEqualTo(LocalDate.now().minusDays(TransactionImportService.DAYS_WITHOUT_RECENT_SCA));
        assertThat(result.fromReason()).startsWith("la banca ha rifiutato il periodo");
        assertThat(result.inserted()).isEqualTo(1);
    }

    @Test
    void splitsThePeriodWhenTheBankTruncatesTheResponseAt100() {
        // Banca finta come la carta ING: 150 movimenti negli ultimi 75 giorni (2 al giorno),
        // ma ogni risposta contiene al massimo i 100 più recenti del periodo richiesto.
        LocalDate today = LocalDate.now();
        List<RawTransaction> all = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            LocalDate date = today.minusDays(i / 2);
            all.add(raw(json("t" + i, "BOOK", date.toString(), "1.00", "DBIT", "SPESA " + i)));
        }
        when(client.getTransactions(eq("uid-1"), any(), any(), any())).thenAnswer(invocation -> {
            LocalDate from = invocation.getArgument(1);
            LocalDate to = invocation.getArgument(2);
            return all.stream()
                    .filter(t -> !t.transaction().bookingDate().isBefore(from)
                            && !t.transaction().bookingDate().isAfter(to))
                    .limit(TransactionImportService.SUSPECTED_RESPONSE_LIMIT)
                    .toList();
        });

        var result = importService.importAccount(account, null);

        assertThat(result.requests()).isGreaterThan(1);
        assertThat(result.inserted()).isEqualTo(150);
        assertThat(transactionRepository.count()).isEqualTo(150);
    }

    @Test
    void doesNotSplitWhenTheBankIgnoresTheDates() {
        // Come Trade Republic: restituisce sempre gli stessi 100 movimenti, anche fuori dal periodo richiesto.
        List<RawTransaction> sameAnswer = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            sameAnswer.add(raw(json("t" + i, "BOOK", "2020-01-01", "1.00", "DBIT", "VECCHIO " + i)));
        }
        when(client.getTransactions(eq("uid-1"), any(), any(), any())).thenReturn(sameAnswer);

        var result = importService.importAccount(account, null);

        assertThat(result.requests()).isEqualTo(1);
        assertThat(result.inserted()).isEqualTo(100);
    }

    @Test
    void explainsWhyTheStartDateWasChosen() {
        bankReturns(json("t1", "BOOK", "2026-09-10", "12.50", "DBIT", "ESSELUNGA"));

        var first = importService.importAccount(account, null);
        var second = importService.importAccount(account, null);

        assertThat(first.fromReason()).startsWith("prima importazione");
        assertThat(second.fromReason()).startsWith("ultimo movimento salvato (2026-09-10)");
    }

    /** Dice al client finto di restituire questi movimenti, qualunque siano conto e date richiesti. */
    private void bankReturns(String... jsons) {
        List<RawTransaction> transactions = Arrays.stream(jsons)
                .map(TransactionImportServiceTest::raw)
                .toList();
        when(client.getTransactions(eq("uid-1"), any(), any(), any())).thenReturn(transactions);
    }

    private static RawTransaction raw(String json) {
        return new RawTransaction(JSON.readValue(json, Transaction.class), json);
    }

    /** Un movimento in formato JSON, come lo manda Enable Banking. */
    private static String json(String entryReference, String status, String date, String amount, String indicator,
            String remittance) {
        String reference = entryReference != null ? "\"entry_reference\":\"" + entryReference + "\"," : "";
        String info = remittance != null ? ",\"remittance_information\":[\"" + remittance + "\"]" : "";
        return "{" + reference
                + "\"transaction_amount\":{\"amount\":\"" + amount + "\",\"currency\":\"EUR\"},"
                + "\"credit_debit_indicator\":\"" + indicator + "\","
                + "\"status\":\"" + status + "\","
                + "\"booking_date\":\"" + date + "\""
                + info + "}";
    }
}
