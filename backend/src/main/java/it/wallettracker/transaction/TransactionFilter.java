package it.wallettracker.transaction;

import java.time.LocalDate;

/**
 * I filtri dell'elenco dei movimenti. Un campo null (o false) vuol dire "filtro spento".
 *
 * @param accountId     solo i movimenti di questo conto
 * @param type          solo i movimenti di questo tipo
 * @param uncategorized solo i movimenti senza categoria
 * @param from          dal giorno (incluso)
 * @param to            al giorno (incluso)
 * @param text          il testo cercato: nella causale, nella controparte, nel nome della categoria, oppure un
 *                      importo ("12,99")
 * @param categoryId    solo i movimenti di questa categoria (docs/38: "Vedi i movimenti" di una categoria)
 */
public record TransactionFilter(Long accountId, TransactionType type, boolean uncategorized, LocalDate from,
        LocalDate to, String text, Long categoryId) {
}
