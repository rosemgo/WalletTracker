package it.wallettracker.account;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Il saldo di un conto come lo mostra la dashboard (docs/saldi.md).
 *
 * @param amount      il saldo contabile: senza i movimenti in attesa (parte O)
 * @param date        il giorno a cui si riferisce: per la banca, il suo; per un conto manuale, l'ultimo movimento contato
 * @param source      dalla banca o scritto dall'utente
 * @param startDate   solo per un conto manuale: il giorno del saldo scritto dall'utente, da cui si parte
 * @param withPending il saldo con i movimenti in attesa di adesso, se ce ne sono (null se non ce ne sono): quello che di
 *                    solito mostra l'app della banca
 */
public record CurrentBalance(BigDecimal amount, LocalDate date, BalanceSource source, LocalDate startDate,
        BigDecimal withPending) {

    /** Un saldo senza movimenti in attesa. */
    public CurrentBalance(BigDecimal amount, LocalDate date, BalanceSource source, LocalDate startDate) {
        this(amount, date, source, startDate, null);
    }

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
        return of(account, after, lastMovement, BigDecimal.ZERO);
    }

    /**
     * Come {@link #of(Account, BigDecimal, LocalDate)}, con la somma dei movimenti in attesa di adesso ({@code pending}):
     * se non è zero, {@code withPending} è il saldo con loro.
     */
    static CurrentBalance of(Account account, BigDecimal after, LocalDate lastMovement, BigDecimal pending) {
        CurrentBalance booked = booked(account, after, lastMovement);
        if (booked == null || pending == null || pending.signum() == 0) {
            return booked;
        }
        return new CurrentBalance(booked.amount(), booked.date(), booked.source(), booked.startDate(),
                booked.amount().add(pending));
    }

    private static CurrentBalance booked(Account account, BigDecimal after, LocalDate lastMovement) {
        if (account.getBalance() == null || account.getBalanceSource() == null) {
            return null;
        }
        LocalDate start = account.getBalanceDate();
        LocalDate date = lastMovement != null && (start == null || lastMovement.isAfter(start)) ? lastMovement : start;
        // Dal saldo contabile: quello letto meno i movimenti in attesa che comprendeva (parte O).
        BigDecimal amount = account.getBookedBalance().add(after == null ? BigDecimal.ZERO : after);
        return account.getBalanceSource() == BalanceSource.BANK
                ? new CurrentBalance(amount, date, BalanceSource.BANK, null)
                : new CurrentBalance(amount, date, BalanceSource.MANUAL, start);
    }
}
