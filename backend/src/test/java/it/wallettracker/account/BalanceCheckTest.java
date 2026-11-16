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
        accountRepository.recordBalance(fineco.getId(), new BigDecimal("1000.00"), LocalDate.of(2026, 11, 10),
                BalanceSource.BANK, Instant.now().minusSeconds(60));
        movement(fineco, "2026-11-12", "-200");

        // 1.000 − 200 = 800: torna, niente da salvare né da segnalare.
        checkService.compare(reload(fineco), BalanceSource.BANK, new BigDecimal("800.00"),
                LocalDate.of(2026, 11, 13), Instant.now().plusSeconds(5));
        assertThat(checkRepository.count()).isZero();

        // 900: 100 € che i movimenti nell'app non spiegano.
        checkService.compare(reload(fineco), BalanceSource.BANK, new BigDecimal("900.00"),
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
        accountRepository.recordBalance(fineco.getId(), new BigDecimal("2595.30"), LocalDate.of(2026, 11, 16),
                BalanceSource.BANK, Instant.now());
        Thread.sleep(5);
        movement(fineco, "2026-11-16", "1458.50"); // la cedola dello stesso giorno, arrivata dopo (parte M)

        checkService.compare(reload(fineco), BalanceSource.BANK, new BigDecimal("4053.80"),
                LocalDate.of(2026, 11, 17), Instant.now().plusSeconds(60));
        // La cedola conta (arrivata dopo il primo saldo); i 50 € no (erano già dentro).
        assertThat(checkService.mismatches()).isEmpty();
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

    private void movement(Account account, String date, String amount) {
        transactionRepository.save(new BankTransaction(account, "c-" + (++counter), TransactionStatus.BOOKED,
                LocalDate.parse(date), null, new BigDecimal(amount), "EUR", null, "Movimento " + counter, "{}"));
    }
}
