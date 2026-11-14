package it.wallettracker.investment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import it.wallettracker.investment.InvestmentDtos.Group;
import it.wallettracker.investment.InvestmentDtos.GroupKind;
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

    /**
     * Un movimento, con quello che serve ai calcoli.
     *
     * @param sourceAccountId quando {@code source} è il nome del conto (il testo non ha il nome di un titolo): l'id di
     *                        quel conto, per aprirne i movimenti; altrimenti null
     * @param sourceSearch    in quel caso, le parole fisse del testo da cercare in Movimenti ("interest payment for payout
     *                        collection"): con il conto, trovano proprio quei movimenti
     * @param accountId       il conto del movimento, per abbinare una ritenuta alla sua rendita (parte U): la banca
     *                        trattiene la ritenuta sullo stesso conto su cui paga. Null se non si sa
     * @param isin            il codice ISIN del titolo, se il testo ne ha uno ({@link #isin(String)}); altrimenti null
     */
    public record Movement(LocalDate date, BigDecimal amount, TransactionType type, boolean onInvestmentAccount,
            String category, String source, Long sourceAccountId, String sourceSearch, Long accountId, String isin) {

        public Movement(LocalDate date, BigDecimal amount, TransactionType type, boolean onInvestmentAccount,
                String category, String source) {
            this(date, amount, type, onInvestmentAccount, category, source, null, null, null, null);
        }

        /** Lo stesso movimento con la data e il titolo della sua rendita: una ritenuta abbinata (parte U). */
        Movement on(Movement income) {
            return new Movement(income.date(), amount, type, onInvestmentAccount, category, income.source(),
                    income.sourceAccountId(), income.sourceSearch(), accountId, isin);
        }
    }

    /**
     * Le parole fisse di un testo, per cercarlo in Movimenti (docs/investimenti.md, parte P): le prime cinque senza
     * cifre. "Interest payment for payout collection 019e8191-…" dà "interest payment for payout collection", uguale per
     * ogni mese. Null se non ce ne sono.
     */
    public static String searchWords(String text) {
        if (text == null) {
            return null;
        }
        String words = Arrays.stream(text.toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(w -> !w.isEmpty() && w.chars().noneMatch(Character::isDigit))
                .limit(5)
                .collect(Collectors.joining(" "));
        return words.isEmpty() ? null : words;
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

    /**
     * Tutte le tasse sugli investimenti, ritenute comprese: l'importo così com'è (negativo; positivo se una tassa viene
     * restituita).
     */
    public static BigDecimal taxes(Movement m) {
        return isWithholding(m) || m.type() == TransactionType.INVESTMENT_TAX ? m.amount() : BigDecimal.ZERO;
    }

    /**
     * Le ritenute sulle rendite (docs/investimenti.md, parti J e T): i movimenti di tipo WITHHOLDING_TAX. Fineco paga
     * la cedola lorda e trattiene la ritenuta con un movimento a parte; rendite più ritenute danno il netto. Trade
     * Republic paga già il netto, e non c'è niente da togliere.
     */
    public static BigDecimal withholding(Movement m) {
        return isWithholding(m) ? m.amount() : BigDecimal.ZERO;
    }

    /** Le altre tasse sugli investimenti (bollo, capital gain, Tobin tax...): non riguardano le rendite. */
    public static BigDecimal otherTaxes(Movement m) {
        return m.type() == TransactionType.INVESTMENT_TAX ? m.amount() : BigDecimal.ZERO;
    }

    /** Una ritenuta: lo dice il tipo, non la categoria (che è un nome dell'utente, e può cambiare: parte T). */
    private static boolean isWithholding(Movement m) {
        return m.type() == TransactionType.WITHHOLDING_TAX;
    }

    /**
     * Il nome del titolo di una rendita o di una ritenuta, per "Da dove" (docs/investimenti.md, parte L). Del testo della
     * banca si tolgono solo le parti che non sono il nome:
     * <ul>
     *   <li>l'etichetta iniziale ("Ced.su", "Div.su", "Rit.ced.su", "Cash Dividend"...): il tipo lo dice già la
     *       categoria;</li>
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

    /**
     * Le etichette con cui iniziano le causali delle rendite e delle ritenute (le regole di V4, V25 e V26). Fineco
     * scrive "Rit.ced.su" e "Rit.div.su", con il ".su" come in "Ced.su" e "Div.su": senza, dal nome restava un "su" di
     * troppo, e nessuna ritenuta si abbinava alla sua cedola (parte U).
     */
    private static final Pattern LABEL = Pattern.compile(
            "^(?i)(ced\\.\\s*su|div\\.\\s*su|rit\\.\\s*ced(\\.\\s*su)?|rit\\.\\s*div(\\.\\s*su)?|rit\\.\\s*fisc\\.?|"
                    + "cash dividend|interest payment( for( payout collection)?)?|capital distribution)\\b[\\s.:]*");

    /**
     * Un codice ISIN: due lettere (il paese), nove lettere o cifre, una cifra di controllo. "IT0003128367" (Enel),
     * "XS2353182020". È uguale in tutte le banche: il modo più sicuro per dire che due testi parlano dello stesso titolo.
     */
    private static final Pattern ISIN = Pattern.compile("\\b([A-Z]{2}[A-Z0-9]{9}[0-9])\\b");

    /**
     * Il codice ISIN nel testo di un movimento, se c'è e la sua cifra di controllo torna; altrimenti null. La cifra di
     * controllo evita di scambiare per un ISIN una parola qualsiasi di 12 caratteri.
     */
    public static String isin(String text) {
        if (text == null) {
            return null;
        }
        var matcher = ISIN.matcher(text.toUpperCase(Locale.ROOT));
        while (matcher.find()) {
            if (validIsin(matcher.group(1))) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /**
     * La cifra di controllo di un ISIN (ISO 6166): ogni lettera diventa un numero (A = 10 ... Z = 35), poi sulle cifre
     * si fa il controllo di Luhn, lo stesso dei numeri delle carte di credito: da destra, una cifra sì e una no si
     * raddoppia (togliendo 9 se supera 9), e la somma di tutto deve finire con 0.
     */
    static boolean validIsin(String code) {
        StringBuilder digits = new StringBuilder();
        for (char c : code.toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        int sum = 0;
        boolean twice = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (twice) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            twice = !twice;
        }
        return sum % 10 == 0;
    }

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
     * gruppo; null diventa la voce senza nome {@code missing}.
     */
    public static List<Group> incomeBy(List<Movement> movements, LocalDate from, LocalDate to,
            Function<Movement, String> name, GroupKind missing) {
        return groupBy(movements, from, to, m -> m.type() == TransactionType.INVESTMENT_INCOME, name, missing, true);
    }

    /** Una ritenuta si cerca da tanti giorni prima della sua rendita... */
    static final int WITHHOLDING_DAYS_BEFORE = 3;

    /** ...a tanti giorni dopo: Fineco la registra a volte il giorno dopo la cedola, a cavallo di due mesi. */
    static final int WITHHOLDING_DAYS_AFTER = 10;

    /**
     * Ogni ritenuta prende la data e il titolo della sua rendita (docs/investimenti.md, parti O e U): una cedola del 30
     * ottobre con la ritenuta registrata il 1° novembre è una cedola netta di ottobre, qualunque periodo si guardi. È la
     * "competenza": la ritenuta è una parte della cedola, non un fatto a sé.
     *
     * <p>L'abbinamento si fa su tutta la storia, prima di scegliere il periodo. La rendita giusta è sullo stesso conto
     * (la banca trattiene sul conto su cui paga), da {@link #WITHHOLDING_DAYS_BEFORE} giorni prima a
     * {@link #WITHHOLDING_DAYS_AFTER} dopo, non ancora abbinata e almeno grande quanto la ritenuta. Tra queste si cerca
     * in tre passate, dalla più sicura alla meno sicura, così una prova debole non "ruba" la rendita a una forte:
     * <ol>
     *   <li><b>lo stesso codice ISIN</b> nei due testi: uguale in tutte le banche;</li>
     *   <li><b>lo stesso nome del titolo</b> ({@link #securityName}), senza badare alle maiuscole;</li>
     *   <li><b>l'unica rendita possibile</b>: per le banche con testi che non conosciamo, e per gli interessi della
     *       liquidità di Fineco ("Rit. Fisc. Interessi Portaf…" e "Interessi Portaf…", con parole diverse). Con due
     *       rendite possibili non si sceglie a caso.</li>
     * </ol>
     * Nelle prime due, la più vicina nel tempo. Una ritenuta senza la sua rendita (più vecchia della storia nell'app, o
     * un caso dubbio) tiene la sua data e il suo nome. La lista che esce ha gli stessi movimenti, nello stesso ordine.
     */
    public static List<Movement> withholdingsOnTheirIncome(List<Movement> movements) {
        List<Movement> result = new ArrayList<>(movements);
        Set<Integer> pairedIncomes = new HashSet<>();
        Set<Integer> pairedWithholdings = new HashSet<>();
        List<java.util.function.BiPredicate<Movement, Movement>> passes = List.of(
                (w, income) -> w.isin() != null && w.isin().equals(income.isin()),
                (w, income) -> w.source() != null && w.source().equalsIgnoreCase(income.source()));
        for (var sameSecurity : passes) {
            for (int i = 0; i < movements.size(); i++) {
                Movement w = movements.get(i);
                if (!isWithholding(w) || pairedWithholdings.contains(i)) {
                    continue;
                }
                Integer best = null;
                long bestDistance = Long.MAX_VALUE;
                for (int j : candidates(movements, w, pairedIncomes)) {
                    long distance = Math.abs(ChronoUnit.DAYS.between(movements.get(j).date(), w.date()));
                    if (sameSecurity.test(w, movements.get(j)) && distance < bestDistance) {
                        best = j;
                        bestDistance = distance;
                    }
                }
                if (best != null) {
                    pair(result, movements, i, best, pairedWithholdings, pairedIncomes);
                }
            }
        }
        for (int i = 0; i < movements.size(); i++) {
            Movement w = movements.get(i);
            if (!isWithholding(w) || pairedWithholdings.contains(i) || w.accountId() == null) {
                continue;
            }
            List<Integer> possible = candidates(movements, w, pairedIncomes);
            if (possible.size() == 1) {
                pair(result, movements, i, possible.getFirst(), pairedWithholdings, pairedIncomes);
            }
        }
        return result;
    }

    /**
     * Le rendite che possono essere quella di una ritenuta: non ancora abbinate, sullo stesso conto (quando si sa), nei
     * giorni giusti e almeno grandi quanto la ritenuta.
     */
    private static List<Integer> candidates(List<Movement> movements, Movement w, Set<Integer> pairedIncomes) {
        List<Integer> found = new ArrayList<>();
        for (int j = 0; j < movements.size(); j++) {
            Movement income = movements.get(j);
            if (income.type() != TransactionType.INVESTMENT_INCOME || pairedIncomes.contains(j)
                    || (w.accountId() != null && income.accountId() != null
                            && !w.accountId().equals(income.accountId()))
                    || income.amount().compareTo(w.amount().abs()) < 0) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(income.date(), w.date()); // positivo: la ritenuta è dopo
            if (days >= -WITHHOLDING_DAYS_BEFORE && days <= WITHHOLDING_DAYS_AFTER) {
                found.add(j);
            }
        }
        return found;
    }

    private static void pair(List<Movement> result, List<Movement> movements, int withholding, int income,
            Set<Integer> pairedWithholdings, Set<Integer> pairedIncomes) {
        pairedWithholdings.add(withholding);
        pairedIncomes.add(income);
        result.set(withholding, movements.get(withholding).on(movements.get(income)));
    }

    /**
     * La riga delle ritenute senza la loro rendita nel periodo: di solito la cedola è nel periodo prima, o è più vecchia
     * della storia nell'app. Così la somma dei gruppi è sempre il netto (parte U).
     */
    static final GroupKind UNMATCHED_WITHHOLDING = GroupKind.UNMATCHED_WITHHOLDING;

    /**
     * Dentro i raggruppamenti le voci si distinguono per nome. Quelle senza nome ne hanno uno interno, che comincia con
     * un carattere che nessun testo di una banca contiene: non si può confondere con una categoria o un titolo, e non
     * esce mai da questa classe ({@link #group}).
     */
    private static String internalName(GroupKind kind) {
        return "\u0000" + kind.name();
    }

    /** Il gruppo da restituire: con il suo nome, oppure (nome interno) con il suo tipo e senza nome. */
    private static Group group(String name, BigDecimal amount, int movements, Long accountId, String search) {
        for (GroupKind kind : GroupKind.values()) {
            if (kind != GroupKind.NAMED && internalName(kind).equals(name)) {
                return new Group(kind, amount, movements);
            }
        }
        return new Group(name, amount, movements, accountId, search);
    }

    /** A parità di importo, in ordine di nome; le voci senza nome in fondo. */
    private static final Comparator<Group> BY_NAME = Comparator.comparing(Group::name,
            Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Le rendite NETTE del periodo raggruppate per tipo ({@code byType}) o per titolo (docs/investimenti.md, parti M e
     * U): ogni ritenuta si toglie dalla rendita dello stesso titolo. Quelle abbinate da
     * {@link #withholdingsOnTheirIncome} hanno già il nome della loro rendita. I nomi si confrontano senza badare alle
     * maiuscole: "ENEL" (Fineco) ed "Enel" (Trade Republic) sono lo stesso titolo, una riga sola, con il primo nome
     * trovato. Una ritenuta senza una rendita dello stesso titolo e sullo stesso conto nel periodo va in una riga a
     * parte, così la somma dei gruppi è sempre il netto. I movimenti contati sono le rendite.
     */
    public static List<Group> netIncomeBy(List<Movement> movements, LocalDate from, LocalDate to, boolean byType) {
        Map<String, String> typeOfSource = new LinkedHashMap<>(); // titolo e conto → tipo ({@link #onAccount})
        Map<String, String> nameOf = new LinkedHashMap<>(); // chiave → il nome da mostrare
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Long> accounts = new LinkedHashMap<>();
        Map<String, String> searches = new LinkedHashMap<>();
        List<Movement> withholdings = new ArrayList<>();
        for (Movement m : movements) {
            if (m.date().isBefore(from) || m.date().isAfter(to)) {
                continue;
            }
            if (m.type() == TransactionType.INVESTMENT_INCOME) {
                String type = m.category() == null ? internalName(GroupKind.OTHER_INCOME) : m.category();
                if (m.source() != null) {
                    typeOfSource.putIfAbsent(onAccount(m), type);
                }
                String name = byType ? type
                        : (m.source() == null ? internalName(GroupKind.UNNAMED_SECURITY) : m.source());
                String key = lower(name);
                nameOf.putIfAbsent(key, name);
                amounts.merge(key, m.amount(), BigDecimal::add);
                counts.merge(key, 1, Integer::sum);
                if (!byType && m.sourceAccountId() != null) {
                    accounts.putIfAbsent(key, m.sourceAccountId());
                    if (m.sourceSearch() != null) {
                        searches.putIfAbsent(key, m.sourceSearch());
                    }
                }
            } else if (isWithholding(m)) {
                withholdings.add(m);
            }
        }
        for (Movement m : withholdings) {
            String type = m.source() == null ? null : typeOfSource.get(onAccount(m));
            String name = type == null ? internalName(UNMATCHED_WITHHOLDING) : (byType ? type : m.source());
            String key = lower(name);
            nameOf.putIfAbsent(key, name);
            amounts.merge(key, m.amount(), BigDecimal::add);
            counts.merge(key, type == null ? 1 : 0, Integer::sum);
        }
        return amounts.entrySet().stream()
                .map(e -> group(nameOf.get(e.getKey()), e.getValue(), counts.get(e.getKey()),
                        accounts.get(e.getKey()), searches.get(e.getKey())))
                .sorted(Comparator.comparing(Group::amount).reversed().thenComparing(BY_NAME))
                .toList();
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    /**
     * Il titolo di un movimento sul suo conto: una ritenuta si toglie da un titolo solo se quel titolo ha una rendita
     * sullo stesso conto. La ritenuta di Fineco su "ENEL" non si toglie dal dividendo di Trade Republic "Enel", che è
     * già netto, anche se nella riga di "Da dove" i due stanno insieme.
     */
    private static String onAccount(Movement m) {
        return lower(m.source()) + " | " + m.accountId();
    }

    /** Le altre tasse del periodo (bollo, capital gain...) per categoria, dalla più pesante. */
    public static List<Group> otherTaxesBy(List<Movement> movements, LocalDate from, LocalDate to) {
        return groupBy(movements, from, to, m -> otherTaxes(m).signum() != 0, Movement::category,
                GroupKind.UNCATEGORIZED, false);
    }

    private static List<Group> groupBy(List<Movement> movements, LocalDate from, LocalDate to,
            java.util.function.Predicate<Movement> which, Function<Movement, String> name, GroupKind missing,
            boolean highestFirst) {
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Movement m : movements) {
            if (!which.test(m) || m.date().isBefore(from) || m.date().isAfter(to)) {
                continue;
            }
            String key = name.apply(m) == null ? internalName(missing) : name.apply(m);
            amounts.merge(key, m.amount(), BigDecimal::add);
            counts.merge(key, 1, Integer::sum);
        }
        Comparator<Group> byAmount = Comparator.comparing(Group::amount);
        return amounts.entrySet().stream()
                .map(e -> group(e.getKey(), e.getValue(), counts.get(e.getKey()), null, null))
                .sorted((highestFirst ? byAmount.reversed() : byAmount).thenComparing(BY_NAME))
                .toList();
    }
}
