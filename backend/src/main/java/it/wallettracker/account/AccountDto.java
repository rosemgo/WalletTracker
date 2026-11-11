package it.wallettracker.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Un conto come lo vede la dashboard: è quello che le API REST restituiscono in JSON.
 *
 * <p>Perché non restituire direttamente l'entità {@link Account}? Per tre motivi:
 * <ul>
 *   <li><b>scegliamo noi cosa esce</b>: ad esempio {@code providerUid} e {@code externalKey} servono solo
 *       al backend, e la dashboard non li vede;</li>
 *   <li><b>il database può cambiare</b> senza cambiare le API (e viceversa);</li>
 *   <li><b>niente sorprese con Hibernate</b>: un'entità ha relazioni caricate "pigramente" (LAZY), che
 *       Jackson proverebbe a leggere fuori dalla transazione, con errori o query inattese.</li>
 * </ul>
 * È lo stesso ruolo dei DTO di Enable Banking ({@code EnableBankingApi}), ma nella direzione opposta:
 * quelli traducono ciò che <b>riceviamo</b>, questo ciò che <b>mandiamo</b>.
 *
 * @param label             come mostrare il conto (vedi Account.label): il nome scelto, oppure banca e IBAN
 * @param displayName       il nome scelto dall'utente, o null
 * @param bank              il nome della banca (es. "ING") o, per un conto manuale, dell'istituto
 * @param source            da dove arrivano i movimenti: Enable Banking o file CSV (conto manuale)
 * @param consentValidUntil fino a quando vale il consenso dato alla banca; null per un conto manuale
 * @param connectionRemovedAt quando l'utente ha rimosso il collegamento (docs/rimuovere-un-collegamento.md): il conto
 *                    resta come storia, ma non si aggiorna più; null se non è stato rimosso
 * @param lastSyncError     il motivo dell'ultimo errore di importazione, null se è andata bene
 * @param balance           il saldo (docs/saldi.md): dalla banca, o scritto dall'utente per un conto manuale; null se
 *                          non si sa
 * @param availableCredit   solo per una carta di credito: il plafond che resta, dato dalla banca (V35); null se non si
 *                          sa
 * @param logoVersion       se il conto ha un logo (docs/loghi.md): un numero che cambia quando cambia il logo, da mettere
 *                          nell'indirizzo dell'immagine ({@code /api/accounts/3/logo?v=...}); null senza logo
 */
public record AccountDto(
        Long id,
        String label,
        String displayName,
        String bank,
        String name,
        String iban,
        String currency,
        AccountRole role,
        AccountSource source,
        Instant consentValidUntil,
        Instant connectionRemovedAt,
        Instant lastSyncAt,
        String lastSyncError,
        CurrentBalance balance,
        AvailableCredit availableCredit,
        Long logoVersion) {

    /** Il plafond che resta su una carta di credito, e il giorno a cui si riferisce. */
    public record AvailableCredit(BigDecimal amount, LocalDate date) {
    }

    /** Da entità a DTO. Va chiamato dentro una transazione, perché legge il collegamento (LAZY). */
    static AccountDto from(Account account, CurrentBalance balance, Long logoVersion) {
        return new AccountDto(
                account.getId(),
                account.label(),
                account.getDisplayName(),
                account.getBankName(),
                account.getName(),
                account.getIban(),
                account.getCurrency(),
                account.getRole(),
                account.getSource(),
                account.getConnection() != null ? account.getConnection().getValidUntil() : null,
                account.getConnection() != null ? account.getConnection().getRemovedAt() : null,
                account.getLastSyncAt(),
                account.getLastSyncError(),
                balance,
                account.getRole() == AccountRole.CREDIT_CARD && account.getAvailableCredit() != null
                        ? new AvailableCredit(account.getAvailableCredit(), account.getAvailableCreditDate())
                        : null,
                logoVersion);
    }
}
