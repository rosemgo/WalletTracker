package it.wallettracker.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.BalanceCheckService.Mismatch;
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

/** Il controllo dei saldi (docs/saldi.md, parte N): due saldi e i movimenti in mezzo devono tornare. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class BalanceCheckTest {

    @Autowired
    BalanceCheckService checkService;

    @Autowired
    BalanceCheckRepository checkRepository;

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
        checkRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    @Test
    void twoBankBalancesMustAddUpWithTheMovementsInBetween() {
        BankConnection connection = connectionRepository.save(new BankConnection("FinecoBank", "IT", "s-1",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account fineco = accountRepository.save(new Account(connection, "k-1", "u-1", null, "Dossier", "EUR"));
        accountRepository.recordBalance(fineco.getId(), new BigDecimal("1000.00"), null, LocalDate.of(2026, 11, 10),
                BalanceSource.BANK, Instant.now().minusSeconds(60), "CLBD");
        movement(fineco, "2026-11-12", "-200");

        // 1.000 − 200 = 800: torna, niente da salvare né da segnalare.
        checkService.compare(reload(fineco), BalanceSource.BANK, new BigDecimal("800.00"), null, null,
                LocalDate.of(2026, 11, 13), Instant.now().plusSeconds(5));
        assertThat(checkRepository.count()).isZero();

        // 900: 100 € che i movimenti nell'app non spiegano.
        checkService.compare(reload(fineco), BalanceSource.BANK, new BigDecimal("900.00"), null, null,
                LocalDate.of(2026, 11, 13), Instant.now().plusSeconds(5));
        assertThat(checkService.mismatches()).singleElement().satisfies(m -> {
            assertThat(m.from()).isEqualTo(LocalDate.of(2026, 11, 10));
            assertThat(m.to()).isEqualTo(LocalDate.of(2026, 11, 13));
            assertThat(m.difference()).isEqualByComparingTo("100.00");
            assertThat(m.source()).isEqualTo(BalanceSource.BANK);
        });
        assertThat(mvc.get().uri("/api/alerts")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$[0].kind").isEqualTo("BALANCE_MISMATCH");
            json.assertThat().extractingPath("$[0].account").asString().contains("Dossier");
            json.assertThat().extractingPath("$[0].mismatch.accountId").isEqualTo(fineco.getId().intValue());
            json.assertThat().extractingPath("$[0].mismatch.from").isEqualTo("2026-11-10");
            json.assertThat().extractingPath("$[0].mismatch.to").isEqualTo("2026-11-13");
            json.assertThat().extractingPath("$[0].mismatch.difference").isEqualTo(100.0);
            json.assertThat().extractingPath("$[0].mismatch.source").isEqualTo("BANK");
        });

        // Arriva il movimento che mancava (per esempio da un file): l'avviso sparisce da solo.
        movement(fineco, "2026-11-11", "100");
        assertThat(checkService.mismatches()).isEmpty();
    }

    @Test
    void aMovementOfTheFirstDayIsInTheFirstBalanceOnlyIfItArrivedBeforeIt() throws InterruptedException {
        BankConnection connection = connectionRepository.save(new BankConnection("FinecoBank", "IT", "s-2",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account fineco = accountRepository.save(new Account(connection, "k-2", "u-2", null, "Dossier", "EUR"));
        // In ordine di tempo, con qualche millesimo di secondo in mezzo: un movimento, la lettura del saldo, la cedola.
        movement(fineco, "2026-11-16", "50"); // già nel primo saldo: importato prima della lettura
        Thread.sleep(5);
        accountRepository.recordBalance(fineco.getId(), new BigDecimal("2595.30"), null, LocalDate.of(2026, 11, 16),
                BalanceSource.BANK, Instant.now(), "CLBD");
        Thread.sleep(5);
        movement(fineco, "2026-11-16", "1458.50"); // la cedola dello stesso giorno, arrivata dopo (parte M)

        checkService.compare(reload(fineco), BalanceSource.BANK, new BigDecimal("4053.80"), null, null,
                LocalDate.of(2026, 11, 17), Instant.now().plusSeconds(60));
        // La cedola conta (arrivata dopo il primo saldo); i 50 € no (erano già dentro).
        assertThat(checkService.mismatches()).isEmpty();
    }

    @Test
    void aBalanceWithThePendingMovementsIsComparedWithoutThem() {
        // Revolut (docs/saldi.md, parte O): il saldo comprende i pagamenti in attesa; i movimenti in mezzo sono solo
        // quelli contabilizzati.
        BankConnection connection = connectionRepository.save(new BankConnection("Revolut", "LT", "s-3",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account revolut = accountRepository.save(new Account(connection, "k-3", "u-3", null, "Revolut", "EUR"));
        accountRepository.recordBalance(revolut.getId(), new BigDecimal("63.46"), BigDecimal.ZERO,
                LocalDate.of(2026, 11, 17), BalanceSource.BANK, Instant.now().minusSeconds(60));
        movement(revolut, "2026-11-18", "-2.28"); // contabilizzato
        pending(revolut, "2026-11-18", "-15.02");
        pending(revolut, "2026-11-18", "-3.64");

        // Il saldo nuovo, 42,52 €, ha dentro i due pagamenti in attesa (−18,66 €): senza, 61,18 = 63,46 − 2,28.
        checkService.compare(reload(revolut), BalanceSource.BANK, new BigDecimal("42.52"), new BigDecimal("-18.66"),
                null, LocalDate.of(2026, 11, 19), Instant.now().plusSeconds(5));
        assertThat(checkRepository.count()).isZero();
        assertThat(checkService.mismatches()).isEmpty();
    }

    @Test
    void aBalanceSavedBeforeV46IsNotCompared() {
        // Non si sa se comprendeva movimenti in attesa (docs/saldi.md, parte P): il confronto potrebbe segnalare una
        // differenza che non c'è. Si controlla dalla lettura dopo.
        BankConnection connection = connectionRepository.save(new BankConnection("Revolut", "LT", "s-4",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account revolut = accountRepository.save(new Account(connection, "k-4", "u-4", null, "Revolut", "EUR"));
        accountRepository.recordBalance(revolut.getId(), new BigDecimal("17.67"), LocalDate.of(2026, 11, 18),
                BalanceSource.BANK, Instant.now().minusSeconds(60)); // senza tipo
        movement(revolut, "2026-11-19", "-18.66");

        checkService.compare(reload(revolut), BalanceSource.BANK, new BigDecimal("17.67"), null, null,
                LocalDate.of(2026, 11, 19), Instant.now().plusSeconds(5));
        assertThat(checkRepository.count()).isZero();
    }

    @Test
    void aMovementOfTheFirstDayThatArrivedLateButWasAlreadyInTheBalanceRaisesNoNotice() throws InterruptedException {
        // docs/saldi.md, parte Q: la banca ha scalato i 30 € prima di mostrare il movimento; arriva dopo la lettura.
        BankConnection connection = connectionRepository.save(new BankConnection("Banca Alfa", "IT", "s-5",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account alfa = accountRepository.save(new Account(connection, "k-5", "u-5", null, "Conto", "EUR"));
        accountRepository.recordBalance(alfa.getId(), new BigDecimal("970.00"), null, LocalDate.of(2026, 11, 19),
                BalanceSource.BANK, Instant.now(), "CLBD");
        Thread.sleep(5);
        movement(alfa, "2026-11-19", "-30.00"); // arrivato dopo: già nel saldo da 970 €

        // Il giorno dopo la banca ridà 970 €: per l'app 970 − 30 avrebbe dovuto fare 940; ma i 30 € spiegano tutto.
        checkService.compare(reload(alfa), BalanceSource.BANK, new BigDecimal("970.00"), null, null,
                LocalDate.of(2026, 11, 20), Instant.now().plusSeconds(5));
        assertThat(checkRepository.count()).isZero();

        // Una differenza che i movimenti in ritardo non spiegano resta un avviso.
        checkService.compare(reload(alfa), BalanceSource.BANK, new BigDecimal("950.00"), null, null,
                LocalDate.of(2026, 11, 20), Instant.now().plusSeconds(5));
        assertThat(checkService.mismatches()).singleElement()
                .satisfies(m -> assertThat(m.difference()).isEqualByComparingTo("10.00"));
    }

    @Test
    void theLastMovementInTheBalanceSaidByTheBankIsInsideItWhenItArrives() throws InterruptedException {
        BankConnection connection = connectionRepository.save(new BankConnection("Banca Alfa", "IT", "s-6",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account alfa = accountRepository.save(new Account(connection, "k-6", "u-6", null, "Conto", "EUR"));
        accountRepository.recordBalance(alfa.getId(), new BigDecimal("970.00"), null, LocalDate.of(2026, 11, 19),
                BalanceSource.BANK, Instant.now(), "CLBD", "E-42");
        Thread.sleep(5);
        transactionRepository.save(new BankTransaction(reload(alfa), "ref:E-42", TransactionStatus.BOOKED,
                LocalDate.of(2026, 11, 19), null, new BigDecimal("-30.00"), "EUR", null, "Bancomat", "{}"));

        checkService.compare(reload(alfa), BalanceSource.BANK, new BigDecimal("970.00"), null, null,
                LocalDate.of(2026, 11, 20), Instant.now().plusSeconds(5));
        assertThat(checkRepository.count()).isZero();
    }

    @Test
    void twoWrittenBalancesMustAddUpWithTheMovementsLoadedInBetween() {
        Account wallet = accountRepository.save(Account.manual("Tinaba", "Wallet", null, "EUR", AccountRole.MAIN));
        movement(wallet, "2026-10-01", "-40");
        writeBalance(wallet, "{\"balance\": 500.00}");
        movement(wallet, "2026-10-28", "200"); // un file caricato dopo
        writeBalance(wallet, "{\"balance\": 650.00}"); // 500 + 200 farebbe 700

        assertThat(checkService.mismatches()).extracting(Mismatch::difference, Mismatch::source)
                .singleElement().satisfies(t -> {
                    assertThat((BigDecimal) t.toList().get(0)).isEqualByComparingTo("-50.00");
                    assertThat(t.toList().get(1)).isEqualTo(BalanceSource.MANUAL);
                });
        assertThat(mvc.get().uri("/api/alerts")).hasStatusOk().bodyJson()
                .extractingPath("$[0].mismatch.source").isEqualTo("MANUAL");
    }

    @Test
    void theFirstBalanceOfAnAccountHasNothingToCompareWith() {
        Account wallet = accountRepository.save(Account.manual("Tinaba", "Wallet", null, "EUR", AccountRole.MAIN));
        writeBalance(wallet, "{\"balance\": 500.00}");
        assertThat(checkRepository.count()).isZero();
    }

    private void writeBalance(Account account, String json) {
        assertThat(mvc.put().uri("/api/accounts/{id}/balance", account.getId())
                .contentType(MediaType.APPLICATION_JSON).content(json)).hasStatusOk();
    }

    private Account reload(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow();
    }

    private void pending(Account account, String date, String amount) {
        transactionRepository.save(new BankTransaction(account, "p-" + (++counter), TransactionStatus.PENDING,
                LocalDate.parse(date), null, new BigDecimal(amount), "EUR", null, "In attesa " + counter, "{}"));
    }

    private void movement(Account account, String date, String amount) {
        transactionRepository.save(new BankTransaction(account, "c-" + (++counter), TransactionStatus.BOOKED,
                LocalDate.parse(date), null, new BigDecimal(amount), "EUR", null, "Movimento " + counter, "{}"));
    }
}
