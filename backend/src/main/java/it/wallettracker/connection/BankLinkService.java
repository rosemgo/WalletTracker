package it.wallettracker.connection;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRole;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Access;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Aspsp;
import it.wallettracker.bank.enablebanking.EnableBankingApi.AspspRef;
import it.wallettracker.bank.enablebanking.EnableBankingApi.AuthorizationRequest;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Session;
import it.wallettracker.bank.enablebanking.EnableBankingClient;
import it.wallettracker.bank.enablebanking.EnableBankingProperties;
import it.wallettracker.bank.enablebanking.PsuHeaders;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.sync.SyncService;
import it.wallettracker.sync.SyncService.AccountSync;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Il collegamento di una banca, in due tempi (lo stesso flusso del programma della Fase 0, ma dalla dashboard):
 *
 * <ol>
 *   <li>{@link #start}: chiediamo a Enable Banking il link per il login sulla banca, e ci segniamo un
 *       {@code state} casuale. Il browser dell'utente va sul sito della banca;</li>
 *   <li>dopo il login (e la SCA), la banca rimanda il browser al nostro indirizzo di ritorno
 *       ({@code redirect-url}) con due parametri: {@code code} e lo stesso {@code state};</li>
 *   <li>{@link #complete}: controlliamo che lo {@code state} sia uno dei nostri, scambiamo il {@code code}
 *       con una sessione, salviamo collegamento e conti e importiamo subito i movimenti.</li>
 * </ol>
 *
 * <p>Perché lo {@code state}? Protegge da un attacco chiamato CSRF: senza, un sito malevolo potrebbe far
 * arrivare al nostro indirizzo di ritorno un {@code code} suo, e farci collegare un conto che non è il tuo.
 * Accettiamo solo gli {@code state} che abbiamo generato noi, una volta sola e per 30 minuti.
 */
@Service
public class BankLinkService {

    /** Durata massima di un consenso PSD2 per la lettura dei conti. */
    static final Duration MAX_CONSENT = Duration.ofDays(180);

    /** Quanto tempo ha l'utente per completare il login sulla banca. */
    static final Duration PENDING_EXPIRY = Duration.ofMinutes(30);

    /** Un collegamento avviato e non ancora completato. */
    private record Pending(Instant validUntil, Instant startedAt) {
    }

    /**
     * Il risultato di un collegamento completato.
     *
     * @param accounts quanti conti sono stati autorizzati
     * @param imported quanti movimenti nuovi sono stati importati
     * @param errors   gli errori di importazione, conto per conto (il collegamento è comunque salvato)
     */
    public record LinkResult(String bank, int accounts, int imported, List<String> errors) {
    }

    /**
     * I collegamenti in attesa, per {@code state}. Una {@link ConcurrentHashMap} perché più richieste HTTP
     * possono usarla nello stesso momento. Sta in memoria: se il backend si riavvia durante il login,
     * basta ricominciare.
     */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private final EnableBankingClient client;
    private final EnableBankingProperties properties;
    private final ConnectionService connectionService;
    private final SyncService syncService;
    private final ClassificationService classificationService;

    public BankLinkService(EnableBankingClient client, EnableBankingProperties properties,
            ConnectionService connectionService, SyncService syncService,
            ClassificationService classificationService) {
        this.client = client;
        this.properties = properties;
        this.connectionService = connectionService;
        this.syncService = syncService;
        this.classificationService = classificationService;
    }

    /** Le banche di un paese supportate da Enable Banking, in ordine alfabetico. */
    public List<Aspsp> banks(String country) {
        return client.listAspsps(country).stream()
                .sorted(Comparator.comparing(Aspsp::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Avvia il collegamento: restituisce l'indirizzo della pagina di login della banca.
     *
     * @param host il nome con cui è aperta l'app (header Host): alla fine la banca rimanda il browser lì, con
     *             l'indirizzo di ritorno di quel nome ({@link EnableBankingProperties#redirectUrlFor})
     */
    public String start(String bankName, String country, String host) {
        Aspsp bank = banks(country).stream()
                .filter(b -> b.name().equals(bankName))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Banca " + bankName + " (" + country + ") non trovata"));

        Instant validUntil = consentValidUntil(bank, Instant.now());
        String state = UUID.randomUUID().toString();
        String url = client.startAuthorization(new AuthorizationRequest(new Access(validUntil),
                new AspspRef(bank.name(), bank.country()), state, properties.redirectUrlFor(host), "personal")).url();

        removeExpired();
        pending.put(state, new Pending(validUntil, Instant.now()));
        return url;
    }

    /**
     * Completa il collegamento dopo il login sulla banca: salva collegamento e conti, importa i movimenti
     * (con l'utente presente: header PSU) e riclassifica tutto.
     */
    public LinkResult complete(String code, String state, PsuHeaders psu) {
        // remove(): uno state si può usare una volta sola.
        //il remove() verifica se la chiave è presente nell'hashmap e se è presente, restituisce l'oggetto e rimuove la coppia chiave, valore dalla mappa
        Pending started = state != null ? pending.remove(state) : null;
        if (started == null || isExpired(started)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Questo collegamento non è valido o è scaduto: ricomincia da \"Collega una banca\".");
        }

        Session session = client.createSession(code);
        BankConnection connection = connectionService.saveSession(session, started.validUntil());

        List<Account> accounts = connectionService.accountsOf(connection);
        int imported = 0;
        List<String> errors = new ArrayList<>();
        for (Account account : accounts) {
            if (account.getRole() == AccountRole.EXCLUDED) {
                continue;
            }
            AccountSync sync = syncService.syncAccount(account, psu);
            if (sync.ok()) {
                imported += sync.result().inserted();
            } else {
                errors.add(account.getName() + ": " + sync.error());
            }
        }
        classificationService.classifyAll();
        return new LinkResult(connection.getAspspName(), accounts.size(), imported, errors);
    }

    /**
     * Fino a quando chiedere il consenso: la durata massima ammessa dalla banca, senza superare i 180 giorni,
     * meno 5 minuti di margine (per non superare il limite a causa di orologi non allineati).
     */
    public static Instant consentValidUntil(Aspsp bank, Instant now) {
        Duration validity = MAX_CONSENT;
        if (bank.maximumConsentValidity() != null && bank.maximumConsentValidity() < MAX_CONSENT.toSeconds()) {
            validity = Duration.ofSeconds(bank.maximumConsentValidity());
        }
        return now.plus(validity).minus(Duration.ofMinutes(5)).truncatedTo(ChronoUnit.SECONDS);
    }

    private static boolean isExpired(Pending started) {
        return started.startedAt().plus(PENDING_EXPIRY).isBefore(Instant.now());
    }

    /** Toglie i collegamenti avviati e mai completati (l'utente ha chiuso la pagina della banca). */
    private void removeExpired() {
        pending.values().removeIf(BankLinkService::isExpired);
    }
}
