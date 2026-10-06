package it.wallettracker.poc;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;

import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRole;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Access;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Aspsp;
import it.wallettracker.bank.enablebanking.EnableBankingApi.AspspRef;
import it.wallettracker.bank.enablebanking.EnableBankingApi.AuthorizationRequest;
import it.wallettracker.bank.enablebanking.EnableBankingApi.AuthorizationResponse;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Balance;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Session;
import it.wallettracker.bank.enablebanking.EnableBankingClient;
import it.wallettracker.bank.enablebanking.EnableBankingProperties;
import it.wallettracker.bank.enablebanking.PsuHeaders;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.connection.BankConnection;
import it.wallettracker.connection.ConnectionService;
import it.wallettracker.sync.SyncService;
import it.wallettracker.sync.SyncService.AccountSync;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.TransactionImportService;
import it.wallettracker.transaction.TransactionImportService.ImportResult;
import it.wallettracker.transaction.TransactionType;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Fase 0: prova di collegamento alla banca, da riga di comando.
 *
 * <p>Il programma:
 * <ol>
 *   <li>ti fa scegliere una banca;</li>
 *   <li>ti fa autorizzare l'accesso ai conti (login + SCA sul sito della banca);</li>
 *   <li>stampa i saldi, importa i movimenti nel database e mostra gli ultimi salvati.</li>
 * </ol>
 * Dalla Fase 1 il collegamento e i conti vengono salvati nel database: la volta successiva puoi
 * riusare il collegamento salvato senza rifare il login, finché il consenso è valido.
 * I movimenti vengono importati nel database senza doppioni (vedi TransactionImportService);
 * i conti con ruolo EXCLUDED vengono saltati.
 *
 * <p>Un {@link CommandLineRunner} viene eseguito da Spring Boot subito dopo l'avvio.
 * {@code @Profile("poc")} fa sì che questa classe esista solo se avvii l'app con il profilo "poc".
 */
@Component
@Profile("poc")
public class PocRunner implements CommandLineRunner {

    /** Durata massima di un consenso PSD2 per la lettura dei conti. */
    private static final Duration MAX_CONSENT = Duration.ofDays(180);

    private final EnableBankingClient client;
    private final EnableBankingProperties properties;
    private final ConnectionService connectionService;
    private final TransactionImportService importService;
    private final ClassificationService classificationService;
    private final SyncService syncService;
    private final Scanner keyboard = new Scanner(System.in);

    /** Il risultato del login sulla banca: il codice da scambiare e la scadenza del consenso richiesta. */
    private record AuthorizationResult(String code, Instant validUntil) {
    }

    // Spring passa al costruttore i componenti di cui abbiamo bisogno (dependency injection).
    public PocRunner(EnableBankingClient client, EnableBankingProperties properties,
            ConnectionService connectionService, TransactionImportService importService,
            ClassificationService classificationService, SyncService syncService) {
        this.client = client;
        this.properties = properties;
        this.connectionService = connectionService;
        this.importService = importService;
        this.classificationService = classificationService;
        this.syncService = syncService;
    }

    @Override
    public void run(String... args) {
        System.out.println("=== WalletTracker - prova di collegamento a Enable Banking ===");
        try {
            BankConnection connection = chooseOrCreateConnection();
            List<Account> accounts = connectionService.accountsOf(connection);
            System.out.println(connection.getAspspName() + ": " + accounts.size() + " conti, consenso valido fino al "
                    + LocalDate.ofInstant(connection.getValidUntil(), ZoneId.systemDefault()));

            // Sei davanti al programma: lo diciamo alla banca, così le letture non consumano
            // il limite giornaliero degli accessi in background (vedi PsuHeaders).
            PsuHeaders psu = new PsuHeaders(findPublicIpAddress(), "WalletTracker/0.1 (Java)");

            for (Account account : accounts) {
                printAccount(account, psu);
            }

            // Classifichiamo di nuovo tutti i movimenti (di tutti i conti: i trasferimenti hanno due lati).
            Map<TransactionType, Integer> counts = classificationService.classifyAll();
            System.out.println();
            System.out.println("=== Classificazione di tutti i movimenti salvati ===");
            counts.forEach((type, count) -> System.out.printf("  %-22s %d%n", type, count));

            for (Account account : accounts) {
                printLatest(account);
            }
        } catch (RestClientResponseException e) {
            System.out.println("Enable Banking ha risposto con un errore HTTP " + e.getStatusCode().value() + ":");
            System.out.println(e.getResponseBodyAsString());
            System.out.println("Vedi 'Problemi comuni' in docs/02-fase-0-collegamento-banca.md");
        } catch (IllegalStateException e) {
            System.out.println("Errore: " + e.getMessage());
        }
    }

