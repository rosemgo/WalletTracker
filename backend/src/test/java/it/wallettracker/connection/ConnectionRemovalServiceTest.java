package it.wallettracker.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.alert.AlertService;
import it.wallettracker.bank.enablebanking.EnableBankingApi;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Access;
import it.wallettracker.bank.enablebanking.EnableBankingApi.AccountId;
import it.wallettracker.bank.enablebanking.EnableBankingApi.AspspRef;
import it.wallettracker.bank.enablebanking.EnableBankingApi.RawTransaction;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Session;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Transaction;
import it.wallettracker.bank.enablebanking.EnableBankingClient;
import it.wallettracker.sync.SyncService;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.TransactionImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Rimuovi collegamento" (docs/rimuovere-un-collegamento.md): la storia resta, e ricollegando la banca non si
 * raddoppia niente. Enable Banking è finto; il database è vero. Dati inventati.
 */
@SpringBootTest
@Import(IntegrationTestConfiguration.class)
class ConnectionRemovalServiceTest {

    private static final JsonMapper JSON = new JsonMapper();
    private static final Instant NOW = Instant.now();
    private static final Instant IN_90_DAYS = NOW.plus(90, ChronoUnit.DAYS);

    @MockitoBean
    EnableBankingClient client;

    @Autowired
    ConnectionRemovalService removalService;

    @Autowired
    ConnectionService connectionService;

    @Autowired
    TransactionImportService importService;

    @Autowired
    SyncService syncService;

    @Autowired
    AlertService alertService;

    @Autowired
    BankConnectionRepository connectionRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    BankTransactionRepository transactionRepository;

    @AfterEach
    void cleanDatabase() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    @Test
    void removingAConnectionKeepsTheAccountsAndTheirMovements() {
        BankConnection connection = connectionService.saveSession(session("session-1", "uid-1"), IN_90_DAYS);
        Account account = connectionService.accountsOf(connection).getFirst();
        bankReturns("uid-1", json("t1", "2026-10-01", "12.50", "DBIT", "SUPERMERCATO ALFA"),
                json("t2", "2026-10-02", "1500.00", "CRDT", "STIPENDIO ACME SRL"));
        importService.importAccount(account, null);

        ConnectionRemovalService.Removal removal = removalService.remove(connection.getId(), NOW);

        assertThat(removal).isEqualTo(new ConnectionRemovalService.Removal("Banca Alfa", 1, true));
        verify(client).deleteSession("session-1"); // il consenso è chiuso anche su Enable Banking
        // La storia resta: il conto e i suoi due movimenti.
        assertThat(accountRepository.count()).isEqualTo(1);
        assertThat(transactionRepository.count()).isEqualTo(2);
        BankConnection removed = connectionRepository.findById(connection.getId()).orElseThrow();
        assertThat(removed.isRemoved()).isTrue();
        assertThat(removed.isValid()).isFalse();
        // Non è più tra i collegamenti validi, non chiede di rinnovare il consenso, e non si legge più.
        assertThat(connectionService.validConnections()).isEmpty();
        assertThat(alertService.alerts()).noneMatch(a -> "Banca Alfa".equals(a.bank()));
        assertThat(syncService.syncAllInBackground().accounts()).isEmpty();
        // Nell'elenco della pagina Conti c'è, segnato come rimosso, con il suo conto.
        assertThat(removalService.connections()).singleElement().satisfies(c -> {
            assertThat(c.removedAt()).isNotNull();
            assertThat(c.accounts()).isEqualTo(1);
        });
    }

    @Test
    void relinkingARemovedBankBringsBackTheSameAccountsWithoutDuplicates() {
        BankConnection connection = connectionService.saveSession(session("session-1", "uid-1"), IN_90_DAYS);
        Account account = connectionService.accountsOf(connection).getFirst();
        bankReturns("uid-1", json("t1", "2026-10-01", "12.50", "DBIT", "SUPERMERCATO ALFA"),
                json("t2", "2026-10-02", "1500.00", "CRDT", "STIPENDIO ACME SRL"));
        importService.importAccount(account, null);
        removalService.remove(connection.getId(), NOW);

        // Mesi dopo: la stessa banca, ricollegata. Enable Banking dà una sessione nuova e un uid nuovo.
        BankConnection again = connectionService.saveSession(session("session-2", "uid-9"), IN_90_DAYS);

        // Lo stesso conto (riconosciuto da banca + IBAN + valuta), spostato sul collegamento nuovo.
        assertThat(accountRepository.count()).isEqualTo(1);
        Account same = connectionService.accountsOf(again).getFirst();
        assertThat(same.getId()).isEqualTo(account.getId());
        assertThat(same.getProviderUid()).isEqualTo("uid-9");
        // Il collegamento rimosso, rimasto senza conti, non c'è più; quello nuovo è valido.
        assertThat(connectionRepository.findAll()).extracting(BankConnection::getSessionId)
                .containsExactly("session-2");
        assertThat(again.isValid()).isTrue();

        // La banca ridà i movimenti già salvati più uno nuovo: solo il nuovo si aggiunge.
        bankReturns("uid-9", json("t1", "2026-10-01", "12.50", "DBIT", "SUPERMERCATO ALFA"),
                json("t2", "2026-10-02", "1500.00", "CRDT", "STIPENDIO ACME SRL"),
                json("t3", "2026-11-05", "30.00", "DBIT", "FARMACIA BETA"));
        TransactionImportService.ImportResult result = importService.importAccount(same, null);

        assertThat(result.inserted()).isEqualTo(1);
        assertThat(transactionRepository.count()).isEqualTo(3);
    }

