package it.wallettracker.classification;

import it.wallettracker.classification.ClassificationRule.AmountSign;
import it.wallettracker.classification.ClassificationRule.MatchMode;
import it.wallettracker.transaction.TransactionType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Il corpo delle richieste per creare, modificare o provare una regola.
 *
 * <p>Le annotazioni di validazione (Bean Validation) descrivono i vincoli; con {@code @Valid} nel controller,
 * Spring li controlla prima di chiamare il metodo e, se non sono rispettati, risponde 400 con l'elenco dei
 * campi sbagliati.
 */
public record RuleRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Min(0) @Max(10_000) Integer priority,
        boolean enabled,
        @NotNull MatchMode matchMode,
        @NotBlank @Size(min = 2, max = 200) String pattern,
        @NotNull AmountSign amountSign,
        @NotNull TransactionType resultType,
        @Size(max = 100) String category) {

    /** Una categoria vuota ("") viene salvata come "nessuna categoria" (null). */
    String categoryOrNull() {
        return category == null || category.isBlank() ? null : category.trim();
    }
}
