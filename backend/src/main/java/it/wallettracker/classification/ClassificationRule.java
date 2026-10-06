package it.wallettracker.classification;

import java.math.BigDecimal;

import it.wallettracker.transaction.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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

    /** Come confrontare il testo. */
    public enum MatchMode { CONTAINS, STARTS_WITH }

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

    @Column(name = "category")
    private String category;

    @Column(name = "builtin", nullable = false)
    private boolean builtin;

    protected ClassificationRule() {
    }

    public ClassificationRule(String name, int priority, MatchMode matchMode, String pattern, AmountSign amountSign,
            TransactionType resultType, String category) {
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
     * True se la regola si applica a un movimento con questo testo e questo importo.
     * Maiuscole e minuscole non contano.
     */
    public boolean matches(String text, BigDecimal amount) {
        if (amountSign == AmountSign.NEGATIVE && amount.signum() >= 0) {
            return false;
        }
        if (amountSign == AmountSign.POSITIVE && amount.signum() <= 0) {
            return false;
        }
        String haystack = text.toLowerCase();
        String needle = pattern.toLowerCase();
        return matchMode == MatchMode.STARTS_WITH ? haystack.startsWith(needle) : haystack.contains(needle);
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

    public TransactionType getResultType() {
        return resultType;
    }

    public String getCategory() {
        return category;
    }

    public boolean isBuiltin() {
        return builtin;
    }
}
