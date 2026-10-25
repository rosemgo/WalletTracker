package it.wallettracker.overview;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import it.wallettracker.overview.MonthCurves.Expense;
import org.junit.jupiter.api.Test;

/** I calcoli del Riepilogo, con dati scritti a mano: niente database, niente Spring. */
class MonthCurvesTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9); // 30 giorni
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10); // 31 giorni

    @Test
    void theCurveAddsUpDayByDay() {
        List<Expense> expenses = List.of(
                expense(2026, 10, 2, 1L, "10.00"),
                expense(2026, 10, 2, 2L, "5.00"),
                expense(2026, 10, 4, 1L, "20.00"),
                expense(2026, 10, 4, 1L, "-3.00"), // un rimborso: riduce
                expense(2026, 9, 30, 1L, "99.00")); // un altro mese: non conta

        assertThat(MonthCurves.cumulative(expenses, OCTOBER, 5))
                .containsExactly(amount("0"), amount("15.00"), amount("15.00"), amount("32.00"), amount("32.00"));
    }

    @Test
    void theUsualIsTheAverageAtTheSameDayAndAShortMonthCountsWholeOnTheLastDays() {
        List<Expense> expenses = List.of(
                expense(2026, 8, 10, 1L, "100.00"),
                expense(2026, 8, 31, 1L, "60.00"),
                expense(2026, 9, 10, 1L, "200.00"),
                expense(2026, 9, 30, 1L, "40.00"));
        List<YearMonth> months = List.of(YearMonth.of(2026, 8), SEPTEMBER);

        assertThat(MonthCurves.usualUpTo(expenses, months, 9)).isEqualByComparingTo("0");
        assertThat(MonthCurves.usualUpTo(expenses, months, 10)).isEqualByComparingTo("150.00"); // (100 + 200) / 2
        assertThat(MonthCurves.usualUpTo(expenses, months, 30)).isEqualByComparingTo("170.00"); // (100 + 240) / 2
        // Il 31: agosto ha la spesa del 31, settembre (30 giorni) è già intero.
        assertThat(MonthCurves.usualUpTo(expenses, months, 31)).isEqualByComparingTo("200.00"); // (160 + 240) / 2
        assertThat(MonthCurves.typical(expenses, months, 31)).hasSize(31).last().isEqualTo(amount("200.00"));
    }

    @Test
    void withoutMonthsThereIsNoUsual() {
        List<Expense> expenses = List.of(expense(2026, 10, 1, 1L, "10.00"));

        assertThat(MonthCurves.usualUpTo(expenses, List.of(), 10)).isNull();
        assertThat(MonthCurves.typical(expenses, List.of(), 31)).isEmpty();
    }

    @Test
    void theAverageIsRoundedToTwoDecimals() {
        List<Expense> expenses = List.of(expense(2026, 9, 1, 1L, "10.00"));
        List<YearMonth> months = List.of(YearMonth.of(2026, 7), YearMonth.of(2026, 8), SEPTEMBER);

        assertThat(MonthCurves.usualUpTo(expenses, months, 30)).isEqualTo(amount("3.33")); // 10 / 3
    }

    @Test
    void onlyMonthsKnownFromTheirFirstDayMakeTheUsual() {
        // La storia comincia il 10 agosto: agosto è a metà, quindi si parte da settembre.
        assertThat(MonthCurves.usualMonths(OCTOBER, 6, LocalDate.of(2026, 8, 10)))
                .containsExactly(SEPTEMBER);
        // Dal primo agosto: agosto conta.
        assertThat(MonthCurves.usualMonths(OCTOBER, 6, LocalDate.of(2026, 8, 1)))
                .containsExactly(YearMonth.of(2026, 8), SEPTEMBER);
        // Una storia lunga: al massimo sei mesi, quelli subito prima.
        assertThat(MonthCurves.usualMonths(OCTOBER, 6, LocalDate.of(2020, 1, 1)))
                .containsExactly(YearMonth.of(2026, 4), YearMonth.of(2026, 5), YearMonth.of(2026, 6),
                        YearMonth.of(2026, 7), YearMonth.of(2026, 8), SEPTEMBER);
        // Nessun movimento, o storia cominciata questo mese: nessun solito.
        assertThat(MonthCurves.usualMonths(OCTOBER, 6, null)).isEmpty();
        assertThat(MonthCurves.usualMonths(OCTOBER, 6, LocalDate.of(2026, 10, 1))).isEmpty();
    }

    @Test
    void theExpensesOfACategoryIncludeThoseWithoutCategoryWhenAskedForNull() {
        List<Expense> expenses = List.of(expense(2026, 10, 1, 1L, "1.00"), expense(2026, 10, 1, null, "2.00"));

        assertThat(MonthCurves.ofCategory(expenses, null)).extracting(Expense::amount).containsExactly(amount("2.00"));
        assertThat(MonthCurves.ofCategory(expenses, 1L)).extracting(Expense::amount).containsExactly(amount("1.00"));
    }

    private static Expense expense(int year, int month, int day, Long categoryId, String amount) {
        return new Expense(LocalDate.of(year, month, day), categoryId, categoryId == null ? null : "Cat " + categoryId,
                new BigDecimal(amount), 1);
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
