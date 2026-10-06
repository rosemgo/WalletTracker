package it.wallettracker.sync;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.account.AccountRole;
import it.wallettracker.bank.enablebanking.PsuHeaders;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.connection.BankConnection;
import it.wallettracker.connection.ConnectionService;
import it.wallettracker.transaction.TransactionImportService;
import it.wallettracker.transaction.TransactionImportService.ImportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * La sincronizzazione: importa i movimenti dei conti e li classifica, ricordando per ogni conto
 * quando è stato aggiornato l'ultima volta e con quale esito.
 *
 * <p>Ci sono due modi di usarla:
 * <ul>
 *   <li>{@link #syncAccount}: aggiorna <b>un</b> conto, subito. La usa il programma della Fase 0 (e nella
 *       Fase 2 il pulsante "Aggiorna ora"), con l'utente presente e quindi con gli header PSU;</li>
 *   <li>{@link #syncAllInBackground}: aggiorna tutti i conti "scaduti", senza header PSU. La chiama
 *       {@link SyncScheduler} a intervalli regolari.</li>
 * </ul>
 *
 * <p>Il limite delle letture in background (circa 4 al giorno per conto) lo rispettiamo con la colonna
 * {@code last_sync_at}: un conto viene aggiornato solo se l'ultimo tentativo è più vecchio di
 * {@code wallettracker.sync.interval}. La data sta nel database, quindi il limite vale anche se
 * l'applicazione viene riavviata dieci volte al giorno.
 */
@Service
public class SyncService {

    /**
     * Il logger scrive messaggi con data, ora e livello (INFO, WARN, ERROR...) al posto di System.out.
     * Quali livelli mostrare si decide in application.yml ({@code logging.level}), senza toccare il codice.
     */
    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final ConnectionService connectionService;
    private final AccountRepository accountRepository;
    private final TransactionImportService importService;
    private final ClassificationService classificationService;
    private final SyncProperties properties;

    public SyncService(ConnectionService connectionService, AccountRepository accountRepository,
            TransactionImportService importService, ClassificationService classificationService,
            SyncProperties properties) {
        this.connectionService = connectionService;
        this.accountRepository = accountRepository;
        this.importService = importService;
        this.classificationService = classificationService;
        this.properties = properties;
    }

    /**
     * L'esito dell'aggiornamento di un conto: il risultato dell'importazione se è andata bene,
     * altrimenti il motivo dell'errore.
     */
    public record AccountSync(ImportResult result, String error) {

        public boolean ok() {
            return error == null;
        }
    }

    /** Il riepilogo di un giro di sincronizzazione automatica: quanti conti per ogni esito. */
    public record Round(int updated, int failed, int notDue) {
    }

    /**
     * Aggiorna un conto e salva l'esito ({@code last_sync_at}, {@code last_sync_error}).
     *
     * <p>L'ordine dei {@code catch} conta: Java usa il primo che corrisponde, quindi si va dal più specifico
     * ({@link RestClientResponseException}, la banca ha risposto con un errore) al più generico.
     *
     * <p>Gli errori <b>non</b> vengono rilanciati: un conto che non risponde non deve bloccare gli altri.
     * Chi chiama trova il motivo in {@link AccountSync#error()}.
     *
     * <p>Questo metodo volutamente <b>non</b> è {@code @Transactional}: l'importazione ha la sua
     * transazione, e se fallisce viene annullata; l'esito lo salviamo poi in una transazione separata
     * ({@link AccountRepository#recordSync}). Se fosse tutto in una sola transazione, l'errore
     * dell'importazione la segnerebbe come "da annullare" e perderemmo anche l'esito.
     *
     * @param psu gli header dell'utente presente, oppure {@code null} per un aggiornamento in background
     */
    public AccountSync syncAccount(Account account, PsuHeaders psu) {
        String error;
        try {
            ImportResult result = importService.importAccount(account, psu);
            accountRepository.recordSync(account.getId(), Instant.now(), null);
            log.info("{}: {} nuovi, {} già presenti, {} in attesa", account.getName(), result.inserted(),
                    result.alreadyPresent(), result.pending());
            return new AccountSync(result, null);
        } catch (RestClientResponseException e) {
            // Errore previsto: la banca o Enable Banking hanno risposto con un errore (es. 429, troppe letture).
            error = "HTTP " + e.getStatusCode().value() + " " + e.getResponseBodyAsString();
            log.warn("{}: importazione non riuscita: {}", account.getName(), error);
        } catch (RestClientException e) {
            // Errore previsto: nessuna risposta (rete assente, Enable Banking non raggiungibile...).
            error = "Enable Banking non raggiungibile: " + e.getMessage();
            log.warn("{}: importazione non riuscita: {}", account.getName(), error);
        } catch (RuntimeException e) {
            // Errore imprevisto (un bug...): lo registriamo con tutti i dettagli (lo "stack trace").
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("{}: importazione non riuscita", account.getName(), e);
        }
        // Salviamo anche la data del tentativo fallito: così non riproviamo prima del prossimo intervallo
        // (riprovare subito dopo un 429 servirebbe solo a prenderne un altro).
        accountRepository.recordSync(account.getId(), Instant.now(), error);
        return new AccountSync(null, error);
    }

    /**
     * Un giro di sincronizzazione automatica: aggiorna, senza header PSU, tutti i conti non esclusi il cui
     * ultimo tentativo è più vecchio dell'intervallo. Se almeno un conto è stato aggiornato, riclassifica
     * tutti i movimenti (i trasferimenti hanno due lati, magari su conti diversi).
     */
    public Round syncAllInBackground() {
        Instant now = Instant.now();
        int updated = 0;
        int failed = 0;
        int notDue = 0;

        for (BankConnection connection : connectionService.allConnections()) {
            for (Account account : connectionService.accountsOf(connection)) {
                if (account.getRole() == AccountRole.EXCLUDED) {
                    continue;
                }
                if (!isDue(account, now)) {
                    notDue++;
                    continue;
                }
                if (!connection.isValid()) {
                    // Consenso scaduto: chiamare la banca darebbe solo errore. Lo segnaliamo e basta.
                    String error = "consenso scaduto il "
                            + LocalDate.ofInstant(connection.getValidUntil(), ZoneId.systemDefault())
                            + ": ricollega " + connection.getAspspName();
                    accountRepository.recordSync(account.getId(), now, error);
                    log.warn("{}: {}", account.getName(), error);
                    failed++;
                    continue;
                }

                if (syncAccount(account, null).ok()) {
                    updated++;
                } else {
                    failed++;
                }
            }
        }

        if (updated > 0) {
            classificationService.classifyAll();
        }
        return new Round(updated, failed, notDue);
    }

    /** True se il conto non è mai stato aggiornato o se l'ultimo tentativo è più vecchio dell'intervallo. */
    boolean isDue(Account account, Instant now) {
        Instant last = account.getLastSyncAt();
        return last == null || !last.isAfter(now.minus(properties.interval()));
    }
}
