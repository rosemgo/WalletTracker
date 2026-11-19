package it.wallettracker.account;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Il saldo di un conto come lo mostra la dashboard (docs/saldi.md).
 *
 * @param amount    il saldo con i movimenti in attesa di adesso (parte O): i pagamenti in attesa sono già spese, come nel
 *                  Riepilogo e nei budget, ed è il numero che mostra l'app della banca
 * @param date      il giorno a cui si riferisce: per la banca, il suo; per un conto manuale, l'ultimo movimento contato
 * @param source    dalla banca o scritto dall'utente
 * @param startDate solo per un conto manuale: il giorno del saldo scritto dall'utente, da cui si parte
 * @param booked    il saldo contabile, senza i movimenti in attesa, se ce ne sono (null se non ce ne sono: allora è
 *                  {@code amount})
 */
public record CurrentBalance(BigDecimal amount, LocalDate date, BalanceSource source, LocalDate startDate,
        BigDecimal booked) {

    /** Il saldo contabile: lo storico, le fotografie e il controllo dei saldi lavorano con questo (parte O). */
    public BigDecimal bookedAmount() {
        return booked != null ? booked : amount;
    }

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
     * se non è zero, il saldo è quello contabile più loro, e il contabile resta in {@code booked}.
     */
    static CurrentBalance of(Account account, BigDecimal after, LocalDate lastMovement, BigDecimal pending) {
        CurrentBalance booked = booked(account, after, lastMovement);
        // Un saldo salvato prima della V45 non dice se comprendeva i movimenti in attesa (parte P): aggiungerli potrebbe
        // contarli due volte. Si mostra com'è, finché la banca non lo ridà.
        if (booked == null || pending == null || pending.signum() == 0 || !account.isPendingKnown()) {
            return booked;
        }
        return new CurrentBalance(booked.amount().add(pending), booked.date(), booked.source(), booked.startDate(),
                booked.amount());
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
