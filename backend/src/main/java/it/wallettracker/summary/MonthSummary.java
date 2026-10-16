package it.wallettracker.summary;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * I numeri di un mese, in una valuta. Tutti gli importi sono positivi quando "vanno bene": le spese sono
 * scritte come numero positivo (1200.00 = hai speso 1200 euro).
 *
 * @param month         il mese, in JSON "2026-10"
 * @param income        le entrate vere (tipo INCOME)
 * @param expenses      le spese vere (tipo EXPENSE); un rimborso classificato come spesa le riduce
 * @param savings       entrate meno spese: quanto hai messo da parte (negativo = hai speso più di quanto è entrato)
 * @param investmentNet rendite meno tasse degli investimenti (INVESTMENT_INCOME + INVESTMENT_TAX); tenute a parte
 * @param toReview      quanti movimenti sono ancora da verificare: finché ci sono, i numeri del mese sono incompleti
 */
public record MonthSummary(YearMonth month, BigDecimal income, BigDecimal expenses, BigDecimal savings,
        BigDecimal investmentNet, long toReview) {
}
