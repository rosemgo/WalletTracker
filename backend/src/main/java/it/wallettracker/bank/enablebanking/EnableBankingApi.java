package it.wallettracker.bank.enablebanking;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Le strutture dati (DTO) delle API di Enable Banking, una per ogni oggetto JSON
 * che inviamo o riceviamo.
 *
 * <p>Usiamo i {@code record} di Java: classi immutabili dove basta elencare i campi.
 * Jackson (la libreria JSON usata da Spring) converte automaticamente JSON ↔ record.
 * I campi del JSON che non elenchiamo vengono semplicemente ignorati.
 *
 * <p>Il JSON di Enable Banking usa nomi come {@code session_id} (snake_case), Java usa
 * {@code sessionId} (camelCase): l'annotazione {@code @JsonNaming(SnakeCaseStrategy.class)}
 * fa la conversione tra i due.
 *
 * @see <a href="https://enablebanking.com/docs/api/reference/">Riferimento delle API</a>
 */
public final class EnableBankingApi {

    private EnableBankingApi() {
    }

    // ---------- Banche ----------

    /**
     * Una banca. Nella terminologia PSD2 si chiama ASPSP (Account Servicing Payment Service Provider).
     * <ul>
     *   <li>{@code maximumConsentValidity}: durata massima del consenso, in secondi;</li>
     *   <li>{@code requiredPsuHeaders}: gli header da inviare per dire alla banca che l'utente è
     *       presente (vedi {@link PsuHeaders}).</li>
     * </ul>
     */
    @JsonNaming(SnakeCaseStrategy.class)
    public record Aspsp(String name, String country, List<String> psuTypes, Long maximumConsentValidity,
            List<String> requiredPsuHeaders) {
    }

    /** Risposta di GET /aspsps. */
    public record AspspList(List<Aspsp> aspsps) {
    }

    /** Riferimento a una banca: nome + paese. */
    public record AspspRef(String name, String country) {
    }

    // ---------- Autorizzazione (consenso PSD2) ----------

    /** Richiesta di POST /auth: "voglio leggere i conti di questa banca fino alla data validUntil". */
    @JsonNaming(SnakeCaseStrategy.class)
    public record AuthorizationRequest(Access access, AspspRef aspsp, String state, String redirectUrl,
            String psuType) {
    }

    @JsonNaming(SnakeCaseStrategy.class)
    public record Access(Instant validUntil) {
    }

    /** Risposta di POST /auth: {@code url} è la pagina in cui l'utente accede alla propria banca. */
    @JsonNaming(SnakeCaseStrategy.class)
    public record AuthorizationResponse(String url, String authorizationId) {
    }

    /** Richiesta di POST /sessions: il codice ricevuto al termine del login sulla banca. */
    public record SessionRequest(String code) {
    }

    /** Risposta di POST /sessions: la sessione e i conti che l'utente ha autorizzato. */
    @JsonNaming(SnakeCaseStrategy.class)
    public record Session(String sessionId, List<Account> accounts, AspspRef aspsp, Access access) {
    }

    // ---------- Conti, saldi, movimenti ----------

    /** Un conto. {@code uid} è l'identificativo da usare nelle chiamate successive (non è l'IBAN). */
    @JsonNaming(SnakeCaseStrategy.class)
    public record Account(String uid, AccountId accountId, String name, String currency) {
    }

    public record AccountId(String iban) {
    }

    /** Un importo. L'API lo invia come stringa ("12.34"): lo leggiamo come BigDecimal, mai double per i soldi. */
    public record Amount(BigDecimal amount, String currency) {
    }

    /** Risposta di GET /accounts/{uid}/balances. */
    public record BalanceList(List<Balance> balances) {
    }

    /** Un saldo. Il tipo segue lo standard ISO 20022, es. CLBD = saldo contabile, ITAV = disponibile. */
    @JsonNaming(SnakeCaseStrategy.class)
    public record Balance(String name, Amount balanceAmount, String balanceType, LocalDate referenceDate) {
    }

    /** Risposta di GET /accounts/{uid}/transactions: se c'è una continuationKey, esistono altre pagine. */
    @JsonNaming(SnakeCaseStrategy.class)
    public record TransactionPage(List<Transaction> transactions, String continuationKey) {
    }

    /**
     * Un movimento.
     * <ul>
     *   <li>{@code creditDebitIndicator}: DBIT = uscita, CRDT = entrata (l'importo è sempre positivo);</li>
     *   <li>{@code status}: BOOK = contabilizzato, PDNG = in attesa (può ancora cambiare);</li>
     *   <li>{@code creditor} / {@code debtor}: chi riceve / chi invia i soldi;</li>
     *   <li>{@code remittanceInformation}: la causale.</li>
     * </ul>
     */
    @JsonNaming(SnakeCaseStrategy.class)
    public record Transaction(
            String entryReference,
            Amount transactionAmount,
            String creditDebitIndicator,
            String status,
            LocalDate bookingDate,
            LocalDate valueDate,
            Party creditor,
            Party debtor,
            List<String> remittanceInformation) {

        /** L'importo con il segno: negativo per le uscite, positivo per le entrate. */
        public BigDecimal signedAmount() {
            BigDecimal amount = transactionAmount.amount().abs();
            return "DBIT".equals(creditDebitIndicator) ? amount.negate() : amount;
        }
    }

    public record Party(String name) {
    }
}
