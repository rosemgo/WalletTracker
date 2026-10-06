package it.wallettracker.classification;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.account.AccountRole;
import it.wallettracker.connection.BankConnection;
import it.wallettracker.connection.BankConnectionRepository;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.TransactionStatus;
import it.wallettracker.transaction.TransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Test del motore di classificazione con scenari volutamente <b>diversi</b> dalle abitudini di una persona:
 * spese da un conto di investimento, dividendi su un conto di spesa, utenti senza conti di investimento...
 * Le regole usate sono quelle di partenza, create dalla migrazione V4.
 */
@SpringBootTest
@Import(IntegrationTestConfiguration.class)
class ClassificationServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);

    @Autowired
    ClassificationService classificationService;

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
    void transferBetweenTwoNormalAccountsIsInternal() {
        Account main = account("IT00MAIN", AccountRole.MAIN);
        Account spending = account("IT00SPEND", AccountRole.SPENDING);
        BankTransaction out = transaction(main, DAY, "-200.00", null, "Bonifico");
        BankTransaction in = transaction(spending, DAY.plusDays(1), "200.00", null, "Payment");

        classificationService.classifyAll();

        assertThat(typeOf(out)).isEqualTo(TransactionType.INTERNAL_TRANSFER);
        assertThat(typeOf(in)).isEqualTo(TransactionType.INTERNAL_TRANSFER);
        assertThat(reload(out).getTransferPeerId()).isEqualTo(in.getId());
    }

    @Test
    void transferToAnInvestmentAccountIsADepositEvenWithoutDescriptions() {
        // Come Trade Republic: nessuna descrizione, solo importo e data. Basta l'abbinamento.
        Account main = account("IT00MAIN", AccountRole.MAIN);
        Account broker = account("IT00BROKER", AccountRole.INVESTMENT);
        BankTransaction out = transaction(main, DAY, "-2000.00", null, null);
        BankTransaction in = transaction(broker, DAY, "2000.00", null, null);

        classificationService.classifyAll();

        assertThat(typeOf(out)).isEqualTo(TransactionType.INVESTMENT_DEPOSIT);
        assertThat(typeOf(in)).isEqualTo(TransactionType.INVESTMENT_DEPOSIT);
    }

    @Test
    void transferFromAnInvestmentAccountIsAWithdrawal() {
        Account broker = account("IT00BROKER", AccountRole.INVESTMENT);
        Account main = account("IT00MAIN", AccountRole.MAIN);
        BankTransaction out = transaction(broker, DAY, "-450.00", null, "Trasferimento");
        transaction(main, DAY, "450.00", null, "Bonifico ricevuto");

        classificationService.classifyAll();

        assertThat(typeOf(out)).isEqualTo(TransactionType.INVESTMENT_WITHDRAWAL);
    }

    @Test
    void aPurchaseFromAnInvestmentAccountIsStillAnExpense() {
        Account broker = account("IT00BROKER", AccountRole.INVESTMENT);
        BankTransaction groceries = transaction(broker, DAY, "-45.30", "CONAD", "PAGAMENTO POS");

        classificationService.classifyAll();

        assertThat(typeOf(groceries)).isEqualTo(TransactionType.EXPENSE);
    }

    @Test
    void aDividendOnASpendingAccountIsInvestmentIncome() {
        Account spending = account("IT00SPEND", AccountRole.SPENDING);
        BankTransaction dividend = transaction(spending, DAY, "12.00", null, "Div.su 10,000 ENI");

        classificationService.classifyAll();

        assertThat(typeOf(dividend)).isEqualTo(TransactionType.INVESTMENT_INCOME);
        assertThat(reload(dividend).getCategory()).isEqualTo("Dividendi");
    }

    @Test
    void theIbanOfAnotherOwnAccountInTheDescriptionMeansTransfer() {
        // L'altro lato non è stato importato, ma la causale cita l'IBAN di un tuo conto (anche con spazi).
        Account main = account("IT00MAIN", AccountRole.MAIN);
        account("IT81G0302501601", AccountRole.SPENDING);
        BankTransaction out = transaction(main, DAY, "-200.00", null,
                "Bonifico a favore di Mario Rossi IBAN beneficiario IT81 G030 2501 601");

        classificationService.classifyAll();

        assertThat(typeOf(out)).isEqualTo(TransactionType.INTERNAL_TRANSFER);
    }

    @Test
    void theCreditCardMonthlySettlementIsAnInternalTransfer() {
        Account main = account("IT00MAIN", AccountRole.MAIN);
        BankTransaction settlement = transaction(main, DAY, "-1820.11", null, "Estratto conto carta di credito al 20260910");

        classificationService.classifyAll();

        assertThat(typeOf(settlement)).isEqualTo(TransactionType.INTERNAL_TRANSFER);
    }

    @Test
    void aManualCorrectionAlwaysWins() {
        Account main = account("IT00MAIN", AccountRole.MAIN);
        BankTransaction loan = transaction(main, DAY, "15000.00", "AMICO", "Prestito");
        loan.setManualType(TransactionType.IGNORED);
        transactionRepository.save(loan);

        classificationService.classifyAll();

        assertThat(typeOf(loan)).isEqualTo(TransactionType.IGNORED);
    }

    @Test
    void zeroAmountsAndExcludedAccountsAreIgnored() {
        Account main = account("IT00MAIN", AccountRole.MAIN);
        Account excluded = account("IT00TEST", AccountRole.EXCLUDED);
        BankTransaction cardCheck = transaction(main, DAY, "0.00", "AMAZON", null);
        BankTransaction onExcluded = transaction(excluded, DAY, "-10.00", "BAR", null);

        classificationService.classifyAll();

        assertThat(typeOf(cardCheck)).isEqualTo(TransactionType.IGNORED);
        assertThat(typeOf(onExcluded)).isEqualTo(TransactionType.IGNORED);
    }

    @Test
    void withoutAnyMatchOutgoingIsExpenseAndIncomingIsIncome() {
        Account main = account("IT00MAIN", AccountRole.MAIN);
        BankTransaction rent = transaction(main, DAY, "-700.00", "MARIO ROSSI", "Affitto ottobre");
        BankTransaction gift = transaction(main, DAY.plusDays(10), "50.00", "LUCA BIANCHI", "Regalo");

        classificationService.classifyAll();

        assertThat(typeOf(rent)).isEqualTo(TransactionType.EXPENSE);
        assertThat(typeOf(gift)).isEqualTo(TransactionType.INCOME);
    }

    @Test
    void transactionsTooFarApartAreNotPaired() {
        Account main = account("IT00MAIN", AccountRole.MAIN);
        Account spending = account("IT00SPEND", AccountRole.SPENDING);
        BankTransaction out = transaction(main, DAY, "-100.00", "NEGOZIO", null);
        transaction(spending, DAY.plusDays(ClassificationService.TRANSFER_MAX_DAYS_APART + 1), "100.00", "RIMBORSO", null);

        classificationService.classifyAll();

        assertThat(typeOf(out)).isEqualTo(TransactionType.EXPENSE);
    }

    @Test
    void withoutAnyTextOnAnInvestmentAccountTheAccountPurposeDecides() {
        // Come Trade Republic: niente causale né controparte. Uscita = acquisto titoli, entrata = rendita.
        Account broker = account("IT00BROKER", AccountRole.INVESTMENT);
        BankTransaction buy = transaction(broker, DAY, "-150.00", null, null);
        BankTransaction dividend = transaction(broker, DAY.plusDays(10), "42.18", null, null);

        classificationService.classifyAll();

        assertThat(typeOf(buy)).isEqualTo(TransactionType.SECURITIES_BUY);
        assertThat(typeOf(dividend)).isEqualTo(TransactionType.INVESTMENT_INCOME);
    }

    @Test
    void withoutAnyTextOnANormalAccountTheGenericFallbackApplies() {
        Account main = account("IT00MAIN", AccountRole.MAIN);
        BankTransaction unknown = transaction(main, DAY, "-30.00", null, null);

        classificationService.classifyAll();

        assertThat(typeOf(unknown)).isEqualTo(TransactionType.EXPENSE);
    }

    // --- Strumenti per costruire gli scenari ---

    private Account account(String iban, AccountRole role) {
        BankConnection connection = connectionRepository.save(
                new BankConnection("Banca", "IT", "session-" + (++counter), Instant.now().plus(90, ChronoUnit.DAYS)));
        Account account = new Account(connection, "key-" + counter, "uid-" + counter, iban, "Conto " + iban, "EUR");
        account.changeRole(role);
        return accountRepository.save(account);
    }

    private BankTransaction transaction(Account account, LocalDate date, String amount, String counterparty,
            String description) {
        return transactionRepository.save(new BankTransaction(account, "tx-" + (++counter), TransactionStatus.BOOKED,
                date, null, new BigDecimal(amount), "EUR", counterparty, description, "{}"));
    }

    private BankTransaction reload(BankTransaction transaction) {
        return transactionRepository.findById(transaction.getId()).orElseThrow();
    }

    private TransactionType typeOf(BankTransaction transaction) {
        return reload(transaction).getType();
    }
}
