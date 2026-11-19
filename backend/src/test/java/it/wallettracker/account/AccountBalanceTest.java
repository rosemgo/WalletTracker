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
    void aBankBalanceFollowsTheMovementsThatArriveAfterItWasRead() {
        // Il caso di Fineco (docs/saldi.md, parte M): il saldo letto la mattina, la cedola scaricata nel pomeriggio.
        BankConnection connection = connectionRepository.save(new BankConnection("FinecoBank", "IT", "s-4",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account fineco = accountRepository.save(new Account(connection, "k-4", "u-4", null, "Dossier", "EUR"));
        movement(fineco, "2026-11-05", "3.95"); // già nel saldo: importato prima della lettura
        // Letto qualche secondo fa (lo stesso istante dell'importazione renderebbe il confronto casuale).
        accountRepository.recordBalance(fineco.getId(), new BigDecimal("2595.30"), LocalDate.of(2026, 11, 16),
                BalanceSource.BANK, Instant.now().minusSeconds(5));
        movement(fineco, "2026-11-16", "1666.86"); // la cedola di oggi, arrivata dopo
        movement(fineco, "2026-11-16", "-208.36"); // e la sua ritenuta
        movement(fineco, "2026-11-02", "50"); // un movimento vecchio arrivato dopo: era già nel saldo della banca

        assertThat(mvc.get().uri("/api/accounts")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$[0].balance.amount").isEqualTo(4053.8);
            json.assertThat().extractingPath("$[0].balance.date").isEqualTo("2026-11-16");
            json.assertThat().extractingPath("$[0].balance.source").isEqualTo("BANK");
        });

        // La banca ridà il saldo, con la cedola dentro: si riparte da lì, niente doppioni.
        accountRepository.recordBalance(fineco.getId(), new BigDecimal("4053.80"), LocalDate.of(2026, 11, 16),
                BalanceSource.BANK, Instant.now().plusSeconds(5));
        assertThat(mvc.get().uri("/api/accounts")).hasStatusOk().bodyJson()
                .extractingPath("$[0].balance.amount").isEqualTo(4053.8);
    }

    @Test
    void aBalanceWithThePendingMovementsShowsThemAndTheBookedOneApart() throws InterruptedException {
        // Revolut (docs/saldi.md, parte O): dà solo il saldo previsto, con i due pagamenti in attesa dentro.
        BankConnection connection = connectionRepository.save(new BankConnection("Revolut", "LT", "s-5",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account revolut = accountRepository.save(new Account(connection, "k-5", "u-5", null, "Revolut", "EUR"));
        Account reloaded = accountRepository.findById(revolut.getId()).orElseThrow();
        transactionRepository.save(new BankTransaction(reloaded, "p-1", TransactionStatus.PENDING,
                LocalDate.of(2026, 11, 18), null, new BigDecimal("-15.02"), "EUR", null, "Conad", "{}"));
        transactionRepository.save(new BankTransaction(reloaded, "p-2", TransactionStatus.PENDING,
                LocalDate.of(2026, 11, 18), null, new BigDecimal("-3.64"), "EUR", null, "Conad", "{}"));
        Thread.sleep(5);
        accountRepository.recordBalance(revolut.getId(), new BigDecimal("44.80"), new BigDecimal("-18.66"),
                LocalDate.of(2026, 11, 19), BalanceSource.BANK, Instant.now());

        // Il saldo con i movimenti in attesa (come nell'app di Revolut, e come le spese, che li contano già), e a parte
        // il contabile.
        assertThat(mvc.get().uri("/api/accounts")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$[0].balance.amount").isEqualTo(44.8);
            json.assertThat().extractingPath("$[0].balance.booked").isEqualTo(63.46);
        });

        // Revolut li contabilizza il giorno dopo: i pagamenti in attesa spariscono, arrivano quelli contabilizzati.
        // Contano una volta sola: il saldo resta quello di Revolut, e il contabile non serve più a parte.
        Thread.sleep(5);
        transactionRepository.deleteAll(transactionRepository.findAll().stream()
                .filter(t -> t.getStatus() == TransactionStatus.PENDING).toList());
        movement(reloaded, "2026-11-20", "-15.02");
        movement(reloaded, "2026-11-20", "-3.64");
        assertThat(mvc.get().uri("/api/accounts")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$[0].balance.amount").isEqualTo(44.8);
            json.assertThat().extractingPath("$[0].balance.booked").isNull();
        });
    }

    @Test
    void aWrittenBalanceOfALinkedAccountIncludesItsPendingMovementsLikeTheBankApp() throws InterruptedException {
        // Un conto collegato di cui la banca non dà il saldo: lo scrive l'utente, copiandolo dall'app della banca, con
        // dentro un pagamento in attesa arrivato dal collegamento (docs/saldi.md, parte O).
        BankConnection connection = connectionRepository.save(new BankConnection("Trade Republic", "DE", "s-6",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account broker = accountRepository.save(new Account(connection, "k-6", "u-6", null, "Conto", "EUR"));
        movement(broker, "2026-11-10", "-20");
        Account reloaded = accountRepository.findById(broker.getId()).orElseThrow();
        transactionRepository.save(new BankTransaction(reloaded, "p-6", TransactionStatus.PENDING,
                LocalDate.of(2026, 11, 18), null, new BigDecimal("-10.00"), "EUR", null, "Pizza", "{}"));

        assertThat(balance(broker, "{\"balance\": 490.00}")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.balance.amount").isEqualTo(490.0); // come nell'app della banca
            json.assertThat().extractingPath("$.balance.booked").isEqualTo(500.0);
        });

        // Il pagamento viene contabilizzato: conta una volta sola.
        Thread.sleep(5);
        transactionRepository.deleteAll(transactionRepository.findAll().stream()
                .filter(t -> t.getStatus() == TransactionStatus.PENDING).toList());
        movement(reloaded, "2026-11-19", "-10.00");
        assertThat(mvc.get().uri("/api/accounts")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$[0].balance.amount").isEqualTo(490.0);
            json.assertThat().extractingPath("$[0].balance.booked").isNull();
        });
    }

    @Test
    void aBankBalanceSavedBeforeV45IsShownAsItIsUntilTheBankGivesItAgain() {
        // Il caso dopo l'aggiornamento (docs/saldi.md, parte P): il saldo di Revolut salvato prima non dice se aveva
        // dentro i pagamenti in attesa. Aggiungerli di nuovo dava −0,99 € invece di 17,67 €.
        BankConnection connection = connectionRepository.save(new BankConnection("Revolut", "LT", "s-7",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account revolut = accountRepository.save(new Account(connection, "k-7", "u-7", null, "Revolut", "EUR"));
        Account reloaded = accountRepository.findById(revolut.getId()).orElseThrow();
        transactionRepository.save(new BankTransaction(reloaded, "p-7", TransactionStatus.PENDING,
                LocalDate.of(2026, 11, 18), null, new BigDecimal("-18.66"), "EUR", null, "Conad", "{}"));
        accountRepository.recordBalance(revolut.getId(), new BigDecimal("17.67"), LocalDate.of(2026, 11, 18),
                BalanceSource.BANK, Instant.now()); // senza tipo: come uno salvato prima della V45

        assertThat(accountRepository.findById(revolut.getId()).orElseThrow().isPendingKnown()).isFalse();
        assertThat(mvc.get().uri("/api/accounts")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$[0].balance.amount").isEqualTo(17.67);
            json.assertThat().extractingPath("$[0].balance.booked").isNull();
        });

        // La banca lo ridà, con il tipo: da lì i movimenti in attesa si contano come sempre.
        accountRepository.recordBalance(revolut.getId(), new BigDecimal("17.67"), new BigDecimal("-18.66"),
                LocalDate.of(2026, 11, 19), BalanceSource.BANK, Instant.now(), "XPCD");
        assertThat(mvc.get().uri("/api/accounts")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$[0].balance.amount").isEqualTo(17.67);
            json.assertThat().extractingPath("$[0].balance.booked").isEqualTo(36.33);
        });
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
