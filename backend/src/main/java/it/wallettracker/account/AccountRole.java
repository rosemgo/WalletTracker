package it.wallettracker.account;

/**
 * Il ruolo di un conto, che decide come trattare i suoi movimenti (vedi docs/03-modello-e-regole.md).
 */
public enum AccountRole {

    /** Non ancora assegnato: è il valore iniziale di ogni conto nuovo. */
    UNASSIGNED,

    /** Il conto principale (es. dove arriva lo stipendio). */
    MAIN,

    /** Un conto usato per le spese. */
    SPENDING,

    /**
     * Una carta di credito: i suoi acquisti sono spese vere, mentre il pagamento del suo saldo da un altro
     * conto è un CARD_SETTLEMENT, che non conta come spesa (vedi ClassificationService).
     */
    CREDIT_CARD,

    /** Un conto di investimento (broker, conto titoli). */
    INVESTMENT,

    /** Conti da ignorare (collegati per prova, vuoti). */
    EXCLUDED
}
