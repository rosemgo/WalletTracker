package it.wallettracker.alert;

import java.time.Instant;
import java.util.List;

/**
 * Un avviso per la dashboard.
 *
 * <p>Il backend manda i <b>dati</b> dell'avviso (di che tipo è, quale banca, quanti giorni...), non la frase
 * da mostrare: le parole e il formato delle date sono compito della dashboard. Così la stessa API potrebbe
 * servire anche un'app per telefono o una mail, ognuna con il suo testo.
 *
 * @param kind     che cosa è successo
 * @param severity quanto è urgente
 * @param bank     la banca (consenso e sincronizzazione), altrimenti null
 * @param country  il paese della banca, per ricollegarla
 * @param account  il nome del conto (sincronizzazione fallita), altrimenti null
 * @param date     la scadenza del consenso, altrimenti null
 * @param daysLeft i giorni che mancano alla scadenza (negativo = scaduto da tanti giorni), altrimenti null
 * @param items    le categorie dei budget superati, altrimenti vuoto
 * @param message  il motivo dell'errore di sincronizzazione, altrimenti null
 */
public record AlertDto(
        AlertKind kind,
        Severity severity,
        String bank,
        String country,
        String account,
        Instant date,
        Long daysLeft,
        List<String> items,
        String message) {

    /** I tipi di avviso. */
    public enum AlertKind {
        /** Il consenso dato alla banca è scaduto: i movimenti non si aggiornano più. */
        CONSENT_EXPIRED,
        /** Il consenso scade entro pochi giorni. */
        CONSENT_EXPIRING,
        /** L'ultima sincronizzazione di un conto è fallita. */
        SYNC_FAILED,
        /** Uno o più budget del mese in corso sono superati. */
        BUDGET_OVER
    }

    /** Quanto è urgente un avviso: gli avvisi arrivano in quest'ordine, dal più urgente. */
    public enum Severity {
        CRITICAL,
        WARNING
    }
}
