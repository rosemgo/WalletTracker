package it.wallettracker.investment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import it.wallettracker.investment.InvestmentDtos.Group;
import it.wallettracker.investment.InvestmentDtos.GroupKind;
import it.wallettracker.investment.InvestmentDtos.Month;
import it.wallettracker.investment.Investments.Movement;
import it.wallettracker.transaction.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Test "puri" dei calcoli degli investimenti: niente database. */
class InvestmentsTest {

    private static Movement m(String date, String amount, TransactionType type, boolean investmentAccount,
            String category, String source) {
        return new Movement(LocalDate.parse(date), new BigDecimal(amount), type, investmentAccount, category, source);
    }

    /**
     * Un movimento come lo prepara InvestmentService (parte U): il nome del titolo e l'ISIN ricavati dal testo della
     * banca, e il conto. I testi sono quelli veri delle banche, gli importi inventati.
     */
    private static Movement onAccount(long account, String date, String amount, TransactionType type, String category,
            String text) {
        return new Movement(LocalDate.parse(date), new BigDecimal(amount), type, true, category,
                Investments.securityName(text), null, null, account, Investments.isin(text));
    }

    /**
     * La tabella dei casi di docs/investimenti.md, parte I: un movimento per riga, con quanto aggiunge all'investito in
     * titoli e ai bonifici verso i conti di investimento. "inv" dice se il movimento è su un conto di investimento.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            # caso                                                        | importo | tipo                  | inv   | investito | bonifici
            1. bonifico di 200 da ING al broker: il lato di ING           | -200    | INVESTMENT_DEPOSIT    | false | 0         | 200
            1. lo stesso bonifico, il lato del broker                     | 200     | INVESTMENT_DEPOSIT    | true  | 0         | 0
            2. il broker compra un ETF da 200                             | -200    | SECURITIES_BUY        | true  | 200       | 0
            3. acquisto di 200 nel deposito titoli del conto corrente     | -200    | SECURITIES_BUY        | false | 200       | 0
            4. vendita di titoli per 150 sul broker                       | 150     | SECURITIES_SELL       | true  | -150      | 0
            5. 150 tornano dal broker a ING: il lato del broker           | -150    | INVESTMENT_WITHDRAWAL | true  | 0         | 0
            5. lo stesso bonifico, il lato di ING                         | 150     | INVESTMENT_WITHDRAWAL | false | 0         | -150
            6. dividendo di 10 sul broker (va nelle rendite)              | 10      | INVESTMENT_INCOME     | true  | 0         | 0
            7. spesa di 30 con la carta del broker                        | -30     | EXPENSE               | true  | 0         | 0
            8. prelievo di 50 al bancomat dal conto del broker            | -50     | EXPENSE               | true  | 0         | 0
            9. 200 dal broker a un altro conto di investimento            | -200    | INTERNAL_TRANSFER     | true  | 0         | 0
            10. Tinaba: il bonifico da ING                                | -200    | INVESTMENT_DEPOSIT    | false | 0         | 200
            10. Tinaba: "Nuovo versamento Robo advisor"                   | -200    | SECURITIES_BUY        | true  | 200       | 0
            una spesa al supermercato dal conto corrente                  | -40     | EXPENSE               | false | 0         | 0
            """)
    void eachCaseCountsInTheRightPlace(String story, String amount, TransactionType type, boolean investmentAccount,
            String invested, String transferred) {
        Movement movement = m("2026-03-10", amount, type, investmentAccount, null, null);

        assertThat(Investments.invested(movement)).as("investito").isEqualByComparingTo(invested);
        assertThat(Investments.transferred(movement)).as("bonifici").isEqualByComparingTo(transferred);
    }

    @Test
    void purchasesAndSalesAreAlsoSeenApartAndNeverAsIncome() {
        Movement buy = m("2026-03-11", "-200", TransactionType.SECURITIES_BUY, true, null, null);
        Movement sale = m("2026-05-11", "150", TransactionType.SECURITIES_SELL, true, null, null);
        Movement coupon = m("2026-04-04", "15", TransactionType.INVESTMENT_INCOME, false, "Cedole", "BTP");

        assertThat(Investments.bought(buy)).isEqualByComparingTo("200");
        assertThat(Investments.sold(buy)).isEqualByComparingTo("0");
        assertThat(Investments.bought(sale)).isEqualByComparingTo("0");
        assertThat(Investments.sold(sale)).isEqualByComparingTo("150");
        // Una vendita restituisce soprattutto capitale: non è una rendita. Una cedola sì, e non è un acquisto.
        assertThat(Investments.income(sale)).isEqualByComparingTo("0");
        assertThat(Investments.income(coupon)).isEqualByComparingTo("15");
        assertThat(Investments.invested(coupon)).isEqualByComparingTo("0");
        assertThat(Investments.taxes(m("2026-04-05", "-2", TransactionType.INVESTMENT_TAX, true, null, null)))
                .isEqualByComparingTo("-2");
    }

    @Test
    void theLineStartsFromAllTheHistoryBeforeThePeriod() {
        List<Movement> movements = List.of(
                m("2025-01-05", "-500", TransactionType.SECURITIES_BUY, true, null, null),
                m("2026-01-05", "-200", TransactionType.INVESTMENT_DEPOSIT, false, null, null),
                m("2026-01-06", "-200", TransactionType.SECURITIES_BUY, true, null, null),
                m("2026-03-05", "-300", TransactionType.SECURITIES_BUY, true, null, null),
                m("2026-03-15", "100", TransactionType.SECURITIES_SELL, true, null, null),
                m("2026-03-16", "100", TransactionType.INVESTMENT_WITHDRAWAL, false, null, null),
                m("2026-03-20", "10", TransactionType.INVESTMENT_INCOME, false, "Cedole", "BTP"),
                m("2026-03-21", "-1", TransactionType.INVESTMENT_TAX, false, null, null));

        List<Month> months = Investments.months(movements, YearMonth.of(2026, 1), YearMonth.of(2026, 3));

        assertThat(months).extracting(Month::month)
                .containsExactly(YearMonth.of(2026, 1), YearMonth.of(2026, 2), YearMonth.of(2026, 3));
        assertThat(months).extracting(Month::bought).extracting(BigDecimal::intValue).containsExactly(200, 0, 300);
        assertThat(months).extracting(Month::sold).extracting(BigDecimal::intValue).containsExactly(0, 0, 100);
        // Il 2025 è prima del periodo, ma la linea parte da lì: 500, poi 700, 700, 900.
        assertThat(months).extracting(Month::investedCumulative).extracting(BigDecimal::intValue)
                .containsExactly(700, 700, 900);
        // I bonifici: 200 a gennaio, e a marzo 100 tornati indietro.
        assertThat(months).extracting(Month::transferred).extracting(BigDecimal::intValue)
                .containsExactly(200, 0, -100);
        assertThat(months.get(2).income()).isEqualByComparingTo("10");
        assertThat(months.get(2).withholding()).isEqualByComparingTo("0");
        assertThat(months.get(2).otherTaxes()).isEqualByComparingTo("-1"); // senza categoria: non è una ritenuta
    }

    @Test
    void theIncomeIsGroupedFromTheHighest() {
        List<Movement> movements = List.of(
                m("2026-02-01", "15", TransactionType.INVESTMENT_INCOME, false, "Cedole", "BTP Italia"),
                m("2026-08-01", "15", TransactionType.INVESTMENT_INCOME, false, "Cedole", "BTP Italia"),
                m("2026-06-20", "12.50", TransactionType.INVESTMENT_INCOME, true, "Dividendi", "ACME SRL"),
                m("2026-07-01", "0.80", TransactionType.INVESTMENT_INCOME, true, null, null),
                m("2025-06-01", "99", TransactionType.INVESTMENT_INCOME, false, "Cedole", "BTP Italia")); // prima

        List<Group> byType = Investments.incomeBy(movements, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                Movement::category, GroupKind.OTHER_INCOME);

        // La voce senza categoria non ha un nome: dice che voce è, e la frase ("Altre rendite") la scrive la dashboard.
        assertThat(byType).extracting(Group::name).containsExactly("Cedole", "Dividendi", null);
        assertThat(byType).extracting(Group::kind)
                .containsExactly(GroupKind.NAMED, GroupKind.NAMED, GroupKind.OTHER_INCOME);
        assertThat(byType.get(0).amount()).isEqualByComparingTo("30");
        assertThat(byType.get(0).movements()).isEqualTo(2);
    }

    @Test
    void theWithholdingIsTakenFromTheGrossIncomeAndTheOtherTaxesStayApart() {
        // Fineco: la cedola lorda e la ritenuta a parte. Il bollo non riguarda le rendite.
        Movement coupon = m("2026-10-01", "125", TransactionType.INVESTMENT_INCOME, false, "Cedole", "BTP");
        Movement withheld = m("2026-10-01", "-15.63", TransactionType.WITHHOLDING_TAX, false, null, "BTP");
        Movement stamp = m("2026-10-01", "-8.50", TransactionType.INVESTMENT_TAX, false, "Imposta di bollo", null);

        assertThat(Investments.withholding(withheld)).isEqualByComparingTo("-15.63");
        assertThat(Investments.otherTaxes(withheld)).isEqualByComparingTo("0");
        assertThat(Investments.withholding(stamp)).isEqualByComparingTo("0");
        assertThat(Investments.otherTaxes(stamp)).isEqualByComparingTo("-8.50");
        assertThat(Investments.withholding(coupon)).isEqualByComparingTo("0");

        List<Group> others = Investments.otherTaxesBy(List.of(coupon, withheld, stamp,
                m("2026-10-02", "-3", TransactionType.INVESTMENT_TAX, true, null, null)),
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        // Dalla più pesante; le ritenute non ci sono.
        assertThat(others).extracting(Group::name).containsExactly("Imposta di bollo", null);
        assertThat(others).extracting(Group::kind).containsExactly(GroupKind.NAMED, GroupKind.UNCATEGORIZED);
    }

    @Test
    void aWithholdingIsRecognisedByItsTypeAndTheCategoryNameDoesNotMatter() {
        // Parte T: il tipo decide. Una ritenuta resta una ritenuta qualunque categoria abbia (anche nessuna)...
        Movement withheld = m("2026-10-01", "-15.63", TransactionType.WITHHOLDING_TAX, false, "Withholding tax", "BTP");
        // ...e una tassa con una categoria che si chiama "Ritenute" è una tassa come le altre: il nome non conta più.
        Movement tax = m("2026-10-01", "-2", TransactionType.INVESTMENT_TAX, false, "Ritenute", null);

        assertThat(Investments.withholding(withheld)).isEqualByComparingTo("-15.63");
        assertThat(Investments.otherTaxes(withheld)).isEqualByComparingTo("0");
        assertThat(Investments.withholding(tax)).isEqualByComparingTo("0");
        assertThat(Investments.otherTaxes(tax)).isEqualByComparingTo("-2");
        // Tutte le tasse insieme: le ritenute ci sono.
        assertThat(Investments.taxes(withheld)).isEqualByComparingTo("-15.63");
        assertThat(Investments.taxes(tax)).isEqualByComparingTo("-2");
    }

    @Test
    void theNameOfTheSecurityKeepsItsDigitsAndDropsLabelQuantitiesDatesAndCodes() {
        // Il caso trovato con i movimenti veri: prima diventava "Senza nome del titolo".
        assertThat(Investments.securityName("Ced.su 16.000,000 ROMANIA-7OT45 6,5%")).isEqualTo("ROMANIA-7OT45 6,5%");
        assertThat(Investments.securityName("Ced.su 50.000 IT0005436693")).isEqualTo("IT0005436693");
        assertThat(Investments.securityName("Ced.su BTP CUM 15/09/2032")).isEqualTo("BTP CUM");
        assertThat(Investments.securityName("Div.su 100 ENI")).isEqualTo("ENI");
        assertThat(Investments.securityName("Rit.ced 16.000,000 ROMANIA-7OT45 6,5%")).isEqualTo("ROMANIA-7OT45 6,5%");
        // Fineco scrive "Rit.ced.su" e "Rit.div.su" (parte U): prima restava un "su" di troppo.
        assertThat(Investments.securityName("Rit.ced.su 16.000,000 ROMANIA-7OT45 6,5%"))
                .isEqualTo("ROMANIA-7OT45 6,5%");
        assertThat(Investments.securityName("Rit.div.su 708,000 ENI")).isEqualTo("ENI");
        assertThat(Investments.securityName("Cash Dividend ACME")).isEqualTo("ACME");
        assertThat(Investments.securityName("Poste Italiane 0,25")).isEqualTo("Poste Italiane");
        // Niente con delle lettere: null (InvestmentService usa allora il nome del conto).
        assertThat(Investments.securityName(
                "Interest payment for payout collection 019e8191-0af0-76fc-a8ac-1d6ee06c92c0")).isNull();
        assertThat(Investments.securityName("Interest payment for 2026-10")).isNull();
        assertThat(Investments.securityName("Ced.su 50.000")).isNull();
        assertThat(Investments.securityName(null)).isNull();
    }

    @Test
    void theListsAreNetEachWithholdingGoesToTheIncomeOfTheSameSecurity() {
        String bond = "ROMANIA-7OT45 6,5%";
        List<Movement> movements = List.of(
                m("2026-10-20", "520", TransactionType.INVESTMENT_INCOME, false, "Cedole", bond),
                m("2026-10-20", "-65", TransactionType.WITHHOLDING_TAX, false, null, bond),
                m("2026-05-20", "247.91", TransactionType.INVESTMENT_INCOME, true, "Dividendi", "Intesa Sanpaolo"),
                m("2026-06-01", "-4", TransactionType.WITHHOLDING_TAX, false, null, "Titolo sconosciuto"),
                m("2026-07-01", "-2", TransactionType.INVESTMENT_TAX, true, "Imposta di bollo", null)); // non c'entra
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 12, 31);

        List<Group> bySource = Investments.netIncomeBy(movements, from, to, false);
        assertThat(bySource).extracting(Group::name)
                .containsExactly(bond, "Intesa Sanpaolo", null);
        assertThat(bySource.get(2).kind()).isEqualTo(GroupKind.UNMATCHED_WITHHOLDING);
        assertThat(bySource.get(0).amount()).isEqualByComparingTo("455"); // 520 − 65
        assertThat(bySource.get(0).movements()).isEqualTo(1); // la ritenuta non è una rendita in più
        assertThat(bySource.get(2).amount()).isEqualByComparingTo("-4");

        List<Group> byType = Investments.netIncomeBy(movements, from, to, true);
        assertThat(byType).extracting(Group::name)
                .containsExactly("Cedole", "Dividendi", null);
        assertThat(byType.get(2).kind()).isEqualTo(GroupKind.UNMATCHED_WITHHOLDING);
        assertThat(byType.get(0).amount()).isEqualByComparingTo("455");
        // La somma dei gruppi è il netto: 520 − 65 + 247,91 − 4.
        assertThat(byType.stream().map(Group::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("698.91");
    }

    @Test
    void aWithholdingTakesTheDateOfItsIncomeEvenAcrossTwoMonths() {
        // L'esempio dell'utente: la cedola il 30 ottobre, la ritenuta di Fineco il 1° novembre.
        String bond = "ROMANIA-7OT45 6,5%";
        List<Movement> movements = Investments.withholdingsOnTheirIncome(List.of(
                m("2026-10-30", "520", TransactionType.INVESTMENT_INCOME, false, "Cedole", bond),
                m("2026-11-01", "-65", TransactionType.WITHHOLDING_TAX, false, null, bond),
                // un'altra ritenuta dello stesso titolo, troppo lontana: nessuna rendita vicina, tiene la sua data
                m("2026-03-01", "-30", TransactionType.WITHHOLDING_TAX, false, null, bond),
                // il bollo non è una ritenuta: non si sposta
                m("2026-11-01", "-2", TransactionType.INVESTMENT_TAX, false, "Imposta di bollo", bond)));

        assertThat(movements).extracting(Movement::date).containsExactly(LocalDate.of(2026, 10, 30),
                LocalDate.of(2026, 10, 30), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 11, 1));

        // Guardando novembre la ritenuta non c'è più; guardando ottobre la cedola è netta.
        LocalDate octoberFirst = LocalDate.of(2026, 10, 1);
        LocalDate octoberLast = LocalDate.of(2026, 10, 31);
        assertThat(Investments.netIncomeBy(movements, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), false))
                .isEmpty();
        assertThat(Investments.netIncomeBy(movements, octoberFirst, octoberLast, false)).singleElement()
                .satisfies(g -> assertThat(g.amount()).isEqualByComparingTo("455"));
        List<Month> months = Investments.months(movements, YearMonth.of(2026, 10), YearMonth.of(2026, 11));
        assertThat(months.get(0).withholding()).isEqualByComparingTo("-65");
        assertThat(months.get(1).withholding()).isEqualByComparingTo("0");
    }

    @Test
    void twoCouponsOfTheSameBondEachGetTheirOwnWithholding() {
        String bond = "BTP ITALIA";
        List<Movement> movements = Investments.withholdingsOnTheirIncome(List.of(
                m("2026-04-01", "30", TransactionType.INVESTMENT_INCOME, false, "Cedole", bond),
                m("2026-10-01", "30", TransactionType.INVESTMENT_INCOME, false, "Cedole", bond),
                m("2026-10-02", "-3.75", TransactionType.WITHHOLDING_TAX, false, null, bond),
                m("2026-04-03", "-3.75", TransactionType.WITHHOLDING_TAX, false, null, bond),
                // più grande della cedola: non può essere la sua ritenuta
                m("2026-10-01", "-50", TransactionType.WITHHOLDING_TAX, false, null, bond)));

        assertThat(movements).extracting(Movement::date).containsExactly(LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 10, 1));
    }

    @Test
    void theSearchWordsAreTheFixedPartOfTheText() {
        assertThat(Investments.searchWords("Interest payment for payout collection 019e8191-0af0-76fc-a8ac-1d6ee06c92c0"))
                .isEqualTo("interest payment for payout collection");
        assertThat(Investments.searchWords("Interessi Portafoglio 2026 trimestre III ottobre novembre"))
                .isEqualTo("interessi portafoglio trimestre iii ottobre");
        assertThat(Investments.searchWords("2026-10")).isNull();
        assertThat(Investments.searchWords(null)).isNull();
    }

    @Test
    void theIsinIsFoundOnlyWhenItsCheckDigitIsRight() {
        assertThat(Investments.isin("Cash Dividend for ISIN IT0003128367")).isEqualTo("IT0003128367");
        assertThat(Investments.isin("Ced.su 50.000 IT0005436693")).isEqualTo("IT0005436693");
        assertThat(Investments.isin("withholding tax us0378331005 apple")).isEqualTo("US0378331005");
        // una cifra cambiata: la cifra di controllo non torna
        assertThat(Investments.isin("Cash Dividend for ISIN IT0003128368")).isNull();
        // parole di 12 caratteri che sembrano un codice, ma non lo sono
        assertThat(Investments.isin("Ced.su 16.000,000 ROMANIA-7OT45 6,5%")).isNull();
        assertThat(Investments.isin(null)).isNull();
    }

    @Test
    void theRealFinecoWithholdingsGoWithTheirCouponAndDividend() {
        // I testi veri di Fineco: "Rit.ced.su" e "Rit.div.su". Prima nessuna si abbinava, e le righe erano lorde.
        List<Movement> movements = Investments.withholdingsOnTheirIncome(List.of(
                onAccount(1, "2026-10-30", "520", TransactionType.INVESTMENT_INCOME, "Cedole",
                        "Ced.su 16.000,000 ROMANIA-7OT45 6,5%"),
                onAccount(1, "2026-11-02", "-65", TransactionType.WITHHOLDING_TAX, null,
                        "Rit.ced.su 16.000,000 ROMANIA-7OT45 6,5%"),
                onAccount(1, "2026-07-22", "143", TransactionType.INVESTMENT_INCOME, "Dividendi",
                        "Div.su 550,000 ENEL"),
                onAccount(1, "2026-07-22", "-37.18", TransactionType.WITHHOLDING_TAX, null,
                        "Rit.div.su 550,000 ENEL")));

        List<Group> byType = Investments.netIncomeBy(movements, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                true);
        assertThat(byType).extracting(Group::name).containsExactly("Cedole", "Dividendi"); // nessuna non abbinata
        assertThat(byType.get(0).amount()).isEqualByComparingTo("455");
        assertThat(byType.get(1).amount()).isEqualByComparingTo("105.82");
        // la ritenuta della cedola ha preso il giorno della cedola, in ottobre
        assertThat(movements.get(1).date()).isEqualTo(LocalDate.of(2026, 10, 30));
    }

    @Test
    void aWithholdingWithAnotherWordingIsPairedByItsIsin() {
        // Una banca che non conosciamo: le etichette sono diverse, ma l'ISIN è lo stesso.
        List<Movement> movements = Investments.withholdingsOnTheirIncome(List.of(
                onAccount(2, "2026-06-15", "100", TransactionType.INVESTMENT_INCOME, "Dividendi",
                        "Dividend payment ACME CORP US0378331005"),
                onAccount(2, "2026-06-16", "-15", TransactionType.WITHHOLDING_TAX, null,
                        "Withholding tax US0378331005")));

        assertThat(movements.get(1).source()).isEqualTo(movements.get(0).source()); // il titolo della sua rendita
        assertThat(movements.get(1).date()).isEqualTo(LocalDate.of(2026, 6, 15));
        assertThat(Investments.netIncomeBy(movements, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), false))
                .singleElement().satisfies(g -> assertThat(g.amount()).isEqualByComparingTo("85"));
    }

    @Test
    void theOnlyPossibleIncomeIsTheOneEvenWithDifferentWords() {
        // Gli interessi della liquidità di Fineco: la rendita e la ritenuta hanno parole diverse ("Tassaz.", "rit.").
        List<Movement> movements = Investments.withholdingsOnTheirIncome(List.of(
                onAccount(1, "2026-11-02", "0.15", TransactionType.INVESTMENT_INCOME, "Interessi",
                        "Interessi Portaf.Remun. Tassaz. 26,00 %"),
                onAccount(1, "2026-11-02", "-0.04", TransactionType.WITHHOLDING_TAX, null,
                        "Rit. Fisc. Interessi Portaf.Remun. rit. 26,00 %")));

        assertThat(Investments.netIncomeBy(movements, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), true))
                .singleElement().satisfies(g -> {
                    assertThat(g.name()).isEqualTo("Interessi");
                    assertThat(g.amount()).isEqualByComparingTo("0.11");
                });
    }

    @Test
    void withTwoPossibleIncomesOrAnotherAccountNothingIsChosenAtRandom() {
        List<Movement> movements = Investments.withholdingsOnTheirIncome(List.of(
                // due rendite lo stesso giorno sullo stesso conto, nomi diversi dalla ritenuta: quale? non si sa
                onAccount(1, "2026-11-02", "0.15", TransactionType.INVESTMENT_INCOME, "Interessi",
                        "Interessi Portaf.Remun. Tassaz. 26,00 %"),
                onAccount(1, "2026-11-02", "520", TransactionType.INVESTMENT_INCOME, "Cedole",
                        "Ced.su 16.000,000 ROMANIA-7OT45 6,5%"),
                onAccount(1, "2026-11-02", "-0.04", TransactionType.WITHHOLDING_TAX, null,
                        "Rit. Fisc. Interessi Portaf.Remun. rit. 26,00 %"),
                // lo stesso titolo, ma su un altro conto (Trade Republic paga già il netto): non è la sua rendita
                onAccount(3, "2026-07-22", "57.72", TransactionType.INVESTMENT_INCOME, "Dividendi", "Enel"),
                onAccount(1, "2026-07-22", "-37.18", TransactionType.WITHHOLDING_TAX, null,
                        "Rit.div.su 550,000 ENEL")));

        assertThat(movements.get(2).date()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(movements.get(2).source()).isEqualTo("Interessi Portaf.Remun. rit. %"); // il suo, non abbinato
        List<Group> bySource = Investments.netIncomeBy(movements, LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31), false);
        // Trade Republic resta 57,72 € netti: la ritenuta di Fineco ha lo stesso nome, ma è su un altro conto. Le due
        // ritenute senza la loro rendita vanno nella riga a parte, e la somma dei gruppi resta il netto.
        assertThat(bySource).filteredOn(g -> "Enel".equals(g.name())).singleElement()
                .satisfies(g -> assertThat(g.amount()).isEqualByComparingTo("57.72"));
        assertThat(bySource).filteredOn(g -> g.kind() == GroupKind.UNMATCHED_WITHHOLDING).singleElement()
                .satisfies(g -> {
                    assertThat(g.amount()).isEqualByComparingTo("-37.22");
                    assertThat(g.movements()).isEqualTo(2);
                });
        assertThat(bySource.stream().map(Group::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("540.65"); // 0,15 + 520 + 57,72 − 0,04 − 37,18
    }

    @Test
    void theSameSecurityWrittenWithOtherCapitalsIsOneRow() {
        // Fineco scrive "ENEL", Trade Republic "Enel": lo stesso titolo, una riga sola.
        List<Movement> movements = List.of(
                onAccount(1, "2026-01-21", "126.50", TransactionType.INVESTMENT_INCOME, "Dividendi",
                        "Div.su 550,000 ENEL"),
                onAccount(1, "2026-07-22", "143", TransactionType.INVESTMENT_INCOME, "Dividendi",
                        "Div.su 550,000 ENEL"),
                onAccount(3, "2026-07-22", "57.72", TransactionType.INVESTMENT_INCOME, "Dividendi", "Enel"));

        assertThat(Investments.netIncomeBy(movements, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), false))
                .singleElement().satisfies(g -> {
                    assertThat(g.name()).isEqualTo("ENEL");
                    assertThat(g.amount()).isEqualByComparingTo("327.22");
                    assertThat(g.movements()).isEqualTo(3);
                });
    }
}
