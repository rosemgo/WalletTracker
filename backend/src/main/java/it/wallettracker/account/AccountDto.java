package it.wallettracker.account;

import java.time.Instant;

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
 * @param bank              il nome della banca (es. "ING")
 * @param consentValidUntil fino a quando vale il consenso dato alla banca
 * @param lastSyncError     il motivo dell'ultimo errore di importazione, null se è andata bene
 */
public record AccountDto(
        Long id,
        String bank,
        String name,
        String iban,
        String currency,
        AccountRole role,
        Instant consentValidUntil,
        Instant lastSyncAt,
        String lastSyncError) {

    /** Da entità a DTO. Va chiamato dentro una transazione, perché legge il collegamento (LAZY). */
    static AccountDto from(Account account) {
        return new AccountDto(
                account.getId(),
                account.getConnection().getAspspName(),
                account.getName(),
                account.getIban(),
                account.getCurrency(),
                account.getRole(),
                account.getConnection().getValidUntil(),
                account.getLastSyncAt(),
                account.getLastSyncError());
    }
}
