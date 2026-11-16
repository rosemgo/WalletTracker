package it.wallettracker.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

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

        // Un saldo della banca di ieri: la fotografia va sotto ieri, e oggi si stima con i movimenti arrivati dopo.
        snapshotRepository.deleteAll();
        accountRepository.recordBalance(main.getId(), new BigDecimal("1000.00"), TODAY.minusDays(1),
                BalanceSource.BANK, Instant.now());
        movement(main, "2026-11-16", "-30");
        historyService.recordToday(TODAY);
        assertThat(snapshotRepository.findAll()).filteredOn(shot -> shot.getAccountId().equals(main.getId()))
                .extracting(BalanceSnapshot::getDate).containsExactly(TODAY.minusDays(1));
        assertThat(historyService.history("EUR", TODAY).points()).last().satisfies(p -> {
            assertThat(p.amount()).isEqualByComparingTo("970.00");
            assertThat(p.estimated()).isTrue();
        });

        assertThat(mvc.get().uri("/api/accounts/balance-history?currency=EUR")).hasStatusOk().bodyJson()
                .satisfies(json -> json.assertThat().extractingPath("$.currency").isEqualTo("EUR"));
        assertThat(mvc.get().uri("/api/accounts/balance-history?currency=EURO")).hasStatus(400);
        // Un conto solo: il conto in euro è lo stesso del totale; la carta e il conto in dollari, in euro, niente.
        assertThat(historyService.history("EUR", main.getId(), TODAY).points()).last()
                .satisfies(p -> assertThat(p.amount()).isEqualByComparingTo("970.00"));
        assertThat(historyService.history("EUR", card.getId(), TODAY).points()).isEmpty();
        assertThat(historyService.history("EUR", dollars.getId(), TODAY).points()).isEmpty();
        assertThat(mvc.get().uri("/api/accounts/balance-history?currency=USD&accountId={id}", dollars.getId()))
                .hasStatusOk().bodyJson()
                .satisfies(json -> json.assertThat().extractingPath("$.accounts").isEqualTo(1));
        assertThat(historyService.history("GBP", TODAY).points()).isEmpty();
    }

    @Test
    void aWrittenBalanceHasNoPhotosAndIsTrueFromItsStart() {
        Account wallet = accountRepository.save(Account.manual("Tinaba", "Wallet", null, "EUR", AccountRole.MAIN));
        movement(wallet, "2026-11-10", "-40");
        accountRepository.recordBalance(wallet.getId(), new BigDecimal("500.00"), LocalDate.of(2026, 11, 14),
                BalanceSource.MANUAL, Instant.parse("2026-11-14T10:00:00Z"));
        movement(wallet, "2026-11-15", "25"); // caricato dopo, da un file

        assertThat(historyService.recordToday(TODAY)).isZero();
        assertThat(snapshotRepository.count()).isZero();

        BalanceHistory history = historyService.history("EUR", TODAY);
        // Dal primo movimento (il 10): prima del 14 stima, dal 14 il saldo scritto più i movimenti.
        assertThat(history.points()).first().satisfies(p -> {
            assertThat(p.date()).isEqualTo(LocalDate.of(2026, 11, 10));
            assertThat(p.estimated()).isTrue();
        });
        assertThat(history.points()).filteredOn(p -> !p.date().isBefore(LocalDate.of(2026, 11, 14)))
                .extracting(p -> p.amount().intValue(), Point::estimated)
                .containsExactly(tuple(500, false),
                        tuple(525, false),
                        tuple(525, false));
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