    /**
     * Mostra i collegamenti salvati nel database con il consenso ancora valido: puoi riusarne uno
     * (niente login) oppure collegare una nuova banca, che verrà salvata.
     */
    private BankConnection chooseOrCreateConnection() {
        List<BankConnection> saved = connectionService.validConnections();
        if (!saved.isEmpty()) {
            System.out.println("Collegamenti salvati:");
            for (int i = 0; i < saved.size(); i++) {
                BankConnection connection = saved.get(i);
                System.out.println("  " + (i + 1) + ") " + connection.getAspspName() + " (" + connection.getAspspCountry()
                        + "), valido fino al " + LocalDate.ofInstant(connection.getValidUntil(), ZoneId.systemDefault()));
            }
            System.out.println("  0) Collega una nuova banca");

            String choice = ask("Scelta");
            try {
                int number = Integer.parseInt(choice);
                if (number >= 1 && number <= saved.size()) {
                    return saved.get(number - 1);
                }
            } catch (NumberFormatException e) {
                // Qualsiasi altra risposta: colleghiamo una nuova banca.
            }
        }

        Aspsp bank = chooseBank();
        AuthorizationResult authorization = authorize(bank);
        Session session = client.createSession(authorization.code());
        System.out.println("Collegamento riuscito! Conti autorizzati: " + session.accounts().size());

        BankConnection connection = connectionService.saveSession(session, authorization.validUntil());
        System.out.println("Collegamento e conti salvati nel database.");
        return connection;
    }

    /** Passo 1: l'utente sceglie la banca dall'elenco di Enable Banking. */
    private Aspsp chooseBank() {
        String country = ask("Paese della banca (premi Invio per IT)");
        if (country.isEmpty()) {
            country = "IT";
        }

        List<Aspsp> banks = client.listAspsps(country.toUpperCase());
        System.out.println("Enable Banking supporta " + banks.size() + " banche in " + country.toUpperCase() + ".");

        while (true) {
            String search = ask("Cerca la banca per nome (es. ING, Revolut)").toLowerCase();
            List<Aspsp> matches = banks.stream()
                    .filter(bank -> bank.name().toLowerCase().contains(search))
                    .toList();

            if (matches.isEmpty()) {
                System.out.println("Nessuna banca trovata con '" + search + "', riprova.");
                continue;
            }

            for (int i = 0; i < matches.size(); i++) {
                Aspsp bank = matches.get(i);
                System.out.println("  " + (i + 1) + ") " + bank.name() + "  " + bank.psuTypes()
                        + "  header PSU richiesti: " + bank.requiredPsuHeaders());
            }

            String choice = ask("Numero della banca");
            try {
                return matches.get(Integer.parseInt(choice) - 1);
            } catch (NumberFormatException | IndexOutOfBoundsException e) {
                System.out.println("Scelta non valida, riprova.");
            }
        }
    }

    /** Passo 2: l'utente accede alla banca e ci restituisce il codice di autorizzazione. */
    private AuthorizationResult authorize(Aspsp bank) {
        // Chiediamo il consenso per la durata massima ammessa dalla banca, senza superare i 180 giorni.
        Duration validity = MAX_CONSENT;
        if (bank.maximumConsentValidity() != null && bank.maximumConsentValidity() < MAX_CONSENT.toSeconds()) {
            validity = Duration.ofSeconds(bank.maximumConsentValidity());
        }
        // Togliamo qualche minuto di margine, per non superare il limite a causa di orologi non allineati.
        Instant validUntil = Instant.now().plus(validity).minus(Duration.ofMinutes(5)).truncatedTo(ChronoUnit.SECONDS);
        System.out.println("Il consenso durerà " + validity.toDays() + " giorni.");

        // "state" è un valore casuale che ci torna indietro invariato dopo il login:
        // se coincide, siamo sicuri che la risposta corrisponde alla nostra richiesta.
        String state = UUID.randomUUID().toString();

        AuthorizationRequest request = new AuthorizationRequest(
                new Access(validUntil),
                new AspspRef(bank.name(), bank.country()),
                state,
                properties.redirectUrl(),
                "personal");
        AuthorizationResponse response = client.startAuthorization(request);

        System.out.println();
        System.out.println("1) Apri questo link nel browser e accedi alla tua banca:");
        System.out.println("   " + response.url());
        System.out.println("2) Alla fine verrai mandato su " + properties.redirectUrl());
        System.out.println("   La pagina darà errore: è normale, per ora non c'è un server in ascolto.");
        System.out.println("3) Copia l'indirizzo completo dalla barra del browser e incollalo qui.");
        String redirectedUrl = ask("Indirizzo");

        Map<String, String> params = UriComponentsBuilder.fromUriString(redirectedUrl)
                .build()
                .getQueryParams()
                .toSingleValueMap();

        if (params.containsKey("error")) {
            throw new IllegalStateException("la banca ha restituito un errore: " + params);
        }
        if (!state.equals(params.get("state"))) {
            throw new IllegalStateException("il parametro 'state' non corrisponde: hai incollato l'indirizzo giusto?");
        }
        if (params.get("code") == null) {
            throw new IllegalStateException("nell'indirizzo incollato non c'è il parametro 'code'");
        }
        return new AuthorizationResult(params.get("code"), validUntil);
    }

