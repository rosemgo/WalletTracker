package it.wallettracker.investment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.account.AccountRole;
import it.wallettracker.account.BalanceSource;
import it.wallettracker.category.CategoryRepository;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.connection.BankConnection;
import it.wallettracker.connection.BankConnectionRepository;
import it.wallettracker.investment.InvestmentDtos.Cash;
import it.wallettracker.investment.InvestmentDtos.Group;
import it.wallettracker.investment.InvestmentDtos.Month;
import it.wallettracker.investment.InvestmentDtos.Overview;
import it.wallettracker.investment.InvestmentDtos.Period;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.TransactionDto;
import it.wallettracker.transaction.TransactionStatus;
import it.wallettracker.transaction.TransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Gli investimenti con un database vero; "oggi" è il 15 ottobre 2026. La storia, inventata:
 * <ul>
 *   <li>un PAC: ogni mese il 5, 200 € da ING verso un conto che non è nell'app (si vede solo l'uscita), da
 *       gennaio a ottobre 2026, e una volta ad agosto 2025;</li>
 *   <li>a marzo 1.000 € da ING al conto titoli: l'uscita, l'entrata (l'altra metà) e l'acquisto di un ETF;</li>
 *   <li>a maggio un acquisto da 500 € nel deposito titoli del conto corrente (Compravendita Titoli);</li>
 *   <li>ad agosto una vendita da 300 € sul conto titoli; a settembre 100 € tornano dal conto titoli a ING (le due
 *       metà); a ottobre una spesa da 30 € con la carta del conto titoli;</li>
 *   <li>un secondo conto di investimento, "Fondo Beta", con un versamento e nessun acquisto;</li>
 *   <li>cedole da 30 € su ING a dicembre 2025 e ad aprile 2026, un dividendo da 12,50 € e il bollo da 2 € sul conto
 *       titoli.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class InvestmentServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    @Autowired
    InvestmentService investmentService;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    BankTransactionRepository transactionRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    BankConnectionRepository connectionRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    ClassificationService classificationService;

    private Account ing;
    private Account broker;
    private Account fund;
    private int counter;

    @BeforeEach
    void history() {
        BankConnection connection = connectionRepository.save(new BankConnection("Banca Alfa", "IT", "session-1",
                Instant.now().plus(90, ChronoUnit.DAYS)));
        ing = accountRepository.save(new Account(connection, "key-1", "uid-1", null, "Conto corrente", "EUR"));
        broker = accountRepository.save(new Account(connection, "key-2", "uid-2", null, "Conto titoli", "EUR"));
        broker.changeRole(AccountRole.INVESTMENT);
        accountRepository.save(broker);

        save(ing, "2025-08-05", "-200", TransactionType.INVESTMENT_DEPOSIT, "Bonifico PAC", null);
        for (int month = 1; month <= 10; month++) {
            save(ing, LocalDate.of(2026, month, 5).toString(), "-200", TransactionType.INVESTMENT_DEPOSIT,
                    "Bonifico PAC", null);
        }
        fund = accountRepository.save(Account.manual("Fondo Beta", "PAC", null, "EUR", AccountRole.INVESTMENT));
        save(fund, "2026-02-05", "200", TransactionType.INVESTMENT_DEPOSIT, "Versamento", null);
        save(ing, "2026-05-20", "-500", TransactionType.SECURITIES_BUY, "Compravendita Titoli BTP", null);
        save(broker, "2026-08-10", "300", TransactionType.SECURITIES_SELL, "Sell trade ETF MONDO", null);
        save(broker, "2026-10-02", "-30", TransactionType.EXPENSE, "Carta: LIBRERIA ROSSI", null); // non conta
        save(ing, "2026-03-10", "-1000", TransactionType.INVESTMENT_DEPOSIT, "Bonifico al conto titoli", null);
        save(broker, "2026-03-10", "1000", TransactionType.INVESTMENT_DEPOSIT, "Deposito", null);
        save(broker, "2026-03-11", "-1000", TransactionType.SECURITIES_BUY, "Buy trade ETF MONDO", null);
        save(broker, "2026-09-15", "-100", TransactionType.INVESTMENT_WITHDRAWAL, "Prelievo", null);
        save(ing, "2026-09-15", "100", TransactionType.INVESTMENT_WITHDRAWAL, "Dal conto titoli", null);
        save(ing, "2025-12-01", "30", TransactionType.INVESTMENT_INCOME, "Ced.su BTP ITALIA", "Cedole");
        save(ing, "2026-04-01", "30", TransactionType.INVESTMENT_INCOME, "Ced.su BTP ITALIA", "Cedole");
        save(broker, "2026-06-20", "12.50", TransactionType.INVESTMENT_INCOME, "Cash Dividend ACME", "Dividendi");
        save(broker, "2026-07-01", "-2", TransactionType.INVESTMENT_TAX, "Stamp Duty Tax", null);
        save(ing, "2026-10-08", "-40", TransactionType.EXPENSE, "ESSELUNGA MILANO", null); // non è un investimento
        classificationService.classifyAll(); // il tipo scelto a mano diventa il tipo del movimento
    }

    @AfterEach
    void cleanDatabase() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    @Test
    void theInvestedMoneyAndTheTransfersAreTwoThingsEachCountedOnce() {
        Overview o = investmentService.overview(Period.YEAR, null, TODAY);

        // Investito in titoli: 1.000 (ETF) + 500 (deposito titoli del conto corrente) − 300 (vendita).
        assertThat(o.investedTotal()).isEqualByComparingTo("1200");
        assertThat(o.investedThisYear()).isEqualByComparingTo("1200");
        assertThat(o.investedThisMonth()).isEqualByComparingTo("0");
        assertThat(o.boughtInPeriod()).isEqualByComparingTo("1500");
        assertThat(o.soldInPeriod()).isEqualByComparingTo("300");
        // Bonifici: 200 (2025) + 10 × 200 + 1.000 − 100. L'altra metà sul conto titoli e il versamento su Fondo Beta
        // non si sommano; la spesa con la carta del conto titoli non tocca niente.
        assertThat(o.transferredTotal()).isEqualByComparingTo("3100");
        assertThat(o.transferredInPeriod()).isEqualByComparingTo("2900");
        assertThat(o.incomeThisYear()).isEqualByComparingTo("42.50");
        assertThat(o.currency()).isEqualTo("EUR");

        // 12 mesi, da novembre 2025.
        assertThat(o.from()).isEqualTo(YearMonth.of(2025, 11));
        assertThat(o.months()).hasSize(12);
        assertThat(o.months()).extracting(Month::investedCumulative).extracting(BigDecimal::intValue)
                .containsExactly(0, 0, 0, 0, 1000, 1000, 1500, 1500, 1500, 1200, 1200, 1200);
        assertThat(o.months()).extracting(Month::transferred).extracting(BigDecimal::intValue)
                .containsExactly(0, 0, 200, 200, 1200, 200, 200, 200, 200, 200, 100, 200);
        assertThat(o.incomeInPeriod()).isEqualByComparingTo("72.50");
        assertThat(o.taxesInPeriod()).isEqualByComparingTo("-2");
        assertThat(o.incomeByType()).extracting(Group::name).containsExactly("Cedole", "Dividendi");
        assertThat(o.incomeByType().get(0).amount()).isEqualByComparingTo("60");

        // Gli elenchi: acquisti e vendite di tutti i conti; i bonifici dal lato di ING. Dal più recente.
        assertThat(o.trades()).extracting(TransactionDto::bookingDate).containsExactly(LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 5, 20), LocalDate.of(2026, 3, 11));
        assertThat(o.transfers()).hasSize(12);
        assertThat(o.transfers().getFirst().bookingDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(o.transfers()).allMatch(t -> t.accountId().equals(ing.getId()));

        // Fondo Beta ha solo un versamento: il suo investito non si vede. Il conto titoli ha acquisti: nessun avviso.
        assertThat(o.onlyTransfers()).containsExactly(fund.label());
    }

    @Test
    void everythingStartsFromTheFirstInvestment() {
        Overview o = investmentService.overview(Period.ALL, null, TODAY);

        assertThat(o.from()).isEqualTo(YearMonth.of(2025, 8));
        assertThat(o.months()).hasSize(15);
        assertThat(o.months().getFirst().transferred()).isEqualByComparingTo("200");
        assertThat(o.months().getFirst().investedCumulative()).isEqualByComparingTo("0");
        assertThat(o.transferredInPeriod()).isEqualByComparingTo("3100");
        assertThat(o.transfers()).hasSize(13);
    }

    @Test
    void theCashIsTheBalanceOfEachInvestmentAccountWhenItIsKnown() {
        // Il conto titoli è collegato: il saldo lo dà la banca. Fondo Beta è manuale: nessuno l'ha scritto.
        accountRepository.recordBalance(broker.getId(), new BigDecimal("118.50"), LocalDate.of(2026, 10, 14),
                BalanceSource.BANK, Instant.now());

        Overview o = investmentService.overview(Period.YEAR, null, TODAY);

        // Solo i conti di investimento: il conto corrente no.
        assertThat(o.cash()).extracting(Cash::accountId).containsExactlyInAnyOrder(broker.getId(), fund.getId());
        Cash brokerCash = o.cash().stream().filter(c -> c.accountId().equals(broker.getId())).findFirst()
                .orElseThrow();
        assertThat(brokerCash.balance().amount()).isEqualByComparingTo("118.50");
        assertThat(brokerCash.manual()).isFalse();
        Cash fundCash = o.cash().stream().filter(c -> c.accountId().equals(fund.getId())).findFirst().orElseThrow();
        assertThat(fundCash.balance()).isNull();
        assertThat(fundCash.manual()).isTrue();
    }

    @Test
    void theApiAnswersAndRejectsWrongRequests() {
        assertThat(mvc.get().uri("/api/investments?period=TWO_YEARS"))
                .hasStatusOk()
                .bodyJson().extractingPath("$.months.length()").isEqualTo(24);
        assertThat(mvc.get().uri("/api/investments?currency=EURO")).hasStatus(400);
        assertThat(mvc.get().uri("/api/investments?period=SEMPRE")).hasStatus(400);
    }

    private void save(Account account, String date, String amount, TransactionType type, String description,
            String category) {
        BankTransaction transaction = new BankTransaction(account, "tx-" + (++counter), TransactionStatus.BOOKED,
                LocalDate.parse(date), null, new BigDecimal(amount), "EUR", null, description, "{}");
        transaction.setManualType(type);
        if (category != null) {
            transaction.setManualCategory(categoryRepository.findByNameIgnoreCase(category).orElseThrow());
        }
        transactionRepository.save(transaction);
    }
}
