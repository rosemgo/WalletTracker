package it.wallettracker.summary;

import java.math.BigDecimal;
import java.util.List;

/**
 * Il riepilogo di una valuta: un elemento per mese (anche i mesi senza movimenti, con zero) e il totale.
 *
 * <p>Perché separare le valute? Perché 100 EUR più 100 USD non fa 200 di niente: sommarli darebbe un numero
 * sbagliato. Chi ha solo conti in euro vede una sola valuta.
 */
public record CurrencySummary(String currency, List<MonthSummary> months, Totals total) {

    /** I totali del periodo: le stesse voci di {@link MonthSummary}, senza il mese. */
    public record Totals(BigDecimal income, BigDecimal expenses, BigDecimal savings, BigDecimal investmentNet,
            long toReview) {
    }
}
