package it.wallettracker.account;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Il saldo di un conto come lo mostra la dashboard (docs/saldi.md).
 *
 * @param amount    il saldo
 * @param date      il giorno a cui si riferisce: per la banca, il suo; per un conto manuale, l'ultimo movimento contato
 * @param source    dalla banca o scritto dall'utente
 * @param startDate solo per un conto manuale: il giorno del saldo scritto dall'utente, da cui si parte
 */
public record CurrentBalance(BigDecimal amount, LocalDate date, BalanceSource source, LocalDate startDate) {

    /**
     * Il saldo di oggi, da quello salvato. Per la banca è quello e basta. Per un conto manuale si parte dal saldo
     * scritto dall'utente e si aggiungono i movimenti importati dopo ({@code after}): il giorno diventa l'ultimo
     * movimento ({@code lastMovement}), se è più recente. Null se il conto non ha un saldo.
     */
    static CurrentBalance of(Account account, BigDecimal after, LocalDate lastMovement) {
        if (account.getBalance() == null || account.getBalanceSource() == null) {
            return null;
        }
        if (account.getBalanceSource() == BalanceSource.BANK) {
            return new CurrentBalance(account.getBalance(), account.getBalanceDate(), BalanceSource.BANK, null);
        }
        LocalDate start = account.getBalanceDate();
        LocalDate date = lastMovement != null && lastMovement.isAfter(start) ? lastMovement : start;
        return new CurrentBalance(account.getBalance().add(after), date, BalanceSource.MANUAL, start);
    }
}
