package it.wallettracker.connection;

import java.time.Instant;
import java.util.List;

import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.bank.enablebanking.EnableBankingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * L'elenco dei collegamenti e "Rimuovi collegamento" (docs/rimuovere-un-collegamento.md).
 *
 * <p>Rimuovere un collegamento <b>non cancella la storia</b>:
 * <ol>
 *   <li>si chiude il consenso su Enable Banking ({@code DELETE /sessions/{id}}), così nessuno legge più quei conti;</li>
 *   <li>se il collegamento ha dei conti, si segna come rimosso: i conti e i loro movimenti restano (budget, analisi e
 *       giroconti abbinati compresi), ma l'app non li aggiorna più e non avvisa della scadenza;</li>
 *   <li>se non ha conti (un tentativo in modalità ristretta, docs/modalita-ristretta.md), non c'è storia da tenere:
 *       si cancella.</li>
 * </ol>
 * Ricollegando la banca, i conti si riconoscono dalla loro chiave stabile e passano al collegamento nuovo
 * ({@link ConnectionService#saveSession}): stessi conti, quindi nessun movimento doppio.
 */
@Service
public class ConnectionRemovalService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRemovalService.class);

    private final BankConnectionRepository connectionRepository;
    private final AccountRepository accountRepository;
    private final EnableBankingClient client;
    private final TransactionTemplate transaction;

    public ConnectionRemovalService(BankConnectionRepository connectionRepository, AccountRepository accountRepository,
            EnableBankingClient client, TransactionTemplate transaction) {
        this.connectionRepository = connectionRepository;
        this.accountRepository = accountRepository;
        this.client = client;
        this.transaction = transaction;
    }

    /**
     * Un collegamento, come lo mostra la pagina Conti.
     *
     * @param accountNames i nomi dei suoi conti (quello scelto dall'utente, se c'è): la conferma della rimozione li
     *                     elenca, perché si rimuovono tutti insieme
     */
    public record ConnectionDto(Long id, String bank, String country, Instant validUntil, Instant removedAt,
            int accounts, List<String> accountNames) {
    }

    /**
     * L'esito di una rimozione.
     *
     * @param accountsKept quanti conti restano come storia (0: il collegamento era vuoto ed è stato cancellato)
     * @param revoked      true se il consenso su Enable Banking è chiuso (o lo era già); false se non si è riusciti a
     *                     chiuderlo: allora va revocato dal pannello di Enable Banking
     */
    public record Removal(String bank, int accountsKept, boolean revoked) {
    }

    /** Tutti i collegamenti, per banca e dal più vecchio, con quanti conti hanno. */
    public List<ConnectionDto> connections() {
        return transaction.execute(status -> connectionRepository.findAllByOrderByAspspNameAscCreatedAtAsc().stream()
                .map(c -> {
                    List<Account> accounts = accountRepository.findByConnectionOrderByName(c);
                    return new ConnectionDto(c.getId(), c.getAspspName(), c.getAspspCountry(), c.getValidUntil(),
                            c.getRemovedAt(), accounts.size(), accounts.stream().map(
                                    ConnectionRemovalService::nameOf).toList());
                })
                .toList());
    }

    /** Il nome di un conto in un elenco della stessa banca: quello scelto dall'utente, o quello della banca. */
    private static String nameOf(Account account) {
        if (account.getDisplayName() != null) {
            return account.getDisplayName();
        }
        return account.getName() != null ? account.getName() : account.label();
    }

    /**
     * Rimuove un collegamento. La chiamata a Enable Banking avviene fuori dalla transazione: una risposta lenta non
     * deve tenere bloccato il database.
     */
    public Removal remove(Long id, Instant now) {
        BankConnection connection = connectionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Collegamento non trovato."));
        if (connection.isRemoved()) {
            return new Removal(connection.getAspspName(), accountRepository.findByConnectionOrderByName(connection)
                    .size(), true);
        }

        boolean revoked = revoke(connection, now);

        int kept = transaction.execute(status -> {
            BankConnection current = connectionRepository.findById(id).orElseThrow();
            int accounts = accountRepository.findByConnectionOrderByName(current).size();
            if (accounts == 0) {
                connectionRepository.delete(current);
            } else {
                current.markRemoved(now);
            }
            return accounts;
        });
        log.info("{}: collegamento rimosso ({} conti tenuti come storia, consenso chiuso: {})",
                connection.getAspspName(), kept, revoked);
        return new Removal(connection.getAspspName(), kept, revoked);
    }

    /** Chiude il consenso su Enable Banking. True se è chiuso, anche perché lo era già. */
    private boolean revoke(BankConnection connection, Instant now) {
        if (!connection.getValidUntil().isAfter(now)) {
            return true; // scaduto: la sessione non vale più, non c'è niente da chiudere
        }
        try {
            client.deleteSession(connection.getSessionId());
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return true; // Enable Banking non la conosce più: già chiusa (per esempio dal pannello)
            }
            log.warn("{}: Enable Banking non ha chiuso la sessione (HTTP {}): {}", connection.getAspspName(),
                    e.getStatusCode().value(), e.getResponseBodyAsString());
            return false;
        } catch (RestClientException | ResponseStatusException e) {
            // Nessuna risposta, o Enable Banking non configurato: il collegamento si rimuove lo stesso, e la pagina
            // dice di chiudere il consenso dal pannello.
            log.warn("{}: non sono riuscito a chiudere la sessione su Enable Banking: {}", connection.getAspspName(),
                    e.getMessage());
            return false;
        }
    }
}
