package it.wallettracker.account;

/** Da dove arrivano i movimenti di un conto. */
public enum AccountSource {

    /** Da Enable Banking: il conto è collegato alla banca e si aggiorna da solo. */
    ENABLE_BANKING,

    /** Da file CSV caricati dall'utente (es. Trade Republic): niente collegamento, niente aggiornamento automatico. */
    MANUAL
}
