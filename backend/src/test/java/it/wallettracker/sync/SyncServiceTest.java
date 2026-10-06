package it.wallettracker.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.account.AccountRole;
import it.wallettracker.bank.enablebanking.EnableBankingApi.RawTransaction;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Transaction;
import it.wallettracker.bank.enablebanking.EnableBankingClient;
import it.wallettracker.connection.BankConnection;
import it.wallettracker.connection.BankConnectionRepository;
import it.wallettracker.sync.SyncService.Round;
import it.wallettracker.transaction.BankTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test della sincronizzazione automatica, con un database vero e Enable Banking finto (come in
 * TransactionImportServiceTest). Il client finto, se non gli diciamo nulla, restituisce una lista vuota.
 */
@SpringBootTest
@Import(IntegrationTestConfiguration.class)
class SyncServiceTest {

    private static final JsonMapper JSON = new JsonMapper();

    @MockitoBean
    EnableBankingClient client;

    @Autowired
    SyncService syncService;

    @Autowired
    SyncProperties properties;

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
    void backgroundSyncReadsWithoutPsuHeadersAndRecordsTheOutcome() {
        Account account = account(validConnection(), "uid-a");
        when(client.getTransactions(eq("uid-a"), any(), any(), any())).thenReturn(List.of(
                raw("{\"transaction_amount\":{\"amount\":\"12.50\",\"currency\":\"EUR\"},"
                        + "\"credit_debit_indicator\":\"DBIT\",\"status\":\"BOOK\",\"booking_date\":\"2026-10-01\","
                        + "\"remittance_information\":[\"SUPERMERCATO\"]}")));

        Round round = syncService.syncAllInBackground();

        assertThat(round).isEqualTo(new Round(1, 0, 0));
        // In background gli header PSU NON vengono mandati (null): l'utente non è presente.
        verify(client).getTransactions(eq("uid-a"), any(), any(), isNull());
        Account reloaded = reload(account);
        assertThat(reloaded.getLastSyncAt()).isNotNull();
        assertThat(reloaded.getLastSyncError()).isNull();
        // Dopo la sincronizzazione i movimenti sono anche classificati.
        assertThat(transactionRepository.findAll()).singleElement()
                .satisfies(transaction -> assertThat(transaction.getType()).isNotNull());
    }

    @Test
    void aFailingAccountDoesNotStopTheOthers() {
        BankConnection connection = validConnection();
        Account failing = account(connection, "uid-a");
        Account working = account(connection, "uid-b");
        when(client.getTransactions(eq("uid-a"), any(), any(), any())).thenThrow(tooManyRequests());

        Round round = syncService.syncAllInBackground();

        assertThat(round).isEqualTo(new Round(1, 1, 0));
        assertThat(reload(failing).getLastSyncError()).startsWith("HTTP 429");
        assertThat(reload(working).getLastSyncError()).isNull();
        assertThat(reload(working).getLastSyncAt()).isNotNull();
    }

    @Test
    void afterAFailureTheAccountWaitsForTheNextInterval() {
        account(validConnection(), "uid-a");
        when(client.getTransactions(eq("uid-a"), any(), any(), any())).thenThrow(tooManyRequests());

        syncService.syncAllInBackground();
        Round second = syncService.syncAllInBackground();

        // Riprovare subito dopo un 429 servirebbe solo a prenderne un altro: il secondo giro non chiama la banca.
        assertThat(second).isEqualTo(new Round(0, 0, 1));
        verify(client, times(1)).getTransactions(eq("uid-a"), any(), any(), any());
    }

    @Test
    void onlyAccountsNotUpdatedForAWholeIntervalAreRead() {
        BankConnection connection = validConnection();
        Account recent = account(connection, "uid-a");
        Account old = account(connection, "uid-b");
        Duration interval = properties.interval();
        accountRepository.recordSync(recent.getId(), Instant.now().minus(interval).plus(1, ChronoUnit.HOURS), null);
        accountRepository.recordSync(old.getId(), Instant.now().minus(interval).minus(1, ChronoUnit.HOURS), null);

        Round round = syncService.syncAllInBackground();

        assertThat(round).isEqualTo(new Round(1, 0, 1));
        verify(client, never()).getTransactions(eq("uid-a"), any(), any(), any());
        verify(client).getTransactions(eq("uid-b"), any(), any(), any());
    }

    @Test
    void excludedAccountsAndExpiredConsentsAreNotRead() {
        Account excluded = account(validConnection(), "uid-a");
        excluded.changeRole(AccountRole.EXCLUDED);
        accountRepository.save(excluded);
        BankConnection expired = connectionRepository.save(new BankConnection("Banca Scaduta", "IT", "session-old",
                Instant.now().minus(1, ChronoUnit.DAYS)));
        Account withExpiredConsent = account(expired, "uid-b");

        Round round = syncService.syncAllInBackground();

        assertThat(round).isEqualTo(new Round(0, 1, 0));
        verify(client, never()).getTransactions(any(), any(), any(), any());
        assertThat(reload(excluded).getLastSyncAt()).isNull();
        assertThat(reload(withExpiredConsent).getLastSyncError())
                .startsWith("consenso scaduto")
                .endsWith("ricollega Banca Scaduta");
    }

    private BankConnection validConnection() {
        counter++;
        return connectionRepository.save(new BankConnection("Banca " + counter, "IT", "session-" + counter,
                Instant.now().plus(90, ChronoUnit.DAYS)));
    }

    private Account account(BankConnection connection, String uid) {
        return accountRepository.save(new Account(connection, "key-" + uid, uid, null, "Conto " + uid, "EUR"));
    }

    private Account reload(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow();
    }

    private static RawTransaction raw(String json) {
        return new RawTransaction(JSON.readValue(json, Transaction.class), json);
    }

    /** L'errore che Enable Banking restituisce quando si superano le letture consentite dalla banca. */
    private static HttpClientErrorException tooManyRequests() {
        return HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", HttpHeaders.EMPTY,
                "{\"error\":\"ASPSP_RATE_LIMIT_EXCEEDED\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
