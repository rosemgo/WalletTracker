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
     * Il saldo di oggi, da quello salvato: il saldo (della banca o scritto dall'utente) più i movimenti importati dopo
     * ({@code after}); il giorno diventa l'ultimo movimento ({@code lastMovement}), se è più recente. Null se il conto
     * non ha un saldo.
     *
     * <p>Anche per la banca (docs/saldi.md, parte M): in background la banca si rilegge al massimo ogni 20 ore, i
     * movimenti ogni 6. Una cedola arrivata dopo l'ultima lettura non è nel saldo della banca, e senza aggiungerla la
     * pagina Conti mostrerebbe un saldo vecchio fino alla lettura successiva. Quando la banca ridà il saldo, si riparte da
     * quello.
     */
    static CurrentBalance of(Account account, BigDecimal after, LocalDate lastMovement) {
        if (account.getBalance() == null || account.getBalanceSource() == null) {
            return null;
        }
        LocalDate start = account.getBalanceDate();
        LocalDate date = lastMovement != null && (start == null || lastMovement.isAfter(start)) ? lastMovement : start;
        BigDecimal amount = account.getBalance().add(after == null ? BigDecimal.ZERO : after);
        return account.getBalanceSource() == BalanceSource.BANK
                ? new CurrentBalance(amount, date, BalanceSource.BANK, null)
                : new CurrentBalance(amount, date, BalanceSource.MANUAL, start);
    }
}
