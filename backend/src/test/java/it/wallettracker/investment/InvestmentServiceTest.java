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
import it.wallettracker.investment.InvestmentDtos.GroupKind;
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
        // Il bollo non è una ritenuta: sta tra le altre tasse, e le rendite restano lorde = nette.
        assertThat(o.withholdingInPeriod()).isEqualByComparingTo("0");
        assertThat(o.otherTaxesInPeriod()).isEqualByComparingTo("-2");
        assertThat(o.otherTaxesByType()).extracting(Group::kind).containsExactly(GroupKind.UNCATEGORIZED);
        // "Da dove": il nome del titolo, senza l'etichetta iniziale.
        assertThat(o.incomeBySource()).extracting(Group::name).containsExactly("BTP ITALIA", "ACME");
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
    void aDossierThatReceivedItsSecuritiesFromAnotherAccountIsNotOnlyTransfers() {
        // Come il secondo dossier Fineco: i titoli arrivano dall'altro conto (giro titoli, trasferimento interno),
        // poi cedole con la loro ritenuta, il bollo e dei prelievi verso il conto corrente. Nessun acquisto, ma i
        // movimenti ci sono: niente avviso (parte X).
        Account dossier = accountRepository.save(Account.manual("Banca Gamma", "Dossier", null, "EUR",
                AccountRole.INVESTMENT));
        save(dossier, "2026-03-25", "60.80", TransactionType.INTERNAL_TRANSFER,
                "Ritenuta Fiscale ROMANIA-7OT45 6,5% Qta/Val.nom. 16000,000000", null);
        save(dossier, "2026-04-01", "1237.50", TransactionType.INVESTMENT_INCOME, "Ced.su 55.000,000 BTP-1OT53 4,5%",
                "Cedole");
        save(dossier, "2026-04-01", "-154.69", TransactionType.WITHHOLDING_TAX,
                "Rit.ced.su 55.000,000 BTP-1OT53 4,5%", null);
        save(dossier, "2026-05-20", "-3000", TransactionType.INVESTMENT_WITHDRAWAL, "Trasferimento a mio conto", null);
        // Un altro conto con un prelievo e solo il bollo: anche il bollo dice che i titoli ci sono.
        Account stamped = accountRepository.save(Account.manual("Banca Delta", "Dossier", null, "EUR",
                AccountRole.INVESTMENT));
        save(stamped, "2026-04-09", "-22.10", TransactionType.INVESTMENT_TAX, "Addebito imposta di bollo Dossier", null);
        save(stamped, "2026-05-20", "-500", TransactionType.INVESTMENT_WITHDRAWAL, "Trasferimento a mio conto", null);
        classificationService.classifyAll();

        Overview o = investmentService.overview(Period.YEAR, null, TODAY);

        // Resta solo Fondo Beta, che ha soltanto un versamento.
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
    void theGrossIncomeTheWithholdingAndTheNetAreSeenApart() {
        // Una cedola lorda di Fineco con la sua ritenuta a parte (tipo Ritenute, senza categoria: parte T).
        save(ing, "2026-10-01", "125", TransactionType.INVESTMENT_INCOME, "Ced.su 50.000 IT0005436693", "Cedole");
        save(ing, "2026-10-01", "-15.63", TransactionType.WITHHOLDING_TAX, "Rit.ced IT0005436693", null);
        classificationService.classifyAll(); // il tipo scelto a mano diventa il tipo del movimento

        Overview o = investmentService.overview(Period.YEAR, null, TODAY);

        assertThat(o.incomeInPeriod()).isEqualByComparingTo("197.50"); // 72,50 + 125, lordo
        assertThat(o.withholdingInPeriod()).isEqualByComparingTo("-15.63");
        // La scheda del Riepilogo, come la pagina: netto. 12,50 + 30 di quest'anno, più la cedola di Fineco tolta la
        // ritenuta (125 - 15,63).
        assertThat(o.incomeThisYear()).isEqualByComparingTo("151.87");
        assertThat(o.otherTaxesInPeriod()).isEqualByComparingTo("-2");
        assertThat(o.months().getLast().income()).isEqualByComparingTo("125");
        assertThat(o.months().getLast().withholding()).isEqualByComparingTo("-15.63");
        // Il BTP è indicato solo dall'ISIN: è il suo nome in "Da dove", non "Ced.su"; e la cifra è netta.
        assertThat(o.incomeBySource()).filteredOn(g -> "IT0005436693".equals(g.name())).singleElement()
                .satisfies(g -> assertThat(g.amount()).isEqualByComparingTo("109.37"));
    }

    @Test
    void theRealFinecoWithholdingIsTakenFromItsCouponEvenTheDayAfter() {
        // Parte U: "Rit.ced.su" (il testo vero di Fineco) il giorno dopo la sua cedola. Prima non si abbinava.
        save(ing, "2026-09-30", "520", TransactionType.INVESTMENT_INCOME, "Ced.su 16.000,000 ROMANIA-7OT45 6,5%",
                "Cedole");
        save(ing, "2026-10-01", "-65", TransactionType.WITHHOLDING_TAX, "Rit.ced.su 16.000,000 ROMANIA-7OT45 6,5%",
                null);
        classificationService.classifyAll();

        Overview o = investmentService.overview(Period.YEAR, null, TODAY);

        assertThat(o.incomeBySource()).filteredOn(g -> "ROMANIA-7OT45 6,5%".equals(g.name())).singleElement()
                .satisfies(g -> assertThat(g.amount()).isEqualByComparingTo("455"));
        assertThat(o.incomeByType()).extracting(Group::kind).doesNotContain(GroupKind.UNMATCHED_WITHHOLDING);
        // la ritenuta sta a settembre, con la sua cedola
        assertThat(o.months()).filteredOn(m -> m.month().equals(YearMonth.of(2026, 9))).singleElement()
                .satisfies(m -> assertThat(m.withholding()).isEqualByComparingTo("-65"));
    }

    @Test
    void fromWhereUsesTheWholeNameOfTheBondOrTheAccount() {
        save(ing, "2026-10-02", "520", TransactionType.INVESTMENT_INCOME, "Ced.su 16.000,000 ROMANIA-7OT45 6,5%",
                "Cedole");
        save(broker, "2026-10-03", "6.37", TransactionType.INVESTMENT_INCOME,
                "Interest payment for payout collection 019e8191-0af0-76fc-a8ac-1d6ee06c92c0", "Interessi");
        save(broker, "2026-09-01", "5.90", TransactionType.INVESTMENT_INCOME,
                "Interest payment for payout collection 019de1d4-6643-7b21-8583-053fbf22f842", "Interessi");
        classificationService.classifyAll();

        Overview o = investmentService.overview(Period.YEAR, null, TODAY);

        assertThat(o.incomeBySource()).extracting(Group::name).contains("ROMANIA-7OT45 6,5%", broker.label());
        assertThat(o.incomeBySource()).extracting(Group::kind).doesNotContain(GroupKind.UNNAMED_SECURITY);
        // Gli interessi, con un codice diverso ogni mese, stanno tutti sotto il conto.
        assertThat(o.incomeBySource()).filteredOn(g -> g.name().equals(broker.label()))
                .singleElement().satisfies(g -> {
                    assertThat(g.movements()).isEqualTo(2);
                    // ...e dicono quale conto è, per aprirne i movimenti; un titolo no.
                    assertThat(g.accountId()).isEqualTo(broker.getId());
                    assertThat(g.search()).isEqualTo("interest payment for payout collection");
                });
        assertThat(o.incomeBySource()).filteredOn(g -> "ROMANIA-7OT45 6,5%".equals(g.name())).singleElement()
                .satisfies(g -> assertThat(g.accountId()).isNull());
    }

    @Test
    void theYearToDateStartsInJanuary() {
        Overview o = investmentService.overview(Period.YEAR_TO_DATE, null, TODAY);

        // Da gennaio a ottobre 2026: dieci mesi; l'investito di prima (nessuno) e i bonifici del 2025 restano fuori.
        assertThat(o.from()).isEqualTo(YearMonth.of(2026, 1));
        assertThat(o.months()).hasSize(10);
        assertThat(o.transferredInPeriod()).isEqualByComparingTo("2900");
        assertThat(mvc.get().uri("/api/investments?period=YEAR_TO_DATE")).hasStatusOk();
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
