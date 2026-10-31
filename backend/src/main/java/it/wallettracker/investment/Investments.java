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
 * I calcoli degli investimenti (docs/investimenti.md, parte B): funzioni "pure", che ricevono i movimenti e
 * restituiscono i numeri, senza database. Così si provano con test semplici (InvestmentsTest).
 *
 * <p><b>Il versato, senza contare due volte.</b> 200 € che vanno da ING a Trade Republic e poi in un ETF compaiono
 * tre volte: l'uscita da ING (versamento), l'entrata su Trade Republic (l'altra metà dello stesso trasferimento,
 * anche lei "versamento") e l'acquisto del titolo. Sono sempre gli stessi soldi. Allora contano solo i movimenti dei
 * conti <b>normali</b> (non di investimento): quello che esce verso gli investimenti, meno quello che rientra. È anche
 * l'unico modo che funziona per un conto che non si può collegare (Tinaba): lì si vede solo l'uscita da ING.
 *
 * <p>Un acquisto di titoli fatto direttamente dal conto corrente (il dossier titoli della banca) è anche lui denaro che
 * va negli investimenti: conta, perché è su un conto normale. Quelli fatti dentro un conto di investimento no.
 */
public final class Investments {

    private Investments() {
    }

    /** Un movimento, con quello che serve ai calcoli. */
    public record Movement(LocalDate date, BigDecimal amount, TransactionType type, boolean onInvestmentAccount,
            String category, String source) {
    }

    /** Quanto il movimento aggiunge al versato netto: positivo se i soldi vanno negli investimenti. */
    public static BigDecimal contribution(Movement m) {
        if (m.onInvestmentAccount()) {
            return BigDecimal.ZERO;
        }
        return switch (m.type()) {
            // Un'uscita (-200) verso gli investimenti è +200 di versato; un rientro (+50) è -50.
            case INVESTMENT_DEPOSIT, INVESTMENT_WITHDRAWAL, SECURITIES_BUY, SECURITIES_SELL -> m.amount().negate();
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
     * I mesi da {@code first} a {@code last}, con il versato, le rendite e le tasse di ognuno. Il cumulato parte dalla
     * storia <b>prima</b> di {@code first}: la linea del grafico dice quanto hai messo in tutto, non solo nel periodo.
     */
    public static List<Month> months(List<Movement> movements, YearMonth first, YearMonth last) {
        BigDecimal cumulative = movements.stream()
                .filter(m -> m.date().isBefore(first.atDay(1)))
                .map(Investments::contribution)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Month> months = new ArrayList<>();
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            LocalDate from = month.atDay(1);
            LocalDate to = month.atEndOfMonth();
            BigDecimal contributed = sum(movements, Investments::contribution, from, to);
            cumulative = cumulative.add(contributed);
            months.add(new Month(month, contributed, cumulative, sum(movements, Investments::income, from, to),
                    sum(movements, Investments::taxes, from, to)));
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
