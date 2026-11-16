package it.wallettracker.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.BalanceHistory.Point;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Lo storico della liquidità con un database vero (docs/saldi.md, parte L): le fotografie e la stima. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class BalanceHistoryServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

    @Autowired
    BalanceHistoryService historyService;

    @Autowired
    BalanceSnapshotRepository snapshotRepository;

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
        snapshotRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    @Test
    void theHistoryCountsTheAccountsOfTheLiquidityAndMarksWhatIsEstimated() {
        BankConnection connection = connectionRepository.save(new BankConnection("Banca Alfa", "IT", "s-1",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        Account main = accountRepository.save(new Account(connection, "k-1", "u-1", null, "Conto", "EUR"));
        accountRepository.recordBalance(main.getId(), new BigDecimal("1000.00"), TODAY, BalanceSource.BANK,
                Instant.now());
        movement(main, "2026-11-14", "-200");
        movement(main, "2026-11-15", "1500");
        // Una carta di credito con un "saldo" e un conto in dollari: non sono nella liquidità in euro.
        Account card = accountRepository.save(new Account(connection, "k-2", "u-2", null, "Carta", "EUR"));
        card.changeRole(AccountRole.CREDIT_CARD);
        accountRepository.save(card);
        accountRepository.recordBalance(card.getId(), new BigDecimal("-300"), TODAY, BalanceSource.BANK,
                Instant.now());
        Account dollars = accountRepository.save(new Account(connection, "k-3", "u-3", null, "USD", "USD"));
        accountRepository.recordBalance(dollars.getId(), new BigDecimal("50"), TODAY, BalanceSource.BANK,
                Instant.now());

        // La fotografia di oggi: i conti che contano e hanno un saldo (non la carta).
        assertThat(historyService.recordToday(TODAY)).isEqualTo(2);
        assertThat(historyService.recordToday(TODAY)).isEqualTo(2); // di nuovo: sostituisce, non raddoppia
        assertThat(snapshotRepository.count()).isEqualTo(2);

        BalanceHistory history = historyService.history("eur", TODAY);
        assertThat(history.currency()).isEqualTo("EUR");
        assertThat(history.accounts()).isEqualTo(1);
        // Dal primo movimento (il 14) a oggi.
        assertThat(history.points()).extracting(Point::date)
                .containsExactly(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15), TODAY);
        assertThat(history.points()).extracting(p -> p.amount().intValue()).containsExactly(-500, 1000, 1000);
        assertThat(history.points()).extracting(Point::estimated).containsExactly(true, true, false);

        assertThat(mvc.get().uri("/api/accounts/balance-history?currency=EUR")).hasStatusOk().bodyJson()
                .satisfies(json -> json.assertThat().extractingPath("$.currency").isEqualTo("EUR"));
        assertThat(mvc.get().uri("/api/accounts/balance-history?currency=EURO")).hasStatus(400);
        assertThat(historyService.history("GBP", TODAY).points()).isEmpty();
    }

    @Test
    void deletingAManualAccountDeletesItsPhotos() {
        Account wallet = accountRepository.save(Account.manual("Tinaba", "Wallet", null, "EUR", AccountRole.MAIN));
        snapshotRepository.upsert(wallet.getId(), TODAY, new BigDecimal("10"));
        assertThat(snapshotRepository.count()).isEqualTo(1);

        assertThat(mvc.delete().uri("/api/accounts/{id}", wallet.getId())).hasStatus2xxSuccessful();
        assertThat(snapshotRepository.count()).isZero();
    }

    private void movement(Account account, String date, String amount) {
        transactionRepository.save(new BankTransaction(account, "h-" + (++counter), TransactionStatus.BOOKED,
                LocalDate.parse(date), null, new BigDecimal(amount), "EUR", null, "Movimento " + counter, "{}"));
    }
}
