package it.wallettracker.category;

import it.wallettracker.transaction.TransactionType;

/**
 * Il gruppo di una categoria: a quali movimenti si addice. Serve alla dashboard per proporre per prime le
 * categorie giuste (a una spesa le categorie di spesa), senza vietare le altre.
 */
public enum CategoryKind {

    /** Spese, e rimborsi di spese (es. Spesa alimentare). */
    EXPENSE,

    /** Entrate (es. Stipendio e pensione). */
    INCOME,

    /** Investimenti: rendite e tasse (es. Dividendi, Ritenute). */
    INVESTMENT,

    /** Il resto (es. Saldo carta). */
    OTHER;

    /** Il gruppo che si addice a un tipo di movimento. */
    public static CategoryKind of(TransactionType type) {
        if (type == null) {
            return OTHER;
        }
        return switch (type) {
            case EXPENSE -> EXPENSE;
            case INCOME -> INCOME;
            case INVESTMENT_INCOME, INVESTMENT_TAX, SECURITIES_BUY, SECURITIES_SELL,
                 INVESTMENT_DEPOSIT, INVESTMENT_WITHDRAWAL -> INVESTMENT;
            default -> OTHER;
        };
    }
}
