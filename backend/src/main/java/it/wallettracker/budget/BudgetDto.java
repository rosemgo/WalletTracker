package it.wallettracker.budget;

import java.math.BigDecimal;

/**
 * Un budget come lo vede la dashboard: il budget e come sta andando nel mese scelto.
 *
 * @param spent           le spese della categoria nel mese (i rimborsi le riducono)
 * @param remaining       quanto resta (negativo = budget superato)
 * @param expectedSoFar   quanto "dovresti" aver speso finora spendendo in modo regolare: per il mese in corso
 *                        è la parte del budget dei giorni passati (al giorno 15 di 30, metà budget); per un
 *                        mese passato è tutto il budget
 * @param averageLast3Months la spesa media della categoria nei 3 mesi prima: aiuta a scegliere un budget realistico
 * @param status          OK, WARNING (oltre l'80%) oppure OVER (superato)
 */
public record BudgetDto(
        Long id,
        String category,
        String currency,
        BigDecimal monthlyAmount,
        BigDecimal spent,
        BigDecimal remaining,
        BigDecimal expectedSoFar,
        BigDecimal averageLast3Months,
        BudgetStatus status) {

    /** Come sta andando un budget. */
    public enum BudgetStatus {
        /** Sotto l'80% del budget. */
        OK,
        /** Dall'80% al 100%: attenzione. */
        WARNING,
        /** Superato. */
        OVER
    }
}
