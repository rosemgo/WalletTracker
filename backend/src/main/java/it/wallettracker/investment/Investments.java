package it.wallettracker.investment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import it.wallettracker.investment.InvestmentDtos.Group;
import it.wallettracker.investment.InvestmentDtos.Month;
import it.wallettracker.transaction.TransactionType;

/**
 * I calcoli degli investimenti (docs/investimenti.md, parti B e I): funzioni "pure", che ricevono i movimenti e
 * restituiscono i numeri, senza database. Così si provano con test semplici (InvestmentsTest).
 *
 * <p><b>Due grandezze, non una.</b> 200 € che vanno da ING a un broker e poi in un ETF compaiono tre volte: l'uscita
 * da ING, l'entrata sul broker (l'altra metà dello stesso bonifico) e l'acquisto del titolo. Sono due fatti diversi,
 * e ognuno si conta una volta sola:
 * <ul>
 *   <li><b>l'investito in titoli</b> ({@link #bought}, {@link #sold}, {@link #invested}): gli acquisti meno le
 *       vendite, su <b>tutti</b> i conti. Un acquisto è un movimento solo, su un conto solo: non c'è niente da
 *       contare due volte. Anche quello fatto dal conto corrente (il deposito titoli della banca) conta;</li>
 *   <li><b>i bonifici verso i conti di investimento</b> ({@link #transferred}): solo sui conti che <b>non</b> sono
 *       di investimento, perché l'altra metà, sul broker, è lo stesso bonifico. È anche l'unico modo che funziona per
 *       un conto che non è nell'app: lì si vede solo l'uscita da ING.</li>
 * </ul>
 * Le spese con la carta del broker e i prelievi al bancomat dal conto del broker non toccano nessuna delle due: sono
 * spese normali.
 */
public final class Investments {

    private Investments() {
    }

    /** Un movimento, con quello che serve ai calcoli. */
    public record Movement(LocalDate date, BigDecimal amount, TransactionType type, boolean onInvestmentAccount,
            String category, String source) {
    }

    /** Quanto il movimento ha comprato di titoli: un acquisto di -200 è 200. */
    public static BigDecimal bought(Movement m) {
        return m.type() == TransactionType.SECURITIES_BUY ? m.amount().negate() : BigDecimal.ZERO;
    }

    /** Quanto è rientrato vendendo titoli: una vendita di +150 è 150. */
    public static BigDecimal sold(Movement m) {
        return m.type() == TransactionType.SECURITIES_SELL ? m.amount() : BigDecimal.ZERO;
    }

    /** L'investito netto in titoli: acquisti meno vendite (un acquisto di -200 è +200, una vendita di +150 è -150). */
    public static BigDecimal invested(Movement m) {
        return bought(m).subtract(sold(m));
    }

    /**
     * Quanto il movimento aggiunge ai bonifici verso i conti di investimento: un'uscita di -200 da ING verso il broker
     * è +200, un rientro di +50 è -50. Sui conti di investimento vale sempre 0: è l'altra metà dello stesso bonifico.
     */
    public static BigDecimal transferred(Movement m) {
        if (m.onInvestmentAccount()) {
            return BigDecimal.ZERO;
        }
        return switch (m.type()) {
            case INVESTMENT_DEPOSIT, INVESTMENT_WITHDRAWAL -> m.amount().negate();
            default -> BigDecimal.ZERO;
        };
    }

    /** Cedole, dividendi e interessi: l'importo così com'è (un dividendo annullato è negativo). */
    public static BigDecimal income(Movement m) {
        return m.type() == TransactionType.INVESTMENT_INCOME ? m.amount() : BigDecimal.ZERO;
    }

    /** Tasse sugli investimenti: l'importo così com'è (negativo; positivo se una tassa viene restituita). */
    public static BigDecimal taxes(Movement m) {
        return m.type() == TransactionType.INVESTMENT_TAX ? m.amount() : BigDecimal.ZERO;
    }

    /** La somma di una misura sui movimenti dal giorno {@code from} al giorno {@code to} (compresi). */
    public static BigDecimal sum(List<Movement> movements, Function<Movement, BigDecimal> measure, LocalDate from,
            LocalDate to) {
        return movements.stream()
                .filter(m -> !m.date().isBefore(from) && !m.date().isAfter(to))
                .map(measure)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * I mesi da {@code first} a {@code last}, con acquisti, vendite, bonifici, rendite e tasse di ognuno. L'investito
     * cumulato parte dalla storia <b>prima</b> di {@code first}: la linea del grafico dice quanto hai investito in
     * tutto, non solo nel periodo.
     */
    public static List<Month> months(List<Movement> movements, YearMonth first, YearMonth last) {
        BigDecimal cumulative = movements.stream()
                .filter(m -> m.date().isBefore(first.atDay(1)))
                .map(Investments::invested)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Month> months = new ArrayList<>();
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            LocalDate from = month.atDay(1);
            LocalDate to = month.atEndOfMonth();
            BigDecimal bought = sum(movements, Investments::bought, from, to);
            BigDecimal sold = sum(movements, Investments::sold, from, to);
            cumulative = cumulative.add(bought).subtract(sold);
            months.add(new Month(month, bought, sold, cumulative, sum(movements, Investments::transferred, from, to),
                    sum(movements, Investments::income, from, to), sum(movements, Investments::taxes, from, to)));
        }
        return months;
    }

    /**
     * Le rendite del periodo raggruppate (per categoria o per titolo), dalla più alta. {@code name} dà il nome del
     * gruppo; null diventa {@code missing}.
     */
    public static List<Group> incomeBy(List<Movement> movements, LocalDate from, LocalDate to,
            Function<Movement, String> name, String missing) {
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Movement m : movements) {
            if (m.type() != TransactionType.INVESTMENT_INCOME || m.date().isBefore(from) || m.date().isAfter(to)) {
                continue;
            }
            String key = name.apply(m) == null ? missing : name.apply(m);
            amounts.merge(key, m.amount(), BigDecimal::add);
            counts.merge(key, 1, Integer::sum);
        }
        return amounts.entrySet().stream()
                .map(e -> new Group(e.getKey(), e.getValue(), counts.get(e.getKey())))
                .sorted(Comparator.comparing(Group::amount).reversed().thenComparing(Group::name))
                .toList();
    }
}
