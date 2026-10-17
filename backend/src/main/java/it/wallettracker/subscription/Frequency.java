package it.wallettracker.subscription;

/** Ogni quanto si ripete un abbonamento. */
public enum Frequency {

    /** Circa ogni mese (da 26 a 35 giorni). */
    MONTHLY(26, 35, 12),

    /** Circa ogni anno (da 350 a 380 giorni). */
    YEARLY(350, 380, 1);

    final int minDays;
    final int maxDays;
    /** Quante volte all'anno: serve per calcolare il costo mensile e annuale. */
    final int timesPerYear;

    Frequency(int minDays, int maxDays, int timesPerYear) {
        this.minDays = minDays;
        this.maxDays = maxDays;
        this.timesPerYear = timesPerYear;
    }

    /** Il periodo "tipico", per prevedere il prossimo pagamento. */
    int typicalDays() {
        return this == MONTHLY ? 30 : 365;
    }
}