    /** Passo 3: saldi del conto, importazione dei movimenti e riepilogo. */
    private void printAccount(Account account, PsuHeaders psu) {
        if (account.getRole() == AccountRole.EXCLUDED) {
            System.out.println();
            System.out.println("(conto " + account.getName() + " escluso: lo salto)");
            return;
        }

        String iban = account.getIban() != null ? account.getIban() : "-";
        System.out.println();
        System.out.println("=== Conto: " + account.getName() + " | IBAN: " + iban + " | " + account.getCurrency()
                + " | ruolo: " + account.getRole() + " ===");

        for (Balance balance : client.getBalances(account.getProviderUid(), psu)) {
            System.out.println("Saldo " + balance.balanceType() + ": "
                    + balance.balanceAmount().amount() + " " + balance.balanceAmount().currency());
        }

        // Importiamo i movimenti nel database (senza doppioni) e mostriamo un riepilogo.
        // SyncService salva anche l'esito nel conto (last_sync_at, last_sync_error), come fa la
        // sincronizzazione automatica. Se l'importazione fallisce, passiamo al conto successivo.
        AccountSync sync = syncService.syncAccount(account, psu);
        if (!sync.ok()) {
            System.out.println("Importazione non riuscita: " + sync.error());
            return;
        }
        ImportResult result = sync.result();
        System.out.println("Importazione dal " + result.from() + " (" + result.fromReason() + ")");
        System.out.println("Richieste alla banca: " + result.requests() + ", ricevuti " + result.received()
                + ", copie ripetute " + result.repeatedCopies()
                + ", nuovi " + result.inserted()
                + ", già presenti " + result.alreadyPresent()
                + ", in attesa " + result.pending());

    }

    /** Gli ultimi movimenti salvati di un conto, con il tipo assegnato dalla classificazione. */
    private void printLatest(Account account) {
        if (account.getRole() == AccountRole.EXCLUDED) {
            return;
        }
        System.out.println();
        System.out.println("Ultimi movimenti di " + account.getName() + " (" + account.getRole() + "):");
        for (BankTransaction transaction : importService.latestTransactions(account)) {
            System.out.printf("  %s  %10s %s  %-7s  %-21s  %s%n",
                    transaction.getBookingDate(),
                    transaction.getAmount(),
                    transaction.getCurrency(),
                    transaction.getStatus(),
                    transaction.getType(),
                    textOf(transaction));
        }
    }

    /**
     * Il tuo indirizzo IP pubblico, da inviare alla banca come "utente presente".
     * Lo chiediamo a un servizio esterno (ipify); se non risponde, lo chiediamo a te.
     */
    private String findPublicIpAddress() {
        try {
            String ip = RestClient.create().get()
                    .uri("https://api.ipify.org")
                    .retrieve()
                    .body(String.class);
            System.out.println("Il tuo indirizzo IP pubblico: " + ip);
            return ip.trim();
        } catch (RestClientException e) {
            return ask("Non riesco a trovare il tuo IP pubblico: scrivilo tu (lo vedi su https://api.ipify.org)");
        }
    }

    /** Controparte + causale, senza ripetere lo stesso testo due volte (succede con Revolut). */
    private static String textOf(BankTransaction transaction) {
        String counterparty = transaction.getCounterparty() != null ? transaction.getCounterparty() : "";
        String description = transaction.getDescription() != null ? transaction.getDescription() : "";
        if (description.equalsIgnoreCase(counterparty)) {
            return counterparty;
        }
        return (counterparty + " " + description).trim();
    }

    private String ask(String question) {
        System.out.print(question + ": ");
        return keyboard.nextLine().trim();
    }
}
