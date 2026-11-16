package it.wallettracker.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * La risposta di {@code GET /api/accounts/balance-history}: la liquidità giorno per giorno in una valuta, dal più
 * vecchio a oggi (docs/saldi.md, parte L). Vuota se nessun conto di quella valuta ha un saldo.
 *
 * @param accounts quanti conti sono sommati
 * @param points   un punto per giorno
 */
public record BalanceHistory(String currency, int accounts, List<Point> points) {

    /**
     * Un giorno.
     *
     * @param amount    la somma dei saldi dei conti a fine giornata
     * @param estimated true se almeno un conto, quel giorno, non ha una fotografia: il suo saldo è stimato togliendo i
     *                  movimenti dal valore vero successivo
     */
    public record Point(LocalDate date, BigDecimal amount, boolean estimated) {
    }
}