    @Test
    void aConnectionWithoutAccountsIsDeleted() {
        // Un tentativo in modalità ristretta salvato da una versione vecchia: nessun conto, nessuna storia da tenere.
        BankConnection empty = connectionRepository.save(new BankConnection("Banca Beta", "IT", "session-3",
                IN_90_DAYS));

        ConnectionRemovalService.Removal removal = removalService.remove(empty.getId(), NOW);

        assertThat(removal).isEqualTo(new ConnectionRemovalService.Removal("Banca Beta", 0, true));
        verify(client).deleteSession("session-3");
        assertThat(connectionRepository.count()).isZero();
    }

    @Test
    void ifEnableBankingDoesNotAnswerTheConnectionIsRemovedAndThePageIsTold() {
        BankConnection connection = connectionService.saveSession(session("session-1", "uid-1"), IN_90_DAYS);
        doThrow(new ResourceAccessException("timeout")).when(client).deleteSession("session-1");

        ConnectionRemovalService.Removal removal = removalService.remove(connection.getId(), NOW);

        assertThat(removal.revoked()).isFalse(); // la pagina dirà di chiudere il consenso dal pannello
        assertThat(connectionRepository.findById(connection.getId()).orElseThrow().isRemoved()).isTrue();
    }

    @Test
    void aSessionThatEnableBankingNoLongerKnowsCountsAsClosed() {
        // Per esempio già cancellata dal pannello di Enable Banking.
        BankConnection connection = connectionService.saveSession(session("session-1", "uid-1"), IN_90_DAYS);
        doThrow(HttpClientErrorException.create(HttpStatusCode.valueOf(404), "Not Found", HttpHeaders.EMPTY,
                "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).when(client).deleteSession("session-1");

        assertThat(removalService.remove(connection.getId(), NOW).revoked()).isTrue();
    }

    @Test
    void anExpiredConsentIsNotSentToEnableBanking() {
        BankConnection expired = connectionRepository.save(new BankConnection("Banca Alfa", "IT", "session-old",
                NOW.minus(1, ChronoUnit.DAYS)));

        assertThat(removalService.remove(expired.getId(), NOW).revoked()).isTrue();
        verify(client, never()).deleteSession(any());
    }

    /** Una sessione finta di "Banca Alfa" con un conto corrente. */
    private static Session session(String sessionId, String accountUid) {
        return new Session(sessionId,
                List.of(new EnableBankingApi.Account(accountUid, new AccountId("IT00X0000000000000000000001"),
                        "Conto corrente", "EUR", null)),
                new AspspRef("Banca Alfa", "IT"), new Access(IN_90_DAYS));
    }

    private void bankReturns(String uid, String... jsons) {
        List<RawTransaction> transactions = java.util.Arrays.stream(jsons)
                .map(json -> new RawTransaction(JSON.readValue(json, Transaction.class), json))
                .toList();
        when(client.getTransactions(eq(uid), any(), any(), any())).thenReturn(transactions);
    }

    /** Un movimento contabilizzato, in formato JSON come lo manda Enable Banking. */
    private static String json(String entryReference, String date, String amount, String indicator,
            String remittance) {
        return "{\"entry_reference\":\"" + entryReference + "\","
                + "\"transaction_amount\":{\"amount\":\"" + amount + "\",\"currency\":\"EUR\"},"
                + "\"credit_debit_indicator\":\"" + indicator + "\",\"status\":\"BOOK\","
                + "\"booking_date\":\"" + date + "\",\"remittance_information\":[\"" + remittance + "\"]}";
    }
}
