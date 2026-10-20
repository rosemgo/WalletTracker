package it.wallettracker.classification;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

import it.wallettracker.category.Category;
import it.wallettracker.transaction.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Una regola di classificazione: "se il testo del movimento contiene (o inizia con) X, allora il tipo è Y".
 *
 * <p>Le regole sono <b>dati</b>, non codice: stanno nella tabella {@code classification_rule}, così ogni
 * utente può modificarle (dalla dashboard, nella Fase 2) senza toccare il programma.
 */
@Entity
@Table(name = "classification_rule")
public class ClassificationRule {

    /**
     * Come confrontare il testo:
     * <ul>
     *   <li>CONTAINS: il testo contiene queste parole, in quest'ordine e una dopo l'altra;</li>
     *   <li>STARTS_WITH: il testo inizia con queste parole;</li>
     *   <li>ALL_WORDS: il testo contiene tutte queste parole, intere, in qualsiasi ordine e anche con altre
     *       parole in mezzo ("MAR GROUP FOGLIANISE" trova "MAR GROUP SPA FOGLIANISE ITA").</li>
     * </ul>
     */
    public enum MatchMode { CONTAINS, STARTS_WITH, ALL_WORDS }

    /** A quali importi si applica la regola. */
    public enum AmountSign { ANY, NEGATIVE, POSITIVE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_mode", nullable = false)
    private MatchMode matchMode;

    @Column(name = "pattern", nullable = false)
    private String pattern;

    @Enumerated(EnumType.STRING)
    @Column(name = "amount_sign", nullable = false)
    private AmountSign amountSign;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_type", nullable = false)
    private TransactionType resultType;

    /** La categoria da assegnare, facoltativa: un riferimento alla tabella {@code category} (colonna category_id). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(name = "builtin", nullable = false)
    private boolean builtin;

    protected ClassificationRule() {
    }

    public ClassificationRule(String name, int priority, MatchMode matchMode, String pattern, AmountSign amountSign,
            TransactionType resultType, Category category) {
        this.name = name;
        this.priority = priority;
        this.enabled = true;
        this.matchMode = matchMode;
        this.pattern = pattern;
        this.amountSign = amountSign;
        this.resultType = resultType;
        this.category = category;
        this.builtin = false;
    }

    /**
     * Questo metodo serve per capire se una regola applicata a un movimento è giusta: in pratica cataloghizza il movimento (causale del movimento)
     * True se la regola si applica a un movimento con questo testo e questo importo.
     * Maiuscole e minuscole non contano, e nemmeno il numero di spazi (vedi {@link #normalizeSpaces}).
     */
    public boolean matches(String text, BigDecimal amount) {
        //se il campo amountSign della regola è NEGATIVE e la somma del movimento è positiva allora la regola selezionata non va bene per quel movimento
        if (amountSign == AmountSign.NEGATIVE && amount.signum() >= 0) {
            return false;
        }
        //se il campo amountSign della regola è POSITIVE e la somma del movimento è negativa allora la regola selezionata non va bene per quel movimento
        if (amountSign == AmountSign.POSITIVE && amount.signum() <= 0) {
            return false;
        }
        String haystack = normalizeSpaces(text).toLowerCase(Locale.ROOT); //testo del movimento
        String needle = normalizeSpaces(pattern).toLowerCase(Locale.ROOT);
        //se il matchMode della regola selezionata è STARTS_WITH allora verifica che il testo del movimento cominci con il valore del campo pattern della regola (vedere i valori di pattern nella tabella del database classification_rule) oppure che il testo del movimento contenga il pattern della regola.
        return switch (matchMode) {
            case STARTS_WITH -> haystack.startsWith(needle);
            case CONTAINS -> haystack.contains(needle);
            // Tutte le parole della regola tra le parole del testo. Una regola senza parole (es. "***") non
            // trova niente: altrimenti "tutte le parole di un elenco vuoto" sarebbe vero per ogni movimento.
            case ALL_WORDS -> {
                Set<String> wanted = words(needle);
                //Nei switch introdotti nelle versioni più recenti di Java (a partire da Java 14), la parola chiave yield viene utilizzata per restituire un valore da un blocco di codice (tra parentesi graffe) all'interno di un case.
                yield !wanted.isEmpty() && words(haystack).containsAll(wanted);
            }
        };
    }

    /** Uno o più "spazi" di qualsiasi tipo: spazio, tabulazione, a capo, spazio non separabile (U+00A0 e simili). */
    private static final Pattern SPACES = Pattern.compile("[\\s\\u00A0\\u2007\\u202F]+");

    /**
     * Riduce ogni gruppo di spazi a uno spazio solo, e toglie quelli all'inizio e alla fine.
     *
     * <p>Molte banche scrivono i pagamenti con carta in campi a larghezza fissa, riempiti di spazi:
     * {@code "MAR GROUP SPA   FOGLIANISE    ITA"}. Nella pagina Movimenti il browser mostra un solo spazio,
     * quindi chi scrive una regola copiando quello che vede ({@code "MAR GROUP SPA FOGLIANISE"}) deve trovare
     * il movimento. Per questo il confronto non bada al numero (e al tipo) di spazi, da nessuna delle due parti.
     */
    static String normalizeSpaces(String text) {
        return SPACES.matcher(text).replaceAll(" ").trim();
    }

    /**
     * Le parole di un testo: i gruppi di lettere e cifre, separati da tutto il resto (spazi, punteggiatura,
     * simboli). "PAYPAL *TEMU" → paypal, temu; "NETFLIX.COM" → netflix, com. {@code \p{L}} e
     * {@code \p{N}} sono le lettere e le cifre di qualsiasi alfabeto, accentate comprese.
     * Il simbolo ^ all'inizio nega la selezione, quindi l'espressione significa: "dividi il testo ogni volta che trovi uno o più caratteri che non sono né lettere né numeri" (ovvero spazi, virgole, punti, punti esclamativi, ecc.).
     */
    static Set<String> words(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(word -> !word.isEmpty())
                .collect(Collectors.toSet());
    }

    /** Modifica la regola (dalla dashboard). Anche le regole di partenza si possono modificare o disattivare. */
    public void update(String name, int priority, boolean enabled, MatchMode matchMode, String pattern,
            AmountSign amountSign, TransactionType resultType, Category category) {
        this.name = name;
        this.priority = priority;
        this.enabled = enabled;
        this.matchMode = matchMode;
        this.pattern = pattern;
        this.amountSign = amountSign;
        this.resultType = resultType;
        this.category = category;
    }

    /** Attiva o disattiva la regola: una regola disattivata resta salvata ma non viene provata. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getPriority() {
        return priority;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public MatchMode getMatchMode() {
        return matchMode;
    }

    public String getPattern() {
        return pattern;
    }

    public AmountSign getAmountSign() {
        return amountSign;
    }

    public TransactionType getResultType() {
        return resultType;
    }

    public Category getCategory() {
        return category;
    }

    public boolean isBuiltin() {
        return builtin;
    }
}
