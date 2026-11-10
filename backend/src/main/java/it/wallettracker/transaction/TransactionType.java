package it.wallettracker.transaction;

import java.util.EnumSet;
import java.util.Set;

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

    /**
     * Il pagamento del saldo di una carta di credito (es. l'addebito mensile sul conto corrente).
     * Non è una spesa: le spese vere sono i singoli acquisti fatti con la carta, già contati. Contarlo
     * di nuovo vorrebbe dire contare due volte le stesse spese.
     */
    CARD_SETTLEMENT,

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

    /**
     * Ritenute sulle rendite (docs/investimenti.md, parte T): le tasse trattenute su una cedola, un dividendo, degli
     * interessi. Alcune banche (Fineco) pagano il lordo e trattengono la ritenuta con un movimento a parte ("Rit.ced
     * …"): Investimenti la toglie dalla rendita e mostra il netto. Il tipo dice già tutto: non serve una categoria.
     */
    WITHHOLDING_TAX,

    /** Le altre tasse sugli investimenti: bollo, capital gain, Tobin tax. Non si tolgono dalle rendite. */
    INVESTMENT_TAX,

    /** Da ignorare (importo zero, conto escluso...). */
    IGNORED,

    /**
     * Da verificare: il movimento non ha nessuna informazione (né testo né abbinamento), quindi il
     * programma non può sapere cos'è. Resta fuori dai totali finché l'utente non lo classifica a mano.
     */
    TO_REVIEW;

    /**
     * I tipi per cui la categoria serve (docs/categorie-e-tipi.md): risponde a una domanda che il tipo lascia aperta.
     * Una spesa, ma per che cosa? Una rendita, ma quale (cedola, dividendo, interessi)? Per gli altri (acquisti e
     * vendite di titoli, versamenti, giroconti, saldo carta) il tipo dice già tutto: "senza categoria" non è un buco
     * da riempire, e il filtro "Senza categoria" di Movimenti non li mostra.
     */
    public static final Set<TransactionType> WITH_CATEGORY = EnumSet.of(EXPENSE, INCOME, INVESTMENT_INCOME,
            INVESTMENT_TAX);
}
