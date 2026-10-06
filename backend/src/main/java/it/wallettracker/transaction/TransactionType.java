package it.wallettracker.transaction;

/**
 * Il tipo di un movimento, assegnato dalla classificazione (vedi ClassificationService e docs/03).
 * Solo EXPENSE e INCOME contano come spese ed entrate "vere".
 */
public enum TransactionType {

    /** Una spesa vera. */
    EXPENSE,

    /** Un'entrata vera (stipendio, rimborso, regalo ricevuto...). */
    INCOME,

    /** Soldi spostati tra due tuoi conti: non è né una spesa né un'entrata. */
    INTERNAL_TRANSFER,

    /** Soldi spostati da un tuo conto normale a un tuo conto di investimento. */
    INVESTMENT_DEPOSIT,

    /** Soldi spostati da un tuo conto di investimento a un tuo conto normale. */
    INVESTMENT_WITHDRAWAL,

    /** Acquisto di titoli. */
    SECURITIES_BUY,

    /** Vendita di titoli. */
    SECURITIES_SELL,

    /** Rendite da investimenti: cedole, dividendi, interessi. */
    INVESTMENT_INCOME,

    /** Tasse sugli investimenti: ritenute, bolli, capital gain. */
    INVESTMENT_TAX,

    /** Da ignorare (importo zero, conto escluso...). */
    IGNORED
}
