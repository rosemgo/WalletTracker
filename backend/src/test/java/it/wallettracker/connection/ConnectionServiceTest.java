package it.wallettracker.connection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.account.AccountRole;
import it.wallettracker.bank.enablebanking.EnableBankingApi;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Access;
import it.wallettracker.bank.enablebanking.EnableBankingApi.AccountId;
import it.wallettracker.bank.enablebanking.EnableBankingApi.AspspRef;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Test con un database PostgreSQL vero (vedi IntegrationTestConfiguration). */
@SpringBootTest
@Import(IntegrationTestConfiguration.class)
class ConnectionServiceTest {

    private static final Instant IN_90_DAYS = Instant.now().plus(90, ChronoUnit.DAYS);

    @Autowired
    ConnectionService connectionService;

    @Autowired
    BankConnectionRepository connectionRepository;

    @Autowired
    AccountRepository accountRepository;

    @AfterEach
    void cleanDatabase() {
        // Prima i conti, poi i collegamenti: i conti hanno una chiave esterna verso i collegamenti.
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    @Test
    void savesConnectionAndAccounts() {
        Session session = ingSession("session-1", "uid-a", "uid-b");

        BankConnection connection = connectionService.saveSession(session, IN_90_DAYS);

        assertThat(connection.getId()).isNotNull();
        assertThat(connection.getAspspName()).isEqualTo("ING");
        List<Account> accounts = connectionService.accountsOf(connection);
        assertThat(accounts).hasSize(2);
        assertThat(accounts).allSatisfy(account -> assertThat(account.getRole()).isEqualTo(AccountRole.UNASSIGNED));
    }

    @Test
    void renewingTheConsentReusesTheSameAccounts() {
        connectionService.saveSession(ingSession("session-1", "uid-a", "uid-b"), IN_90_DAYS);

        // Nuovo consenso per la stessa banca: Enable Banking dà una nuova sessione e nuovi uid.
        BankConnection renewed = connectionService.saveSession(ingSession("session-2", "uid-c", "uid-d"), IN_90_DAYS);

        assertThat(accountRepository.count()).isEqualTo(2);
        assertThat(connectionService.accountsOf(renewed))
                .extracting(Account::getProviderUid)
                .containsExactlyInAnyOrder("uid-c", "uid-d");
        // Il vecchio collegamento è rimasto senza conti ed è stato cancellato.
        assertThat(connectionRepository.findAll())
                .extracting(BankConnection::getSessionId)
                .containsExactly("session-2");
    }

    @Test
    void acceptsVeryLongIdentifiersFromTheBank() {
        // Caso reale con ING: l'impronta del conto (identification_hash) superava i 200 caratteri.
        String longHash = "h".repeat(500);
        String longUid = "u".repeat(300);
        Session session = new Session("s".repeat(300),
                List.of(new EnableBankingApi.Account(longUid, new AccountId("IT60X001"), "n".repeat(300), "EUR", longHash)),
                new AspspRef("ING", "IT"),
                new Access(IN_90_DAYS));

        BankConnection connection = connectionService.saveSession(session, IN_90_DAYS);

        assertThat(connectionService.accountsOf(connection))
                .extracting(Account::getExternalKey)
                .containsExactly(longHash);
    }

    @Test
    void externalKeyDistinguishesCurrenciesWithTheSameIban() {
        // Revolut: due conti (EUR e USD) con lo stesso IBAN.
        var eur = new EnableBankingApi.Account("uid-1", new AccountId("LT001"), "Revolut", "EUR", null);
        var usd = new EnableBankingApi.Account("uid-2", new AccountId("LT001"), "Revolut", "USD", null);

        assertThat(ConnectionService.externalKeyOf("Revolut", eur))
                .isNotEqualTo(ConnectionService.externalKeyOf("Revolut", usd));
    }

    @Test
    void externalKeyPrefersTheIdentificationHash() {
        var account = new EnableBankingApi.Account("uid-1", new AccountId("IT001"), "Conto", "EUR", "hash-123");

        assertThat(ConnectionService.externalKeyOf("ING", account)).isEqualTo("hash-123");
    }

    /** Una sessione ING finta con un conto corrente e un conto deposito. */
    private static Session ingSession(String sessionId, String currentAccountUid, String savingsAccountUid) {
        return new Session(sessionId,
                List.of(new EnableBankingApi.Account(currentAccountUid, new AccountId("IT60X001"), "Conto Corrente", "EUR", null),
                        new EnableBankingApi.Account(savingsAccountUid, new AccountId("IT60X002"), "Conto Deposito", "EUR", null)),
                new AspspRef("ING", "IT"),
                new Access(IN_90_DAYS));
    }
}
