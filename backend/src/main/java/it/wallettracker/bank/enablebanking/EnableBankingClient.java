package it.wallettracker.bank.enablebanking;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import it.wallettracker.bank.enablebanking.EnableBankingApi.Aspsp;
import it.wallettracker.bank.enablebanking.EnableBankingApi.AspspList;
import it.wallettracker.bank.enablebanking.EnableBankingApi.AuthorizationRequest;
import it.wallettracker.bank.enablebanking.EnableBankingApi.AuthorizationResponse;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Balance;
import it.wallettracker.bank.enablebanking.EnableBankingApi.BalanceList;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Session;
import it.wallettracker.bank.enablebanking.EnableBankingApi.SessionRequest;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Transaction;
import it.wallettracker.bank.enablebanking.EnableBankingApi.TransactionPage;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Client per le API REST di Enable Banking.
 *
 * <p>Il percorso per leggere i conti di una persona è:
 * <ol>
 *   <li>{@link #listAspsps}: elenco delle banche di un paese;</li>
 *   <li>{@link #startAuthorization}: ci dà un link dove la persona accede alla propria banca (login + SCA);</li>
 *   <li>la banca rimanda la persona al nostro "redirect URL", aggiungendo un {@code code};</li>
 *   <li>{@link #createSession}: trasforma il {@code code} in una sessione con l'elenco dei conti;</li>
 *   <li>{@link #getBalances} e {@link #getTransactions}: leggono i dati, senza bisogno della
 *       persona, finché il consenso è valido.</li>
 * </ol>
 *
 * <p>Se Enable Banking risponde con un errore (4xx o 5xx), RestClient lancia una
 * {@code RestClientResponseException} che contiene lo stato HTTP e il corpo della risposta.
 */
@Component
public class EnableBankingClient {

    private final RestClient restClient;

    public EnableBankingClient(RestClient.Builder builder, EnableBankingProperties properties,
            EnableBankingJwtFactory jwtFactory) {
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                // Su ogni richiesta aggiungiamo l'header "Authorization: Bearer <JWT>".
                // Creiamo un JWT nuovo ogni volta: costa pochissimo e non dobbiamo gestirne la scadenza.
                .defaultRequest(request -> request.header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + jwtFactory.createToken()))
                .build();
    }

    /** GET /aspsps: le banche disponibili in un paese (es. "IT"). */
    public List<Aspsp> listAspsps(String country) {
        AspspList response = restClient.get()
                .uri("/aspsps?country={country}", country)
                .retrieve()
                .body(AspspList.class);
        return response.aspsps();
    }

    /** POST /auth: avvia l'autorizzazione e restituisce il link per accedere alla banca. */
    public AuthorizationResponse startAuthorization(AuthorizationRequest request) {
        return restClient.post()
                .uri("/auth")
                .body(request)
                .retrieve()
                .body(AuthorizationResponse.class);
    }

    /** POST /sessions: scambia il codice ricevuto dopo il login con una sessione autorizzata. */
    public Session createSession(String code) {
        return restClient.post()
                .uri("/sessions")
                .body(new SessionRequest(code))
                .retrieve()
                .body(Session.class);
    }

    /** GET /accounts/{uid}/balances: i saldi di un conto, letti in background (senza l'utente). */
    public List<Balance> getBalances(String accountUid) {
        return getBalances(accountUid, null);
    }

    /**
     * GET /accounts/{uid}/balances: i saldi di un conto.
     *
     * @param psu gli header dell'utente presente, oppure {@code null} per una lettura in background
     */
    public List<Balance> getBalances(String accountUid, PsuHeaders psu) {
        BalanceList response = restClient.get()
                .uri("/accounts/{uid}/balances", accountUid)
                .headers(headers -> {
                    if (psu != null) {
                        psu.addTo(headers);
                    }
                })
                .retrieve()
                .body(BalanceList.class);
        return response.balances();
    }

    /** GET /accounts/{uid}/transactions: i movimenti tra due date, letti in background (senza l'utente). */
    public List<Transaction> getTransactions(String accountUid, LocalDate from, LocalDate to) {
        return getTransactions(accountUid, from, to, null);
    }

    /**
     * GET /accounts/{uid}/transactions: tutti i movimenti di un conto tra due date.
     *
     * <p>L'API restituisce i movimenti "a pagine". Se la risposta contiene una
     * {@code continuation_key}, la rimandiamo nella richiesta successiva per avere la pagina dopo,
     * e così via finché non arriva vuota.
     *
     * @param psu gli header dell'utente presente, oppure {@code null} per una lettura in background
     */
    public List<Transaction> getTransactions(String accountUid, LocalDate from, LocalDate to, PsuHeaders psu) {
        List<Transaction> allTransactions = new ArrayList<>();
        String continuationKey = null;

        do {
            String url = "/accounts/{uid}/transactions?date_from={from}&date_to={to}";
            if (continuationKey != null) {
                url += "&continuation_key={key}";
            }

            TransactionPage page = restClient.get()
                    .uri(url, accountUid, from, to, continuationKey)
                    .headers(headers -> {
                        if (psu != null) {
                            psu.addTo(headers);
                        }
                    })
                    .retrieve()
                    .body(TransactionPage.class);

            if (page.transactions() != null) {
                allTransactions.addAll(page.transactions());
            }

            // Protezione: alcune banche restituiscono di nuovo la stessa chiave, e il ciclo non finirebbe mai.
            if (page.continuationKey() != null && page.continuationKey().equals(continuationKey)) {
                break;
            }
            continuationKey = page.continuationKey();
        } while (continuationKey != null && !continuationKey.isEmpty());

        return allTransactions;
    }
}
