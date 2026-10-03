package it.wallettracker.poc;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;

import it.wallettracker.bank.enablebanking.EnableBankingApi.Access;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Account;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Aspsp;
import it.wallettracker.bank.enablebanking.EnableBankingApi.AspspRef;
import it.wallettracker.bank.enablebanking.EnableBankingApi.AuthorizationRequest;
import it.wallettracker.bank.enablebanking.EnableBankingApi.AuthorizationResponse;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Balance;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Party;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Session;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Transaction;
import it.wallettracker.bank.enablebanking.EnableBankingClient;
import it.wallettracker.bank.enablebanking.EnableBankingProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Fase 0: prova di collegamento alla banca, da riga di comando.
 *
 * <p>Il programma:
 * <ol>
 *   <li>ti fa scegliere una banca;</li>
 *   <li>ti fa autorizzare l'accesso ai conti (login + SCA sul sito della banca);</li>
 *   <li>stampa saldi e movimenti degli ultimi 30 giorni.</li>
 * </ol>
 * Non salva nulla: serve solo a verificare che il collegamento funzioni con le tue banche.
 *
 * <p>Un {@link CommandLineRunner} viene eseguito da Spring Boot subito dopo l'avvio.
 * {@code @Profile("poc")} fa sì che questa classe esista solo se avvii l'app con il profilo "poc".
 */
@Component
@Profile("poc")
public class PocRunner implements CommandLineRunner {

    private static final int DAYS_OF_HISTORY = 30;

    /** Durata massima di un consenso PSD2 per la lettura dei conti. */
    private static final Duration MAX_CONSENT = Duration.ofDays(180);

    private final EnableBankingClient client;
    private final EnableBankingProperties properties;
    private final Scanner keyboard = new Scanner(System.in);

    // Spring passa al costruttore i componenti di cui abbiamo bisogno (dependency injection).
    public PocRunner(EnableBankingClient client, EnableBankingProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void run(String... args) {
        System.out.println("=== WalletTracker - Fase 0: prova di collegamento a Enable Banking ===");
        try {
            Aspsp bank = chooseBank();
            String code = authorize(bank);

            Session session = client.createSession(code);
            System.out.println("Collegamento riuscito! Conti autorizzati: " + session.accounts().size());

            for (Account account : session.accounts()) {
                printAccount(account);
            }
        } catch (RestClientResponseException e) {
            System.out.println("Enable Banking ha risposto con un errore HTTP " + e.getStatusCode().value() + ":");
            System.out.println(e.getResponseBodyAsString());
            System.out.println("Vedi 'Problemi comuni' in docs/02-fase-0-collegamento-banca.md");
        } catch (IllegalStateException e) {
            System.out.println("Errore: " + e.getMessage());
        }
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
                System.out.println("  " + (i + 1) + ") " + bank.name() + "  " + bank.psuTypes());
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
    private String authorize(Aspsp bank) {
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
        return params.get("code");
    }

    /** Passo 3: stampiamo saldi e movimenti di un conto. */
    private void printAccount(Account account) {
        String iban = account.accountId() != null ? account.accountId().iban() : "-";
        System.out.println();
        System.out.println("=== Conto: " + account.name() + " | IBAN: " + iban + " | " + account.currency() + " ===");

        for (Balance balance : client.getBalances(account.uid())) {
            System.out.println("Saldo " + balance.balanceType() + ": "
                    + balance.balanceAmount().amount() + " " + balance.balanceAmount().currency());
        }

        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(DAYS_OF_HISTORY);
        List<Transaction> transactions = client.getTransactions(account.uid(), from, to);
        System.out.println(transactions.size() + " movimenti dal " + from + " al " + to + ":");

        for (Transaction transaction : transactions) {
            System.out.printf("  %s  %10s %s  %s  %s%n",
                    dateOf(transaction),
                    transaction.signedAmount(),
                    transaction.transactionAmount().currency(),
                    transaction.status(),
                    descriptionOf(transaction));
        }
    }

    /** Non tutte le banche compilano tutte le date: usiamo quella contabile e, se manca, quella di valuta. */
    private static LocalDate dateOf(Transaction transaction) {
        return transaction.bookingDate() != null ? transaction.bookingDate() : transaction.valueDate();
    }

    /** Descrizione leggibile: controparte (chi riceve se è un'uscita, chi invia se è un'entrata) + causale. */
    private static String descriptionOf(Transaction transaction) {
        Party counterparty = transaction.signedAmount().signum() < 0 ? transaction.creditor() : transaction.debtor();
        String name = counterparty != null && counterparty.name() != null ? counterparty.name() : "";
        String reference = transaction.remittanceInformation() != null
                ? String.join(" ", transaction.remittanceInformation())
                : "";
        return (name + " " + reference).trim();
    }

    private String ask(String question) {
        System.out.print(question + ": ");
        return keyboard.nextLine().trim();
    }
}
