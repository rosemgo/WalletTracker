package it.wallettracker.connection;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.bank.enablebanking.EnableBankingApi;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Logica dei collegamenti bancari: salva una sessione appena autorizzata e i suoi conti.
 *
 * <p>È il punto dove i DTO di Enable Banking ({@link EnableBankingApi}) vengono tradotti nelle
 * nostre entità ({@link BankConnection}, {@link Account}). Il resto dell'applicazione userà
 * solo le entità.
 *
 * <p>{@code @Service} è come {@code @Component}, ma dice a chi legge che qui c'è logica di business.
 */
@Service
public class ConnectionService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);

    private final BankConnectionRepository connectionRepository;
    private final AccountRepository accountRepository;

    public ConnectionService(BankConnectionRepository connectionRepository, AccountRepository accountRepository) {
        this.connectionRepository = connectionRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Salva una sessione appena creata e i suoi conti.
     *
     * <p>Se un conto esiste già (lo riconosciamo dalla chiave stabile), non ne creiamo un altro:
     * lo spostiamo sul nuovo collegamento e aggiorniamo il suo uid. Così, rinnovando il consenso,
     * i conti e in futuro i loro movimenti restano gli stessi.
     *
     * <p>{@code @Transactional}: tutte le scritture avvengono in un'unica transazione.
     * Se qualcosa va storto a metà, il database torna com'era prima (niente dati a metà).
     *
     * @param requestedValidUntil la scadenza chiesta in POST /auth, usata se la banca non la restituisce
     */
    @Transactional
    public BankConnection saveSession(Session session, Instant requestedValidUntil) {
        Instant validUntil = session.access() != null && session.access().validUntil() != null
                ? session.access().validUntil()
                : requestedValidUntil;

        BankConnection connection = connectionRepository.save(new BankConnection(
                session.aspsp().name(), session.aspsp().country(), session.sessionId(), validUntil));

        log.info("{}: Enable Banking ha passato {} conti{}", session.aspsp().name(), session.accounts().size(),
                describe(session.accounts()));
        Set<String> usedKeys = new HashSet<>();
        for (EnableBankingApi.Account apiAccount : session.accounts()) {
            String externalKey = externalKeyOf(session.aspsp().name(), apiAccount);
            String iban = apiAccount.accountId() != null ? apiAccount.accountId().iban() : null;
            Optional<Account> known = accountRepository.findByExternalKey(externalKey);
            // Mai fondere due conti diversi (docs/modalita-ristretta.md, parte D): se l'impronta è già di un altro conto
            // della stessa sessione, o di un conto salvato con un altro IBAN, ci si fida dell'IBAN.
            if (usedKeys.contains(externalKey)
                    || known.filter(account -> differentIban(account.getIban(), iban)).isPresent()) {
                String fallback = keyWithoutHash(session.aspsp().name(), apiAccount);
                log.warn("{}: due conti con la stessa impronta (conto {}): uso banca + IBAN per distinguerli",
                        session.aspsp().name(), lastDigits(iban));
                externalKey = usedKeys.contains(fallback) ? session.aspsp().name() + "|uid:" + apiAccount.uid()
                        : fallback;
                known = accountRepository.findByExternalKey(externalKey);
            }
            usedKeys.add(externalKey);
            String key = externalKey;

            known.ifPresentOrElse(
                    // Conto già noto: aggiorniamo collegamento e uid. Non serve chiamare save():
                    // dentro una transazione Hibernate salva da solo le modifiche agli oggetti caricati.
                    (Account existing) -> {
                        existing.moveTo(connection, apiAccount.uid());
                        //accountRepository.save(existing); //NON SERVE, leggi commento sopra
                    },
                    // Conto nuovo: lo creiamo.
                    () -> {
                        accountRepository.save(new Account(connection, key, apiAccount.uid(), iban,
                                apiAccount.name(), apiAccount.currency()));
                    });
        }

        // I vecchi collegamenti della stessa banca rimasti senza conti (perché li abbiamo appena
        // spostati su quello nuovo) non servono più: li cancelliamo, così l'elenco resta pulito.
        for (BankConnection old : connectionRepository.findByAspspNameAndAspspCountry(
                connection.getAspspName(), connection.getAspspCountry())) {
            if (!old.getId().equals(connection.getId()) && !accountRepository.existsByConnection(old)) {
                connectionRepository.delete(old);
            }
        }
        return connection;
    }

    /** I collegamenti con il consenso ancora valido. */
    @Transactional(readOnly = true)
    public List<BankConnection> validConnections() {
        return connectionRepository.findByRemovedAtIsNullAndValidUntilAfterOrderByAspspName(Instant.now());
    }

    /** Tutti i collegamenti, anche quelli con il consenso scaduto. */
    @Transactional(readOnly = true)
    public List<BankConnection> allConnections() {
        return connectionRepository.findAll();
    }

    /** I conti di un collegamento. */
    @Transactional(readOnly = true)
    public List<Account> accountsOf(BankConnection connection) {
        return accountRepository.findByConnectionOrderByName(connection);
    }

    /**
     * La chiave stabile di un conto, in ordine di preferenza:
     * <ol>
     *   <li>l'impronta fornita da Enable Banking ({@code identification_hash});</li>
     *   <li>banca + IBAN + valuta (Revolut usa lo stesso IBAN per più valute, per questo c'è la valuta);</li>
     *   <li>banca + uid: è l'ultima risorsa (es. carte senza IBAN), perché l'uid cambia a ogni consenso.</li>
     * </ol>
     */
    static String externalKeyOf(String aspspName, EnableBankingApi.Account account) {
        if (account.identificationHash() != null && !account.identificationHash().isBlank()) {
            return account.identificationHash();
        }
        return keyWithoutHash(aspspName, account);
    }

    /** La chiave senza l'impronta: banca + IBAN + valuta, o banca + uid. */
    static String keyWithoutHash(String aspspName, EnableBankingApi.Account account) {
        String iban = account.accountId() != null ? account.accountId().iban() : null;
        if (iban != null && !iban.isBlank()) {
            return aspspName + "|" + iban + "|" + account.currency();
        }
        return aspspName + "|uid:" + account.uid();
    }

    /** Due IBAN noti e diversi (senza badare a spazi e maiuscole)? Se uno manca non si può dire: non sono "diversi". */
    static boolean differentIban(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        return !normalize(a).equals(normalize(b));
    }

    private static String normalize(String iban) {
        return iban.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    }

    /**
     * I conti passati da Enable Banking, per il log: solo le ultime quattro cifre dell'IBAN, la valuta e se c'è
     * l'impronta. Basta per capire un collegamento andato storto (docs/modalita-ristretta.md), senza dati completi.
     */
    static String describe(List<EnableBankingApi.Account> accounts) {
        if (accounts.isEmpty()) {
            return "";
        }
        return accounts.stream()
                .map(a -> lastDigits(a.accountId() != null ? a.accountId().iban() : null) + " " + a.currency()
                        + (a.identificationHash() != null && !a.identificationHash().isBlank() ? " (impronta)"
                                : " (senza impronta)"))
                .collect(Collectors.joining(", ", ": ", ""));
    }

    /** "··3288": le ultime quattro cifre di un IBAN, o "senza IBAN" (una carta). */
    static String lastDigits(String iban) {
        if (iban == null || iban.isBlank()) {
            return "senza IBAN";
        }
        String clean = normalize(iban);
        return "··" + clean.substring(Math.max(0, clean.length() - 4));
    }
}
