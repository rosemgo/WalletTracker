package it.wallettracker.explore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import it.wallettracker.explore.ExploreDtos.Bar;
import it.wallettracker.explore.ExploreDtos.By;
import it.wallettracker.explore.ExploreDtos.Group;
import it.wallettracker.explore.ExploreDtos.Metric;
import it.wallettracker.explore.ExploreDtos.Period;
import it.wallettracker.explore.ExploreDtos.Unit;
import it.wallettracker.transaction.TransactionType;

/**
 * I calcoli dell'esploratore (docs/38, parte C), senza database: funzioni pure che ricevono i movimenti già letti.
 * Si provano con dati scritti a mano (ExplorerTest).
 */
final class Explorer {

    private Explorer() {
    }

    /**
     * Un movimento, con quello che serve all'esploratore. {@code amount} ha il segno del database (spese negative).
     *
     * @param categoryId   null = senza categoria
     * @param merchantKey  la chiave dell'esercente (null se il movimento non ha testo)
     */
    record Movement(LocalDate day, BigDecimal amount, TransactionType type, Long categoryId, String category,
            Long accountId, String account, String merchantKey, String merchantName) {
    }

    /**
     * Il valore di una "metrica" nel periodo da {@code from} a {@code to} (inclusi): le spese (positive), le entrate,
     * o il netto (entrate meno spese). Con {@code categoryId}, solo quella categoria.
     */
    static BigDecimal value(List<Movement> movements, Metric metric, LocalDate from, LocalDate to, Long categoryId) {
        BigDecimal total = BigDecimal.ZERO;
        for (Movement m : movements) {
            if (!m.day().isBefore(from) && !m.day().isAfter(to) && counts(m, metric, categoryId)) {
                total = total.add(m.amount());
            }
        }
        // Le spese nel database sono negative: per "quanto ho speso" le giriamo.
        return metric == Metric.EXPENSES ? total.negate() : total;
    }

    /** Un movimento conta per la metrica (e la categoria, se c'è)? */
    static boolean counts(Movement m, Metric metric, Long categoryId) {
        boolean type = switch (metric) {
            case EXPENSES -> m.type() == TransactionType.EXPENSE;
            case INCOME -> m.type() == TransactionType.INCOME;
            case NET -> m.type() == TransactionType.EXPENSE || m.type() == TransactionType.INCOME;
        };
        return type && (categoryId == null || categoryId.equals(m.categoryId()));
    }

    /**
     * Le barre di un periodo che finisce il giorno {@code end}:
     * <ul>
     *   <li>WEEK: i sette giorni della settimana (lunedì-domenica) di {@code end};</li>
     *   <li>MONTH: le settimane del mese di {@code end}, a blocchi di sette giorni dal primo (1-7, 8-14, 15-21,
     *       22-28 e, se ci sono, 29-31): ogni barra si confronta con le altre senza settimane a cavallo di due mesi;</li>
     *   <li>SIX_MONTHS e YEAR: i 6 o 12 mesi che finiscono con quello di {@code end}.</li>
     * </ul>
     * Le barre dopo {@code today} sono "future": non ancora successe.
     */
    static List<Bar> bars(Period period, LocalDate end, LocalDate today) {
        List<LocalDate[]> ranges = new ArrayList<>();
        switch (period) {
            case WEEK -> {
                LocalDate monday = end.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                for (int i = 0; i < 7; i++) {
                    ranges.add(new LocalDate[] {monday.plusDays(i), monday.plusDays(i)});
                }
            }
            case MONTH -> {
                YearMonth month = YearMonth.from(end);
                for (int first = 1; first <= month.lengthOfMonth(); first += 7) {
                    ranges.add(new LocalDate[] {month.atDay(first),
                            month.atDay(Math.min(first + 6, month.lengthOfMonth()))});
                }
            }
            case SIX_MONTHS, YEAR -> {
                int count = period == Period.YEAR ? 12 : 6;
                YearMonth last = YearMonth.from(end);
                for (YearMonth m = last.minusMonths(count - 1); !m.isAfter(last); m = m.plusMonths(1)) {
                    ranges.add(new LocalDate[] {m.atDay(1), m.atEndOfMonth()});
                }
            }
        }
        return ranges.stream().map(r -> new Bar(r[0], r[1], BigDecimal.ZERO, r[0].isAfter(today))).toList();
    }

