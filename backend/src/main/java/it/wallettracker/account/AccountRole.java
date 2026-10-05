package it.wallettracker.account;

/**
 * Il ruolo di un conto, che decide come trattare i suoi movimenti (vedi docs/03-modello-e-regole.md).
 */
public enum AccountRole {

    /** Non ancora assegnato: è il valore iniziale di ogni conto nuovo. */
    UNASSIGNED,

    /** Il conto principale, dove arriva lo stipendio (ING conto corrente). */
    MAIN,

    /** Conti usati per le spese (Revolut, ING carta di credito, BBVA). */
    SPENDING,

    /** Conti di investimento (Fineco, Trade Republic). */
    INVESTMENT,

    /** Conti da ignorare (collegati per prova, vuoti). */
    EXCLUDED
}
