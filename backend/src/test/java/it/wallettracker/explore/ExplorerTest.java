package it.wallettracker.explore;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import it.wallettracker.explore.ExploreDtos.Bar;
import it.wallettracker.explore.ExploreDtos.By;
import it.wallettracker.explore.ExploreDtos.Group;
import it.wallettracker.explore.ExploreDtos.Metric;
import it.wallettracker.explore.ExploreDtos.Period;
import it.wallettracker.explore.ExploreDtos.Unit;
import it.wallettracker.explore.Explorer.Movement;
import it.wallettracker.transaction.TransactionType;
import org.junit.jupiter.api.Test;

/** I calcoli dell'esploratore, con dati scritti a mano: niente database, niente Spring. */
class ExplorerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25); // domenica

    @Test
    void theBarsOfEachPeriod() {
        // Una settimana: da lunedì 19 a domenica 25.
        List<Bar> week = Explorer.bars(Period.WEEK, TODAY, TODAY);
        assertThat(week).hasSize(7);
        assertThat(week.getFirst().from()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(week.getLast().to()).isEqualTo(TODAY);

        // Un mese: blocchi di sette giorni dal primo; l'ultimo (29-31) non è ancora cominciato.
        List<Bar> month = Explorer.bars(Period.MONTH, TODAY, TODAY);
        assertThat(month).extracting(Bar::from).extracting(LocalDate::getDayOfMonth).containsExactly(1, 8, 15, 22, 29);
        assertThat(month.getLast().to()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(month).extracting(Bar::future).containsExactly(false, false, false, false, true);
        // Febbraio di un anno non bisestile: quattro settimane esatte.
        assertThat(Explorer.bars(Period.MONTH, LocalDate.of(2026, 2, 10), TODAY)).hasSize(4);

        // Un anno: i 12 mesi fino a ottobre.
        List<Bar> year = Explorer.bars(Period.YEAR, TODAY, TODAY);
        assertThat(year).hasSize(12);
        assertThat(year.getFirst().from()).isEqualTo(LocalDate.of(2025, 11, 1));
        assertThat(Explorer.bars(Period.SIX_MONTHS, TODAY, TODAY).getFirst().from())
                .isEqualTo(LocalDate.of(2026, 5, 1));
    }

    @Test
    void expensesIncomeAndNet() {
        List<Movement> movements = List.of(
                expense(2026, 10, 3, 1L, "-100.00"),
                expense(2026, 10, 4, 1L, "20.00"), // un rimborso: riduce le spese
                expense(2026, 10, 5, 2L, "-30.00"),
                income(2026, 10, 23, "2000.00"),
                new Movement(LocalDate.of(2026, 10, 6), new BigDecimal("-500.00"), TransactionType.INTERNAL_TRANSFER,
                        null, null, 1L, "Conto", "GIROCONTO", "GIROCONTO"));
        LocalDate from = LocalDate.of(2026, 10, 1);

        assertThat(Explorer.value(movements, Metric.EXPENSES, from, TODAY, null)).isEqualByComparingTo("110.00");
        assertThat(Explorer.value(movements, Metric.EXPENSES, from, TODAY, 1L)).isEqualByComparingTo("80.00");
        assertThat(Explorer.value(movements, Metric.INCOME, from, TODAY, null)).isEqualByComparingTo("2000.00");
        assertThat(Explorer.value(movements, Metric.NET, from, TODAY, null)).isEqualByComparingTo("1890.00");
        // Il periodo conta: fino al 4 ottobre.
        assertThat(Explorer.value(movements, Metric.EXPENSES, from, LocalDate.of(2026, 10, 4), null))
                .isEqualByComparingTo("80.00");
    }

    @Test
    void theAveragePerUnitComesFromTheUsualMonths() {
        // Agosto (31 giorni) 310 €, settembre (30 giorni) 300 €: 610 € in 61 giorni = 10 € al giorno.
        List<Movement> movements = List.of(expense(2026, 8, 10, 1L, "-310.00"), expense(2026, 9, 10, 1L, "-300.00"));
        List<YearMonth> months = List.of(YearMonth.of(2026, 8), YearMonth.of(2026, 9));

        assertThat(Explorer.averagePerUnit(movements, Metric.EXPENSES, null, months, Unit.DAY))
                .isEqualByComparingTo("10.00");
        assertThat(Explorer.averagePerUnit(movements, Metric.EXPENSES, null, months, Unit.WEEK))
                .isEqualByComparingTo("70.00");
        assertThat(Explorer.averagePerUnit(movements, Metric.EXPENSES, null, months, Unit.MONTH))
                .isEqualByComparingTo("305.00");
        assertThat(Explorer.averagePerUnit(movements, Metric.EXPENSES, null, List.of(), Unit.MONTH)).isNull();
    }

    @Test
    void groupsByCategoryMerchantAndAccount() {
        List<Movement> movements = List.of(
                new Movement(LocalDate.of(2026, 10, 3), new BigDecimal("-60.00"), TransactionType.EXPENSE, 1L,
                        "Spesa alimentare", 1L, "Conto corrente", "ESSELUNGA", "ESSELUNGA MILANO"),
                new Movement(LocalDate.of(2026, 10, 9), new BigDecimal("-40.00"), TransactionType.EXPENSE, 1L,
                        "Spesa alimentare", 2L, "Carta di credito", "ESSELUNGA", "ESSELUNGA Milano centro"),
                new Movement(LocalDate.of(2026, 10, 5), new BigDecimal("-70.00"), TransactionType.EXPENSE, 2L,
                        "Bar e ristoranti", 2L, "Carta di credito", "PIZZERIA", "PIZZERIA DA GINO"),
                // settembre: solo per il solito
                new Movement(LocalDate.of(2026, 9, 3), new BigDecimal("-50.00"), TransactionType.EXPENSE, 1L,
                        "Spesa alimentare", 1L, "Conto corrente", "ESSELUNGA", "ESSELUNGA MILANO"));
        LocalDate from = LocalDate.of(2026, 10, 1);

        List<Group> categories = Explorer.groups(movements, Metric.EXPENSES, from, TODAY, By.CATEGORY, null,
                List.of(YearMonth.of(2026, 9)), 25);
        assertThat(categories).extracting(Group::name).containsExactly("Spesa alimentare", "Bar e ristoranti");
        assertThat(categories.getFirst().amount()).isEqualByComparingTo("100.00");
        assertThat(categories.getFirst().movements()).isEqualTo(2);
        assertThat(categories.getFirst().categoryId()).isEqualTo(1L);
        assertThat(categories.getFirst().usual()).isEqualByComparingTo("50.00");
        assertThat(categories.get(1).usual()).isEqualByComparingTo("0");

        // Per esercente: il nome è quello del movimento più recente.
        List<Group> merchants = Explorer.groups(movements, Metric.EXPENSES, from, TODAY, By.MERCHANT, null,
                List.of(), 25);
        assertThat(merchants).extracting(Group::name).containsExactly("ESSELUNGA Milano centro", "PIZZERIA DA GINO");
        assertThat(merchants.getFirst().usual()).isNull();

        List<Group> accounts = Explorer.groups(movements, Metric.EXPENSES, from, TODAY, By.ACCOUNT, null, List.of(),
                25);
        assertThat(accounts).extracting(Group::name).containsExactly("Carta di credito", "Conto corrente");
        assertThat(accounts.getFirst().amount()).isEqualByComparingTo("110.00");

        // Con una categoria scelta, solo i suoi movimenti.
        assertThat(Explorer.groups(movements, Metric.EXPENSES, from, TODAY, By.MERCHANT, 2L, List.of(), 25))
                .extracting(Group::name).containsExactly("PIZZERIA DA GINO");
    }

    @Test
    void theUsualOfACategoryAtTheSameDay() {
        List<Movement> movements = List.of(expense(2026, 9, 10, 1L, "-30.00"), expense(2026, 9, 20, 1L, "-50.00"));
        List<YearMonth> months = List.of(YearMonth.of(2026, 9));

        assertThat(Explorer.usualUpTo(movements, Metric.EXPENSES, 1L, months, 15)).isEqualByComparingTo("30.00");
        assertThat(Explorer.usualUpTo(movements, Metric.EXPENSES, 1L, months, 31)).isEqualByComparingTo("80.00");
        assertThat(Explorer.usualUpTo(movements, Metric.EXPENSES, 1L, List.of(), 31)).isNull();
    }

    private static Movement expense(int year, int month, int day, Long categoryId, String amount) {
        return new Movement(LocalDate.of(year, month, day), new BigDecimal(amount), TransactionType.EXPENSE,
                categoryId, "Cat " + categoryId, 1L, "Conto", "NEGOZIO", "NEGOZIO");
    }

    private static Movement income(int year, int month, int day, String amount) {
        return new Movement(LocalDate.of(year, month, day), new BigDecimal(amount), TransactionType.INCOME, 9L,
                "Stipendio", 1L, "Conto", "ACME", "ACME SRL");
    }
}
