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
import it.wallettracker.category.Category;
import it.wallettracker.category.CategoryRepository;
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

    @Autowired
    CategoryRepository categoryRepository;

    private int counter;

    @AfterEach
    void cleanDatabase() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        connectionRepository.deleteAll();
    }

    @Test
    void transferBetweenTwoNormalAccountsIsInternal() {
        Account main = owned("IT00MAIN", AccountRole.MAIN, "Mario Rossi");
        Account spending = owned("IT00SPEND", AccountRole.SPENDING, "Mario Rossi");
        BankTransaction out = transaction(main, DAY, "-200.00", null, "Bonifico a favore di Mario Rossi");
        BankTransaction in = transaction(spending, DAY.plusDays(1), "200.00", null, "Payment from Mario Rossi");

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
        Account broker = owned("IT00BROKER", AccountRole.INVESTMENT, "ROSSI MARIO");
        Account main = owned("IT00MAIN", AccountRole.MAIN, "Mario Rossi");
        BankTransaction out = transaction(broker, DAY, "-450.00", null, "Mario Rossi Trasferimento");
        transaction(main, DAY, "450.00", null, "Ord: ROSSI MARIO Bonifico ricevuto");

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
        assertThat(reload(dividend).getCategory().getId()).isEqualTo(category("Dividendi").getId());
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
    void theCreditCardSettlementIsRecognisedByTheStarterRule() {
        // La regola di partenza riconosce la causale di ING anche senza il ruolo CREDIT_CARD.
        Account main = account("IT00MAIN", AccountRole.MAIN);
        BankTransaction settlement = transaction(main, DAY, "-1820.11", null, "Estratto conto carta di credito al 20260910");

        classificationService.classifyAll();

        assertThat(typeOf(settlement)).isEqualTo(TransactionType.CARD_SETTLEMENT);
    }

    @Test
    void aPaymentShownAlsoOnTheCardIsPairedAsASettlement() {
        // Banche che mostrano il pagamento anche sulla carta: se i due lati hanno l'aspetto di un
        // trasferimento tra conti propri, l'abbinamento li riconosce come saldo della carta.
        Account main = owned("IT00MAIN", AccountRole.MAIN, "Mario Rossi");
        Account card = owned(null, AccountRole.CREDIT_CARD, "Mario Rossi");
        BankTransaction out = transaction(main, DAY, "-830.00", null, "Pagamento carta a favore di Mario Rossi");
        BankTransaction in = transaction(card, DAY.plusDays(1), "830.00", null, "Pagamento da Mario Rossi");

        classificationService.classifyAll();

        assertThat(typeOf(out)).isEqualTo(TransactionType.CARD_SETTLEMENT);
        assertThat(typeOf(in)).isEqualTo(TransactionType.CARD_SETTLEMENT);
    }

    @Test
    void movementsThatHappenToEqualSumsOfCardPurchasesKeepTheirType() {
        // Regressione: un controllo "per somma" aveva scambiato per saldo della carta una bolletta,
        // un pagamento a un amico e una ritenuta, perché i loro importi coincidevano per caso con la somma
        // di qualche acquisto consecutivo della carta. Il motore non deve mai indovinare così.
        Account main = account("IT00MAIN", AccountRole.MAIN);
        Account card = account(null, AccountRole.CREDIT_CARD);
        Account broker = account("IT00BROKER", AccountRole.INVESTMENT);
        transaction(card, DAY.minusDays(10), "-30.00", null, "BAR");
        transaction(card, DAY.minusDays(9), "-12.68", null, "EDICOLA");
        transaction(card, DAY.minusDays(8), "-37.02", null, "FARMACIA");
        BankTransaction bill = transaction(main, DAY, "-42.68", "Ge.se.sa. Spa", "Ge.se.sa. Spa");
        BankTransaction tax = transaction(broker, DAY, "-49.70", null, "Rit.div.su 708,000 ENI");

        classificationService.classifyAll();

        assertThat(typeOf(bill)).isEqualTo(TransactionType.EXPENSE);
        assertThat(typeOf(tax)).isEqualTo(TransactionType.INVESTMENT_TAX);
    }

    @Test
    void aRefundOnTheCardIsNotPairedWithAnUnrelatedPurchase() {
        Account main = account("IT00MAIN", AccountRole.MAIN);
        Account card = account(null, AccountRole.CREDIT_CARD);
        BankTransaction refund = transaction(card, DAY, "15.16", null, "PAYPAL *TEMU");
        BankTransaction purchase = transaction(main, DAY, "-15.16", null, "LIBRERIA");

        classificationService.classifyAll();

        assertThat(typeOf(refund)).isEqualTo(TransactionType.INCOME);
        assertThat(typeOf(purchase)).isEqualTo(TransactionType.EXPENSE);
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
    void aPurchaseIsNotPairedWithAnUnrelatedIncomeOfTheSameAmount() {
        // Un acquisto da 20 € e, due giorni dopo, 20 € ricevuti da un amico su un altro conto: non è un trasferimento.
        Account card = account("IT00CARD", AccountRole.SPENDING);
        Account spending = account("IT00SPEND", AccountRole.SPENDING);
        BankTransaction purchase = transaction(card, DAY, "-20.00", "V B S R L", "FOGLIANISE ITA");
        BankTransaction fromFriend = transaction(spending, DAY.plusDays(2), "20.00", "LUCA BIANCHI", "Pizza");

        classificationService.classifyAll();

        assertThat(typeOf(purchase)).isEqualTo(TransactionType.EXPENSE);
        assertThat(typeOf(fromFriend)).isEqualTo(TransactionType.INCOME);
    }

    @Test
    void aTransferMentioningTheOwnerNameIsPairedWhateverTheWordOrder() {
        Account main = owned("IT00MAIN", AccountRole.MAIN, "ROSSI MARIO");
        Account spending = owned("IT00SPEND", AccountRole.SPENDING, "Mario Rossi");
        BankTransaction out = transaction(main, DAY, "-300.00", null, "Bonifico a favore di Mario Rossi");
        BankTransaction in = transaction(spending, DAY, "300.00", "MARIO ROSSI", "Payment from Mario Rossi");

        classificationService.classifyAll();

        assertThat(typeOf(out)).isEqualTo(TransactionType.INTERNAL_TRANSFER);
        assertThat(typeOf(in)).isEqualTo(TransactionType.INTERNAL_TRANSFER);
    }

    @Test
    void theNameOfAManualAccountIsNotTakenAsTheOwnerName() {
        // Il nome di un conto manuale lo sceglie l'utente (es. "Carta Esselunga", una carta prepagata): non è il
        // nome dell'intestatario come quello che danno le banche. Se lo usassimo, ogni spesa con quelle parole
        // sembrerebbe un trasferimento verso quel conto.
        Account main = account("IT00MAIN", AccountRole.MAIN);
        Account manual = accountRepository.save(Account.manual("Esselunga", "Carta Esselunga", null, "EUR",
                AccountRole.SPENDING));
        BankTransaction purchase = transaction(main, DAY, "-45.00", "ESSELUNGA SPA", "Pagamento carta Esselunga Milano");
        transaction(manual, DAY, "45.00", null, null);  // senza testo, come in tanti CSV

        classificationService.classifyAll();

        assertThat(typeOf(purchase)).isEqualTo(TransactionType.EXPENSE);
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
    void withoutAnyTextAndNoPairTheTransactionIsToReviewOnAnyAccount() {
        // Come Trade Republic: niente causale né controparte. Il tipo di conto NON basta a decidere
        // (potrebbe essere un acquisto di titoli come un pagamento con carta): lo decide l'utente.
        Account broker = account("IT00BROKER", AccountRole.INVESTMENT);
        Account main = account("IT00MAIN", AccountRole.MAIN);
        BankTransaction onBroker = transaction(broker, DAY, "-150.00", null, null);
        BankTransaction onMain = transaction(main, DAY.plusDays(10), "-30.00", null, null);

        classificationService.classifyAll();

        assertThat(typeOf(onBroker)).isEqualTo(TransactionType.TO_REVIEW);
        assertThat(typeOf(onMain)).isEqualTo(TransactionType.TO_REVIEW);
    }

    @Test
    void aManualCorrectionResolvesATransactionToReview() {
        Account broker = account("IT00BROKER", AccountRole.INVESTMENT);
        BankTransaction unknown = transaction(broker, DAY, "-150.00", null, null);
        unknown.setManualType(TransactionType.SECURITIES_BUY);
        transactionRepository.save(unknown);

        classificationService.classifyAll();

        assertThat(typeOf(unknown)).isEqualTo(TransactionType.SECURITIES_BUY);
    }

    // --- Strumenti per costruire gli scenari ---

    private Account account(String iban, AccountRole role) {
        BankConnection connection = connectionRepository.save(
                new BankConnection("Banca", "IT", "session-" + (++counter), Instant.now().plus(90, ChronoUnit.DAYS)));
        Account account = new Account(connection, "key-" + counter, "uid-" + counter, iban, "Conto " + iban, "EUR");
        account.changeRole(role);
        return accountRepository.save(account);
    }

    /** Un conto con il nome dell'intestatario, come lo restituiscono le banche. */
    private Account owned(String iban, AccountRole role, String ownerName) {
        BankConnection connection = connectionRepository.save(
                new BankConnection("Banca", "IT", "session-" + (++counter), Instant.now().plus(90, ChronoUnit.DAYS)));
        Account account = new Account(connection, "key-" + counter, "uid-" + counter, iban, ownerName, "EUR");
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

    /** Una categoria dell'elenco di partenza, per nome. */
    private Category category(String name) {
        return categoryRepository.findByNameIgnoreCase(name).orElseThrow();
    }
}
