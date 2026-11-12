package it.wallettracker.account;

/** Da dove viene il saldo di un conto (V34, docs/saldi.md). */
public enum BalanceSource {

    /** Dato dalla banca con Enable Banking, a ogni sincronizzazione. */
    BANK,

    /** Scritto dall'utente per un conto manuale, dopo aver controllato l'ultimo movimento importato. */
    MANUAL
}
