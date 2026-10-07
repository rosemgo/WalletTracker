package it.wallettracker.transaction;

import java.util.List;

/**
 * Una "pagina" di movimenti. Con migliaia di movimenti non li mandiamo tutti insieme: la dashboard
 * chiede la pagina 0, poi la 1, e così via.
 *
 * @param page       il numero della pagina (si parte da 0)
 * @param size       quanti movimenti al massimo per pagina
 * @param totalItems quanti movimenti ci sono in tutto, con i filtri scelti
 * @param totalPages quante pagine ci sono in tutto
 */
public record TransactionPageDto(List<TransactionDto> items, int page, int size, long totalItems, int totalPages) {
}
