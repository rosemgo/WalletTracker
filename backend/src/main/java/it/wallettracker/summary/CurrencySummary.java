package it.wallettracker.summary;

import java.math.BigDecimal;
import java.util.List;

/**
 * Il riepilogo di una valuta: un elemento per mese (anche i mesi senza movimenti, con zero), il totale e
 * le spese del periodo per categoria, dalla più alta.
 *
 * <p>Perché separare le valute? Perché 100 EUR più 100 USD non fa 200 di niente: sommarli darebbe un numero
 * sbagliato. Chi ha solo conti in euro vede una sola valuta.
 */
public record CurrencySummary(String currency, List<MonthSummary> months, Totals total,
        List<CategoryExpense> expensesByCategory) {

    /** I totali del periodo: le stesse voci di {@link MonthSummary}, senza il mese. */
    public record Totals(BigDecimal income, BigDecimal expenses, BigDecimal savings, BigDecimal investmentNet,
            long toReview) {
    }

    /**
     * Le spese di una categoria nel periodo, come numero positivo.
     *
     * @param category  il nome della categoria (categoryId è il suo id); entrambi null = spese senza categoria
     * @param movements quanti movimenti, rimborsi compresi
     */
    public record CategoryExpense(Long categoryId, String category, BigDecimal amount, long movements) {
    }
}
