package it.wallettracker.subscription;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Riconosce se una serie di pagamenti allo stesso esercente ha l'aspetto di un abbonamento.
 *
 * <p>È una classe "pura": riceve dei dati e restituisce un risultato, senza database, senza Spring e senza
 * leggere l'orologio ("oggi" è un parametro). Per questo si prova con test semplicissimi e velocissimi
 * (vedi RecurringDetectorTest).
 *
 * <p>Una serie è un abbonamento se valgono <b>tutte</b> queste condizioni:
 * <ol>
 *   <li><b>abbastanza pagamenti</b>: almeno 3 per un mensile, almeno 2 per un annuale;</li>
 *   <li><b>intervalli regolari</b>: l'intervallo "tipico" (la mediana) è di circa un mese o un anno, e
 *       almeno 3 intervalli su 4 sono in quella fascia (un mese saltato non basta a escluderlo);</li>
 *   <li><b>importi simili</b>: almeno 3 pagamenti su 4 sono entro il 20% dell'importo tipico. Il
 *       supermercato ogni mese c'è, ma con importi sempre diversi: non è un abbonamento;</li>
 *   <li><b>ancora attivo</b>: l'ultimo pagamento non è troppo vecchio (al massimo un periodo e mezzo fa).
 *       Un abbonamento disdetto un anno fa non serve suggerirlo.</li>
 * </ol>
 * In caso di dubbio la risposta è "no": un suggerimento sbagliato fa perdere fiducia nel programma, uno
 * mancato si recupera (l'utente lo vede nei movimenti).
 */
public final class RecurringDetector {

    /** Quanto possono variare gli importi rispetto a quello tipico: 20%. */
    static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.20");

    /** La parte di intervalli e importi che deve essere "regolare": 3 su 4. */
    static final double REGULAR_SHARE = 0.75;

    private RecurringDetector() {
    }

    /** Un pagamento: la data e l'importo, positivo. */
    public record Payment(LocalDate date, BigDecimal amount) {
    }

    /** Il risultato: ogni quanto, quanto di solito, quante volte, l'ultimo e il prossimo previsto. */
    public record Pattern(Frequency frequency, BigDecimal typicalAmount, int occurrences, LocalDate lastDate,
            BigDecimal lastAmount, LocalDate nextDate) {
    }

    /** La serie è un abbonamento? Se sì, come si ripete. */
    public static Optional<Pattern> detect(List<Payment> payments, LocalDate today) {
        List<Payment> sorted = new ArrayList<>(payments);
        sorted.sort(Comparator.comparing(Payment::date));

        for (Frequency frequency : Frequency.values()) {
            int minimum = frequency == Frequency.MONTHLY ? 3 : 2;
            if (sorted.size() < minimum) {
                continue;
            }
            List<Long> intervals = new ArrayList<>();
            for (int i = 1; i < sorted.size(); i++) {
                intervals.add(ChronoUnit.DAYS.between(sorted.get(i - 1).date(), sorted.get(i).date()));
            }
            long typicalInterval = median(intervals);
            if (typicalInterval < frequency.minDays || typicalInterval > frequency.maxDays) {
                continue;
            }
            long regularIntervals = intervals.stream()
                    .filter(days -> days >= frequency.minDays && days <= frequency.maxDays)
                    .count();
            if (regularIntervals < REGULAR_SHARE * intervals.size()) {
                continue;
            }

            BigDecimal typicalAmount = medianAmount(sorted);
            BigDecimal tolerance = typicalAmount.multiply(AMOUNT_TOLERANCE);
            long similarAmounts = sorted.stream()
                    .filter(p -> p.amount().subtract(typicalAmount).abs().compareTo(tolerance) <= 0)
                    .count();
            if (similarAmounts < REGULAR_SHARE * sorted.size()) {
                continue;
            }

            Payment last = sorted.getLast();
            LocalDate nextDate = frequency == Frequency.MONTHLY ? last.date().plusMonths(1) : last.date().plusYears(1);
            long daysSinceLast = ChronoUnit.DAYS.between(last.date(), today);
            if (daysSinceLast > frequency.typicalDays() * 1.5) {
                return Optional.empty(); // regolare, ma finito: probabilmente disdetto
            }
            return Optional.of(new Pattern(frequency, typicalAmount, sorted.size(), last.date(), last.amount(),
                    nextDate));
        }
        return Optional.empty();
    }

    /** La mediana: il valore in mezzo, una volta messi in ordine. A differenza della media, un valore strano non la sposta. */
    static long median(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    private static BigDecimal medianAmount(List<Payment> payments) {
        List<BigDecimal> sorted = payments.stream().map(Payment::amount).sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : sorted.get(middle - 1).add(sorted.get(middle)).divide(BigDecimal.TWO, 2, RoundingMode.HALF_UP);
    }
}
