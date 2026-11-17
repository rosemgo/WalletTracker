package it.wallettracker.alert;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import it.wallettracker.message.Message;

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
 * @param message  il motivo dell'errore di sincronizzazione (nella risposta: la sua frase), altrimenti null
 * @param mismatch i saldi che non tornano con i movimenti (docs/saldi.md, parte N), altrimenti null
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
        Message message,
        Mismatch mismatch) {

    /** Un avviso senza i dati di un saldo che non torna: tutti tranne BALANCE_MISMATCH. */
    public AlertDto(AlertKind kind, Severity severity, String bank, String country, String account, Instant date,
            Long daysLeft, List<String> items, Message message) {
        this(kind, severity, bank, country, account, date, daysLeft, items, message, null);
    }

    /**
     * Due saldi di un conto che non tornano con i movimenti arrivati in mezzo.
     *
     * @param accountId  il conto, per aprirne i movimenti
     * @param source     BANK (due saldi della banca) o MANUAL (due saldi scritti dall'utente)
     * @param from       il giorno del primo saldo
     * @param to         il giorno del secondo
     * @param difference il secondo saldo meno (il primo più i movimenti in mezzo)
     */
    public record Mismatch(Long accountId, String source, LocalDate from, LocalDate to, BigDecimal difference,
            String currency) {
    }

    /** I tipi di avviso. */
    public enum AlertKind {
        /** Il consenso dato alla banca è scaduto: i movimenti non si aggiornano più. */
        CONSENT_EXPIRED,
        /** Il consenso scade entro pochi giorni. */
        CONSENT_EXPIRING,
        /** L'ultima sincronizzazione di un conto è fallita. */
        SYNC_FAILED,
        /** Uno o più budget del mese in corso sono superati. */
        BUDGET_OVER,
        /** Due saldi di un conto non tornano con i movimenti arrivati in mezzo: ne manca uno, o ce n'è uno in più. */
        BALANCE_MISMATCH
    }

    /** Quanto è urgente un avviso: gli avvisi arrivano in quest'ordine, dal più urgente. */
    public enum Severity {
        CRITICAL,
        WARNING
    }
}
