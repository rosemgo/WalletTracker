package it.wallettracker.investment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import it.wallettracker.investment.InvestmentDtos.Group;
import it.wallettracker.investment.InvestmentDtos.Month;
import it.wallettracker.investment.Investments.Movement;
import it.wallettracker.transaction.TransactionType;
import org.junit.jupiter.api.Test;

/** Test "puri" dei calcoli degli investimenti: niente database. */
class InvestmentsTest {

    private static Movement m(String date, String amount, TransactionType type, boolean investmentAccount,
            String category, String source) {
        return new Movement(LocalDate.parse(date), new BigDecimal(amount), type, investmentAccount, category, source);
    }

    @Test
    void onlyWhatLeavesAnOrdinaryAccountCountsSoNothingIsCountedTwice() {
        // 200 € da ING a Trade Republic e poi in un ETF: tre movimenti, gli stessi soldi.
        Movement out = m("2026-03-10", "-200", TransactionType.INVESTMENT_DEPOSIT, false, null, null);
        Movement in = m("2026-03-10", "200", TransactionType.INVESTMENT_DEPOSIT, true, null, null);
        Movement buy = m("2026-03-11", "-200", TransactionType.SECURITIES_BUY, true, null, null);

        assertThat(Investments.contribution(out)).isEqualByComparingTo("200");
        assertThat(Investments.contribution(in)).isEqualByComparingTo("0");
        assertThat(Investments.contribution(buy)).isEqualByComparingTo("0");
        // Un prelievo che torna sul conto corrente toglie; un acquisto dal dossier titoli della banca conta.
        assertThat(Investments.contribution(m("2026-04-01", "50", TransactionType.INVESTMENT_WITHDRAWAL, false, null,
                null))).isEqualByComparingTo("-50");
        assertThat(Investments.contribution(m("2026-04-02", "-1000", TransactionType.SECURITIES_BUY, false, null,
                null))).isEqualByComparingTo("1000");
        // Una spesa non è un investimento; una cedola è una rendita, non un versamento.
        assertThat(Investments.contribution(m("2026-04-03", "-30", TransactionType.EXPENSE, false, null, null)))
                .isEqualByComparingTo("0");
        Movement coupon = m("2026-04-04", "15", TransactionType.INVESTMENT_INCOME, false, "Cedole", "BTP");
        assertThat(Investments.contribution(coupon)).isEqualByComparingTo("0");
        assertThat(Investments.income(coupon)).isEqualByComparingTo("15");
        assertThat(Investments.taxes(m("2026-04-05", "-2", TransactionType.INVESTMENT_TAX, true, null, null)))
                .isEqualByComparingTo("-2");
    }

    @Test
    void theLineStartsFromAllTheHistoryBeforeThePeriod() {
        List<Movement> movements = List.of(
                m("2025-01-05", "-500", TransactionType.INVESTMENT_DEPOSIT, false, null, null),
                m("2026-01-05", "-200", TransactionType.INVESTMENT_DEPOSIT, false, null, null),
                m("2026-03-05", "-200", TransactionType.INVESTMENT_DEPOSIT, false, null, null),
                m("2026-03-20", "10", TransactionType.INVESTMENT_INCOME, false, "Cedole", "BTP"),
                m("2026-03-21", "-1", TransactionType.INVESTMENT_TAX, false, null, null));

        List<Month> months = Investments.months(movements, YearMonth.of(2026, 1), YearMonth.of(2026, 3));

        assertThat(months).extracting(Month::month)
                .containsExactly(YearMonth.of(2026, 1), YearMonth.of(2026, 2), YearMonth.of(2026, 3));
        assertThat(months).extracting(Month::contributed).extracting(BigDecimal::intValue).containsExactly(200, 0, 200);
        // Il 2025 è prima del periodo, ma la linea parte da lì: 500, poi 700, 700, 900.
        assertThat(months).extracting(Month::cumulative).extracting(BigDecimal::intValue)
                .containsExactly(700, 700, 900);
        assertThat(months.get(2).income()).isEqualByComparingTo("10");
        assertThat(months.get(2).taxes()).isEqualByComparingTo("-1");
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
                Movement::category, "Altre rendite");

        assertThat(byType).extracting(Group::name).containsExactly("Cedole", "Dividendi", "Altre rendite");
        assertThat(byType.get(0).amount()).isEqualByComparingTo("30");
        assertThat(byType.get(0).movements()).isEqualTo(2);
    }
}