    /** Le barre con il loro valore. */
    static List<Bar> withValues(List<Bar> bars, List<Movement> movements, Metric metric, Long categoryId) {
        return bars.stream()
                .map(b -> new Bar(b.from(), b.to(), value(movements, metric, b.from(), b.to(), categoryId), b.future()))
                .toList();
    }

    /** L'unità delle barre di un periodo: giorni, settimane, mesi. */
    static Unit unitOf(Period period) {
        return switch (period) {
            case WEEK -> Unit.DAY;
            case MONTH -> Unit.WEEK;
            case SIX_MONTHS, YEAR -> Unit.MONTH;
        };
    }

    /**
     * La media "di solito" per unità (al giorno, a settimana, al mese), dai mesi completi {@code usualMonths}: il totale
     * diviso i giorni (per giorno e settimana) o i mesi. Null senza mesi.
     */
    static BigDecimal averagePerUnit(List<Movement> movements, Metric metric, Long categoryId,
            List<YearMonth> usualMonths, Unit unit) {
        if (usualMonths.isEmpty()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        int days = 0;
        for (YearMonth month : usualMonths) {
            total = total.add(value(movements, metric, month.atDay(1), month.atEndOfMonth(), categoryId));
            days += month.lengthOfMonth();
        }
        return switch (unit) {
            case MONTH -> total.divide(BigDecimal.valueOf(usualMonths.size()), 2, RoundingMode.HALF_UP);
            case DAY -> total.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
            case WEEK -> total.multiply(BigDecimal.valueOf(7)).divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
        };
    }

    /**
     * Il valore di una categoria (o di tutto, con {@code categoryId} null) "di solito" dal giorno 1 al giorno
     * {@code day} del mese: la media sui mesi {@code usualMonths}. Null senza mesi.
     */
    static BigDecimal usualUpTo(List<Movement> movements, Metric metric, Long categoryId, List<YearMonth> usualMonths,
            int day) {
        if (usualMonths.isEmpty()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (YearMonth month : usualMonths) {
            LocalDate to = month.atDay(Math.min(day, month.lengthOfMonth()));
            total = total.add(value(movements, metric, month.atDay(1), to, categoryId));
        }
        return total.divide(BigDecimal.valueOf(usualMonths.size()), 2, RoundingMode.HALF_UP);
    }

    /**
     * I movimenti del periodo raggruppati per categoria, esercente o conto, dal gruppo più alto. Per le categorie, con
     * {@code usualMonths} non vuoto, ogni gruppo ha il suo solito allo stesso giorno del mese ({@code day}).
     */
    static List<Group> groups(List<Movement> movements, Metric metric, LocalDate from, LocalDate to, By by,
            Long categoryId, List<YearMonth> usualMonths, int day) {
        Function<Movement, Object> keyOf = switch (by) {
            case CATEGORY -> Movement::categoryId;
            case MERCHANT -> Movement::merchantKey;
            case ACCOUNT -> Movement::accountId;
        };
        // LinkedHashMap: una voce per gruppo. I movimenti arrivano dal più recente, quindi il nome di un esercente è
        // quello del suo movimento più recente.
        Map<Object, List<Movement>> byKey = new LinkedHashMap<>();
        movements.stream()
                .filter(m -> !m.day().isBefore(from) && !m.day().isAfter(to) && counts(m, metric, categoryId))
                .sorted(Comparator.comparing(Movement::day).reversed())
                .forEach(m -> byKey.computeIfAbsent(keyOf.apply(m), k -> new ArrayList<>()).add(m));

        List<Group> groups = new ArrayList<>();
        byKey.forEach((key, list) -> {
            Movement first = list.getFirst();
            BigDecimal amount = list.stream().map(Movement::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (metric == Metric.EXPENSES) {
                amount = amount.negate();
            }
            String name = switch (by) {
                case CATEGORY -> first.category();
                case MERCHANT -> first.merchantName();
                case ACCOUNT -> first.account();
            };
            BigDecimal usual = by == By.CATEGORY && !usualMonths.isEmpty()
                    ? usualUpTo(movements, metric, first.categoryId(), usualMonths, day)
                    : null;
            groups.add(new Group(Objects.toString(key, null), by == By.CATEGORY ? first.categoryId() : null, name,
                    amount, list.size(), usual));
        });
        groups.sort(Comparator.comparing(Group::amount).reversed());
        return groups;
    }
}
