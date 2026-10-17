package it.wallettracker.budget;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Il corpo delle richieste per creare o modificare un budget (vedi RuleRequest per la validazione).
 *
 * <ul>
 *   <li>{@code @Pattern(regexp = "[A-Z]{3}")}: la valuta sono tre lettere maiuscole, come "EUR";</li>
 *   <li>{@code @DecimalMin("0.01")}: un budget di zero (o negativo) non ha senso;</li>
 *   <li>{@code @Digits(fraction = 2)}: al massimo due decimali, come i centesimi.</li>
 * </ul>
 */
public record BudgetRequest(
        @NotNull Long categoryId, // l'id di una categoria dell'elenco
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull @DecimalMin("0.01") @DecimalMax("1000000000") @Digits(integer = 10, fraction = 2)
        BigDecimal monthlyAmount) {
}
