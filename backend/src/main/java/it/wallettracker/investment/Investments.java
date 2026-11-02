package it.wallettracker.investment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

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

    /** La categoria delle ritenute sulle rendite (V12), quella che le regole di partenza danno a "Rit.ced", "Rit.div". */
    static final String WITHHOLDING = "Ritenute";

    /**
     * Le ritenute sulle rendite (docs/investimenti.md, parte J): le tasse con la categoria "Ritenute". Fineco paga la
     * cedola lorda e trattiene la ritenuta con un movimento a parte; rendite più ritenute danno il netto. Trade Republic
     * paga già il netto, e non c'è niente da togliere.
     */
    public static BigDecimal withholding(Movement m) {
        return isWithholding(m) ? m.amount() : BigDecimal.ZERO;
    }

    /** Le altre tasse sugli investimenti (bollo, capital gain, Tobin tax...): non riguardano le rendite. */
    public static BigDecimal otherTaxes(Movement m) {
        return m.type() == TransactionType.INVESTMENT_TAX && !isWithholding(m) ? m.amount() : BigDecimal.ZERO;
    }

    private static boolean isWithholding(Movement m) {
        return m.type() == TransactionType.INVESTMENT_TAX && WITHHOLDING.equalsIgnoreCase(m.category());
    }

    /**
     * Il nome del titolo di una rendita o di una ritenuta, per "Da dove" (docs/investimenti.md, parte L). Del testo della
     * banca si tolgono solo le parti che non sono il nome:
     * <ul>
     *   <li>l'etichetta iniziale ("Ced.su", "Div.su", "Rit.ced", "Cash Dividend"...): il tipo lo dice già la categoria;</li>
     *   <li>i numeri da soli, senza lettere: quantità ("16.000,000") e importi per azione;</li>
     *   <li>le date ("15/09/2032", "2026-10-31") e i codici dei pagamenti (gli UUID di Trade Republic).</li>
     * </ul>
     * Tutto il resto resta, anche con le cifre: nel nome di un titolo ci sono (scadenza, cedola, codice).
     * "Ced.su 16.000,000 ROMANIA-7OT45 6,5%" diventa "ROMANIA-7OT45 6,5%". Null se non resta niente con delle lettere.
     */
    public static String securityName(String text) {
        if (text == null) {
            return null;
        }
        String rest = LABEL.matcher(text.strip()).replaceFirst("");
        rest = DATE.matcher(rest).replaceAll(" ");
        rest = UUID_CODE.matcher(rest).replaceAll(" ");
        StringBuilder name = new StringBuilder();
        for (String word : rest.split("\\s+")) {
            if (word.isEmpty() || NUMBER.matcher(word).matches()) {
                continue;
            }
            name.append(name.isEmpty() ? "" : " ").append(word);
        }
        String result = name.toString().strip();
        if (result.chars().noneMatch(Character::isLetter)) {
            return null;
        }
        return result.length() > 100 ? result.substring(0, 100).strip() : result;
    }

    /** Le etichette con cui iniziano le causali delle rendite e delle ritenute (le regole di V4, V25 e V26). */
    private static final Pattern LABEL = Pattern.compile(
            "^(?i)(ced\\.\\s*su|div\\.\\s*su|rit\\.\\s*ced|rit\\.\\s*div|rit\\.\\s*fisc\\.?|cash dividend|"
                    + "interest payment( for( payout collection)?)?|capital distribution)\\b[\\s.:]*");

    /** Un numero da solo (una quantità, un importo): cifre, punti e virgole, magari con il segno. */
    private static final Pattern NUMBER = Pattern.compile("[+-]?[0-9][0-9.,]*");

    /** Una data: 15/09/2032, 15.09.32, 2026-10-31, 2026-10. */
    private static final Pattern DATE = Pattern.compile(
            "\\b(\\d{1,2}[/.]\\d{1,2}[/.]\\d{2,4}|\\d{4}-\\d{2}(-\\d{2})?)\\b");

    /** Il codice di un pagamento (un UUID): 019e8191-0af0-76fc-a8ac-1d6ee06c92c0. */
    private static final Pattern UUID_CODE = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");

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
                    sum(movements, Investments::income, from, to), sum(movements, Investments::withholding, from, to),
                    sum(movements, Investments::otherTaxes, from, to)));
        }
        return months;
    }

    /**
     * Le rendite del periodo raggruppate (per categoria o per titolo), dalla più alta. {@code name} dà il nome del
     * gruppo; null diventa {@code missing}.
     */
    public static List<Group> incomeBy(List<Movement> movements, LocalDate from, LocalDate to,
            Function<Movement, String> name, String missing) {
        return groupBy(movements, from, to, m -> m.type() == TransactionType.INVESTMENT_INCOME, name, missing, true);
    }

    /** Una ritenuta si cerca da tanti giorni prima della sua rendita... */
    static final int WITHHOLDING_DAYS_BEFORE = 3;

    /** ...a tanti giorni dopo: Fineco la registra a volte il giorno dopo la cedola, a cavallo di due mesi. */
    static final int WITHHOLDING_DAYS_AFTER = 10;

    /**
     * Ogni ritenuta prende la data della sua rendita (docs/investimenti.md, parte O): una cedola del 30 ottobre con la
     * ritenuta registrata il 1° novembre è una cedola netta di ottobre, qualunque periodo si guardi. È la "competenza":
     * la ritenuta è una parte della cedola, non un fatto a sé.
     *
     * <p>L'abbinamento si fa su tutta la storia, prima di scegliere il periodo: la rendita dello stesso titolo
     * ({@link #securityName}), da {@link #WITHHOLDING_DAYS_BEFORE} giorni prima a {@link #WITHHOLDING_DAYS_AFTER} dopo,
     * la più vicina, non ancora abbinata, e almeno grande quanto la ritenuta. Una ritenuta senza la sua rendita (più
     * vecchia della storia nell'app) tiene la sua data. La lista che esce ha gli stessi movimenti, nello stesso ordine.
     */
    public static List<Movement> withholdingsOnTheirIncome(List<Movement> movements) {
        List<Movement> result = new ArrayList<>(movements);
        Set<Integer> pairedIncomes = new HashSet<>();
        for (int i = 0; i < movements.size(); i++) {
            Movement w = movements.get(i);
            if (!isWithholding(w) || w.source() == null) {
                continue;
            }
            Integer best = null;
            long bestDistance = Long.MAX_VALUE;
            for (int j = 0; j < movements.size(); j++) {
                Movement income = movements.get(j);
                if (income.type() != TransactionType.INVESTMENT_INCOME || pairedIncomes.contains(j)
                        || !w.source().equals(income.source())
                        || income.amount().compareTo(w.amount().abs()) < 0) {
                    continue;
                }
                long days = ChronoUnit.DAYS.between(income.date(), w.date()); // positivo: la ritenuta è dopo
                if (days < -WITHHOLDING_DAYS_BEFORE || days > WITHHOLDING_DAYS_AFTER) {
                    continue;
                }
                if (Math.abs(days) < bestDistance) {
                    best = j;
                    bestDistance = Math.abs(days);
                }
            }
            if (best != null) {
                pairedIncomes.add(best);
                result.set(i, new Movement(movements.get(best).date(), w.amount(), w.type(), w.onInvestmentAccount(),
                        w.category(), w.source()));
            }
        }
        return result;
    }

    /** La riga delle ritenute che non si sa a quale rendita togliere. */
    static final String UNMATCHED_WITHHOLDING = "Ritenute non abbinate a una rendita";

    /**
     * Le rendite NETTE del periodo raggruppate per tipo ({@code byType}) o per titolo (docs/investimenti.md, parte M):
     * ogni ritenuta si toglie dalla rendita dello stesso titolo ("Rit.ced … ROMANIA-7OT45 6,5%" da "Ced.su …
     * ROMANIA-7OT45 6,5%", stesso {@link #securityName}). Una ritenuta senza la sua rendita nel periodo va in una riga a
     * parte, così la somma dei gruppi è sempre il netto. I movimenti contati sono le rendite.
     */
    public static List<Group> netIncomeBy(List<Movement> movements, LocalDate from, LocalDate to, boolean byType) {
        Map<String, String> typeOfSource = new LinkedHashMap<>();
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Movement> withholdings = new ArrayList<>();
        for (Movement m : movements) {
            if (m.date().isBefore(from) || m.date().isAfter(to)) {
                continue;
            }
            if (m.type() == TransactionType.INVESTMENT_INCOME) {
                String type = m.category() == null ? "Altre rendite" : m.category();
                if (m.source() != null) {
                    typeOfSource.putIfAbsent(m.source(), type);
                }
                String key = byType ? type : (m.source() == null ? "Senza nome del titolo" : m.source());
                amounts.merge(key, m.amount(), BigDecimal::add);
                counts.merge(key, 1, Integer::sum);
            } else if (isWithholding(m)) {
                withholdings.add(m);
            }
        }
        for (Movement m : withholdings) {
            String type = m.source() == null ? null : typeOfSource.get(m.source());
            String key = type == null ? UNMATCHED_WITHHOLDING : (byType ? type : m.source());
            amounts.merge(key, m.amount(), BigDecimal::add);
            counts.merge(key, type == null ? 1 : 0, Integer::sum);
        }
        return amounts.entrySet().stream()
                .map(e -> new Group(e.getKey(), e.getValue(), counts.get(e.getKey())))
                .sorted(Comparator.comparing(Group::amount).reversed().thenComparing(Group::name))
                .toList();
    }

    /** Le altre tasse del periodo (bollo, capital gain...) per categoria, dalla più pesante. */
    public static List<Group> otherTaxesBy(List<Movement> movements, LocalDate from, LocalDate to) {
        return groupBy(movements, from, to, m -> otherTaxes(m).signum() != 0, Movement::category,
                "Senza categoria", false);
    }

    private static List<Group> groupBy(List<Movement> movements, LocalDate from, LocalDate to,
            java.util.function.Predicate<Movement> which, Function<Movement, String> name, String missing,
            boolean highestFirst) {
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Movement m : movements) {
            if (!which.test(m) || m.date().isBefore(from) || m.date().isAfter(to)) {
                continue;
            }
            String key = name.apply(m) == null ? missing : name.apply(m);
            amounts.merge(key, m.amount(), BigDecimal::add);
            counts.merge(key, 1, Integer::sum);
        }
        Comparator<Group> byAmount = Comparator.comparing(Group::amount);
        return amounts.entrySet().stream()
                .map(e -> new Group(e.getKey(), e.getValue(), counts.get(e.getKey())))
                .sorted((highestFirst ? byAmount.reversed() : byAmount).thenComparing(Group::name))
                .toList();
    }
}
