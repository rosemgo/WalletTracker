package it.wallettracker.overview;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * I calcoli del Riepilogo (docs/36, parte C), senza database: funzioni pure, che ricevono le spese già lette e
 * restituiscono numeri. Si provano con dati scritti a mano (MonthCurvesTest), senza avviare niente.
 *
 * <p>Il "solito" è la media dei mesi completi precedenti, <b>giorno per giorno</b>: "il 15 del mese, di solito, avevi
 * speso 130 €". Così il confronto è giusto anche a metà mese: confrontare i 170 € spesi al 15 ottobre con i 230 € di un
 * mese intero direbbe "stai spendendo meno" anche quando non è vero.
 *
 * <p>I mesi non sono lunghi uguali: per il giorno 31 di un mese di 30 giorni si usa il totale del mese (nel giorno 31
 * non c'è stato niente da spendere).
 */
final class MonthCurves {

    private MonthCurves() {
    }

    /**
     * Una spesa (o la somma delle spese) di un giorno, in una categoria: importo positivo, come lo pensa una persona
     * ("ho speso 120 €"). Un rimborso segnato come spesa è negativo e la riduce.
     *
     * @param categoryId null per le spese senza categoria
     */
    record Expense(LocalDate day, Long categoryId, String category, BigDecimal amount, long movements) {
    }

    /** Quanto si è speso nel mese {@code month} dal giorno 1 al giorno {@code day} compreso. */
    static BigDecimal upTo(List<Expense> expenses, YearMonth month, int day) {
        int last = Math.min(day, month.lengthOfMonth());
        BigDecimal total = BigDecimal.ZERO;
        for (Expense expense : expenses) {
            if (YearMonth.from(expense.day()).equals(month) && expense.day().getDayOfMonth() <= last) {
                total = total.add(expense.amount());
            }
        }
        return total;
    }

    /** La spesa accumulata del mese: il valore di ogni giorno, dal giorno 1 a {@code days}. */
    static List<BigDecimal> cumulative(List<Expense> expenses, YearMonth month, int days) {
        List<BigDecimal> curve = new ArrayList<>();
        for (int day = 1; day <= days; day++) {
            curve.add(upTo(expenses, month, day));
        }
        return curve;
    }

    /**
     * Il mese tipico: per ogni giorno da 1 a {@code days}, la media delle spese accumulate fino a quel giorno nei mesi
     * {@code months}. Vuota se non ci sono mesi da cui calcolarla.
     */
    static List<BigDecimal> typical(List<Expense> expenses, List<YearMonth> months, int days) {
        List<BigDecimal> curve = new ArrayList<>();
        if (months.isEmpty()) {
            return curve;
        }
        for (int day = 1; day <= days; day++) {
            curve.add(usualUpTo(expenses, months, day));
        }
        return curve;
    }

    /** La media, sui mesi {@code months}, delle spese dal giorno 1 al giorno {@code day}; null senza mesi. */
    static BigDecimal usualUpTo(List<Expense> expenses, List<YearMonth> months, int day) {
        if (months.isEmpty()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (YearMonth month : months) {
            total = total.add(upTo(expenses, month, day));
        }
        // Due decimali, arrotondando "come a scuola" (0,005 va su).
        return total.divide(BigDecimal.valueOf(months.size()), 2, RoundingMode.HALF_UP);
    }

    /** Solo le spese di una categoria ({@code null} = quelle senza categoria). */
    static List<Expense> ofCategory(List<Expense> expenses, Long categoryId) {
        return expenses.stream().filter(e -> Objects.equals(e.categoryId(), categoryId)).toList();
    }

    /**
     * I mesi da cui calcolare il solito: i {@code count} mesi prima di {@code month}, ma solo quelli che conosciamo
     * <b>per intero</b>, cioè che cominciano dopo il primo movimento della storia ({@code historyStart}). Il primo mese
     * di un conto collegato a metà mese è incompleto: nella media farebbe sembrare il solito più basso.
     */
    static List<YearMonth> usualMonths(YearMonth month, int count, LocalDate historyStart) {
        List<YearMonth> months = new ArrayList<>();
        if (historyStart == null) {
            return months;
        }
        for (YearMonth m = month.minusMonths(count); m.isBefore(month); m = m.plusMonths(1)) {
            if (!historyStart.isAfter(m.atDay(1))) {
                months.add(m);
            }
        }
        return months;
    }
}
