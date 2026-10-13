package it.wallettracker.classification;

import it.wallettracker.classification.ClassificationRule.AmountSign;
import it.wallettracker.classification.ClassificationRule.MatchMode;
import it.wallettracker.transaction.TransactionType;

/**
 * Una regola come la vede la dashboard.
 *
 * @param builtin true per le regole di partenza (fornite dal progetto): si possono modificare e disattivare,
 *                ma non cancellare
 * @param uses    quanti movimenti ha classificato questa regola nell'ultima classificazione
 */
public record RuleDto(
        Long id,
        String name,
        int priority,
        boolean enabled,
        MatchMode matchMode,
        String pattern,
        AmountSign amountSign,
        TransactionType resultType,
        String category,
        boolean builtin,
        long uses) {

    static RuleDto from(ClassificationRule rule, long uses) {
        return new RuleDto(rule.getId(), rule.getName(), rule.getPriority(), rule.isEnabled(), rule.getMatchMode(),
                rule.getPattern(), rule.getAmountSign(), rule.getResultType(), rule.getCategory(), rule.isBuiltin(),
                uses);
    }
}
