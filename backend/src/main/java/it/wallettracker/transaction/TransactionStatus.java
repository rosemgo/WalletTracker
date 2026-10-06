package it.wallettracker.transaction;

/** Lo stato di un movimento. */
public enum TransactionStatus {

    /** Contabilizzato: definitivo. */
    BOOKED,

    /** In attesa: può ancora cambiare importo o data, o sparire. */
    PENDING
}
