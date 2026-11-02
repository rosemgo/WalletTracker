package it.wallettracker.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.connection.BankConnection;
import it.wallettracker.connection.BankConnectionRepository;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.TransactionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Il saldo dei conti (docs/saldi.md): quello scritto a mano per un conto manuale, e quello della banca. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class AccountBalanceTest {

    @Autowired
    MockMvcTester mvc;

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
    void theWrittenBalanceStartsFromTheLastMovementAndFollowsTheOnesImportedAfter() {
        Account wallet = accountRepository.save(Account.manual("Tinaba", "Wallet", null, "EUR",
                AccountRole.INVESTMENT));
        movement(wallet, "2026-10-02", "-200");
        movement(wallet, "2026-10-28", "200");

        // L'utente guarda l'app della banca dopo l'ultimo movimento (28 ottobre) e scrive il saldo.
        assertThat(balance(wallet, "{\"balance\": 350.00}"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.balance.amount").isEqualTo(350.0);
                    json.assertThat().extractingPath("$.balance.date").isEqualTo("2026-10-28");
                    json.assertThat().extractingPath("$.balance.startDate").isEqualTo("2026-10-28");
                    json.assertThat().extractingPath("$.balance.source").isEqualTo("MANUAL");
                });

        // Un'importazione dopo: un movimento dello stesso giorno che prima non c'era, uno nuovo, e uno vecchio (un
        // file con più storia). Contano i primi due; il vecchio era già dentro il saldo scritto.
        movement(wallet, "2026-10-28", "46.74");
        movement(wallet, "2026-11-01", "-50");
        movement(wallet, "2025-06-01", "-999");

        assertThat(mvc.get().uri("/api/accounts"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$[0].balance.amount").isEqualTo(346.74);
                    json.assertThat().extractingPath("$[0].balance.date").isEqualTo("2026-11-01");
                    json.assertThat().extractingPath("$[0].balance.startDate").isEqualTo("2026-10-28");
                });

        // null lo toglie.
        assertThat(balance(wallet, "{\"balance\": null}")).hasStatusOk().bodyJson()
                .extractingPath("$.balance").isNull();
    }

    @Test
    void aNewAccountWithoutMovementsStartsFromToday() {
        Account wallet = accountRepository.save(Account.manual("Tinaba", "Wallet", null, "EUR",
                AccountRole.INVESTMENT));

        assertThat(balance(wallet, "{\"balance\": 0}")).hasStatusOk().bodyJson()
                .extractingPath("$.balance.startDate").isEqualTo(LocalDate.now().toString());
    }

    @Test
    void theBalanceOfALinkedAccountComesOnlyFromTheBank() {
        BankConnection connection = connectionRepository.save(new BankConnection("Banca Alfa", "IT", "s-1",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account linked = accountRepository.save(new Account(connection, "k-1", "u-1", null, "MARIO ROSSI", "EUR"));
        accountRepository.recordBalance(linked.getId(), new BigDecimal("1234.56"), LocalDate.of(2026, 10, 31),
                BalanceSource.BANK, Instant.now());

        assertThat(balance(linked, "{\"balance\": 10}")).hasStatus(409);
        assertThat(mvc.get().uri("/api/accounts"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$[0].balance.amount").isEqualTo(1234.56);
                    json.assertThat().extractingPath("$[0].balance.date").isEqualTo("2026-10-31");
                    json.assertThat().extractingPath("$[0].balance.source").isEqualTo("BANK");
                    json.assertThat().extractingPath("$[0].balance.startDate").isNull();
                });
    }

    @Test
    void aLinkedAccountWithoutTheBankBalanceCanHaveOneWritten() {
        // Trade Republic con Enable Banking: la banca non dà il saldo, lo scrive l'utente.
        BankConnection connection = connectionRepository.save(new BankConnection("Trade Republic", "DE", "s-2",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account broker = accountRepository.save(new Account(connection, "k-2", "u-2", null, "MARIO ROSSI", "EUR"));
        movement(broker, "2026-10-28", "-200");

        assertThat(balance(broker, "{\"balance\": 500}")).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.balance.amount").isEqualTo(500);
                    json.assertThat().extractingPath("$.balance.source").isEqualTo("MANUAL");
                });
        // I movimenti che arrivano dopo con la sincronizzazione lo aggiornano, come quelli di un file.
        movement(broker, "2026-10-30", "12.50");
        assertThat(mvc.get().uri("/api/accounts")).hasStatusOk().bodyJson()
                .extractingPath("$[0].balance.amount").isEqualTo(512.5);
    }

    @Test
    void aCreditCardShowsTheAvailableCredit() {
        BankConnection connection = connectionRepository.save(new BankConnection("Banca Alfa", "IT", "s-3",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account card = accountRepository.save(new Account(connection, "k-3", "u-3", null, "Carta", "EUR"));
        accountRepository.recordAvailableCredit(card.getId(), new BigDecimal("2187.60"), LocalDate.of(2026, 11, 2));

        // Finché il conto non ha il ruolo Carta di credito, il plafond non si mostra.
        assertThat(mvc.get().uri("/api/accounts")).hasStatusOk().bodyJson()
                .extractingPath("$[0].availableCredit").isNull();
        Account reloaded = accountRepository.findById(card.getId()).orElseThrow();
        reloaded.changeRole(AccountRole.CREDIT_CARD);
        accountRepository.save(reloaded);
        assertThat(mvc.get().uri("/api/accounts")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$[0].availableCredit.amount").isEqualTo(2187.6);
            json.assertThat().extractingPath("$[0].availableCredit.date").isEqualTo("2026-11-02");
        });
        // Per una carta non c'è un saldo da scrivere (docs/saldi.md, parte J).
        assertThat(balance(card, "{\"balance\": 300}")).hasStatus(409);
    }

    @Test
    void wrongBalancesAreRejected() {
        Account wallet = accountRepository.save(Account.manual("Tinaba", "Wallet", null, "EUR",
                AccountRole.INVESTMENT));

        assertThat(balance(wallet, "{\"balance\": 12.345}")).hasStatus(400);
        assertThat(mvc.put().uri("/api/accounts/999999/balance").contentType(MediaType.APPLICATION_JSON)
                .content("{\"balance\": 1}")).hasStatus(404);
    }

    private MvcTestResult balance(Account account, String json) {
        return mvc.put().uri("/api/accounts/{id}/balance", account.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private void movement(Account account, String date, String amount) {
        transactionRepository.save(new BankTransaction(account, "t-" + (++counter), TransactionStatus.BOOKED,
                LocalDate.parse(date), null, new BigDecimal(amount), "EUR", null, "Movimento " + counter, "{}"));
    }
}
