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
 *
 * <p><b>Più abbonamenti dallo stesso esercente</b> ({@link #detectAll}): lo stesso creditore può addebitare
 * cose diverse (Fastweb: la SIM da 7,99 € e una bolletta da 23,95 €). Mescolati, gli addebiti non sono più
 * regolari. Se la serie intera non è un abbonamento, la dividiamo per importo e guardiamo ogni gruppo da solo.
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

    /**
     * Gli abbonamenti in una serie di pagamenti allo stesso esercente: di solito uno (o nessuno), ma possono
     * essere di più se l'esercente addebita importi diversi (vedi la descrizione della classe).
     * <ol>
     *   <li>prima la serie intera, come {@link #detect}: così un rincaro piccolo (12,99 → 13,99) resta lo stesso
     *       abbonamento;</li>
     *   <li>se non lo è, i gruppi di importi simili ({@link #amountGroups}), ognuno da solo.</li>
     * </ol>
     */
    public static List<Pattern> detectAll(List<Payment> payments, LocalDate today) {
        Optional<Pattern> whole = detect(payments, today);
        if (whole.isPresent()) {
            return List.of(whole.get());
        }
        List<List<Payment>> groups = amountGroups(payments);
        if (groups.size() < 2) {
            return List.of(); // un gruppo solo è la serie intera: già vista
        }
        return groups.stream()
                .map(group -> detect(group, today))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * I pagamenti divisi in gruppi di importi simili: in ordine di importo, un pagamento entra nel gruppo se è
     * entro il 20% del primo (il più piccolo) del gruppo, altrimenti ne apre uno nuovo. 7,99 7,99 7,99 23,95 →
     * {7,99 7,99 7,99} e {23,95}.
     */
    static List<List<Payment>> amountGroups(List<Payment> payments) {
        List<Payment> byAmount = new ArrayList<>(payments);
        byAmount.sort(Comparator.comparing(Payment::amount));
        List<List<Payment>> groups = new ArrayList<>();
        List<Payment> current = new ArrayList<>();
        for (Payment payment : byAmount) {
            if (!current.isEmpty() && !similar(payment.amount(), current.getFirst().amount())) {
                groups.add(current);
                current = new ArrayList<>();
            }
            current.add(payment);
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    /** Due importi sono "simili" se la differenza è al massimo il 20% del riferimento. */
    public static boolean similar(BigDecimal amount, BigDecimal reference) {
        return amount.subtract(reference).abs().compareTo(reference.multiply(AMOUNT_TOLERANCE)) <= 0;
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
            long similarAmounts = sorted.stream()
                    .filter(p -> similar(p.amount(), typicalAmount))
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
