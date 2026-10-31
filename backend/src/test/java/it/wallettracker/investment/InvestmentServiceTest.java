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
import it.wallettracker.category.CategoryRepository;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.connection.BankConnection;
import it.wallettracker.connection.BankConnectionRepository;
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
 *   <li>un PAC: ogni mese il 5, 200 € da ING verso un conto che non si può collegare (si vede solo l'uscita), da
 *       gennaio a ottobre 2026, e una volta ad agosto 2025;</li>
 *   <li>a marzo 1.000 € da ING a Trade Republic: l'uscita, l'entrata (l'altra metà) e l'acquisto di un ETF;</li>
 *   <li>a settembre 100 € tornano da Trade Republic a ING (le due metà);</li>
 *   <li>cedole da 30 € su ING a dicembre 2025 e ad aprile 2026, un dividendo da 12,50 € e il bollo da 2 € su Trade
 *       Republic.</li>
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
    void theInvestedMoneyIsCountedOnceAndTheIncomeIsAddedUp() {
        Overview o = investmentService.overview(Period.YEAR, null, TODAY);

        // 200 (2025) + 10 × 200 + 1.000 − 100: l'entrata su Trade Republic e l'acquisto dell'ETF non si sommano.
        assertThat(o.contributedTotal()).isEqualByComparingTo("3100");
        assertThat(o.contributedThisYear()).isEqualByComparingTo("2900");
        assertThat(o.contributedThisMonth()).isEqualByComparingTo("200");
        assertThat(o.incomeThisYear()).isEqualByComparingTo("42.50");
        assertThat(o.currency()).isEqualTo("EUR");

        // 12 mesi, da novembre 2025: la linea parte dai 200 € di agosto 2025.
        assertThat(o.from()).isEqualTo(YearMonth.of(2025, 11));
        assertThat(o.months()).hasSize(12);
        assertThat(o.months()).extracting(Month::cumulative).extracting(BigDecimal::intValue)
                .containsExactly(200, 200, 400, 600, 1800, 2000, 2200, 2400, 2600, 2800, 2900, 3100);
        assertThat(o.incomeInPeriod()).isEqualByComparingTo("72.50");
        assertThat(o.taxesInPeriod()).isEqualByComparingTo("-2");
        assertThat(o.incomeByType()).extracting(Group::name).containsExactly("Cedole", "Dividendi");
        assertThat(o.incomeByType().get(0).amount()).isEqualByComparingTo("60");

        // Gli ultimi versamenti: solo quelli dai conti normali, dal più recente.
        assertThat(o.recentContributions()).hasSize(InvestmentService.RECENT);
        TransactionDto latest = o.recentContributions().getFirst();
        assertThat(latest.bookingDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(o.recentContributions()).allMatch(t -> t.accountId().equals(ing.getId()));
    }

    @Test
    void everythingStartsFromTheFirstInvestment() {
        Overview o = investmentService.overview(Period.ALL, null, TODAY);

        assertThat(o.from()).isEqualTo(YearMonth.of(2025, 8));
        assertThat(o.months()).hasSize(15);
        assertThat(o.months().getFirst().cumulative()).isEqualByComparingTo("200");
        assertThat(o.contributedInPeriod()).isEqualByComparingTo("3100");
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
