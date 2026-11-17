package it.wallettracker.setup;

import java.security.PrivateKey;
import java.util.List;

import it.wallettracker.bank.enablebanking.EnableBankingClient;
import it.wallettracker.bank.enablebanking.EnableBankingCredentials;
import it.wallettracker.bank.enablebanking.EnableBankingJwtFactory;
import it.wallettracker.bank.enablebanking.EnableBankingProperties;
import it.wallettracker.bank.enablebanking.InvalidPrivateKeyException;
import it.wallettracker.message.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * La configurazione guidata di Enable Banking: stato attuale e salvataggio di credenziali nuove.
 *
 * <p>Le credenziali vengono salvate solo dopo averle <b>verificate</b>: con quelle credenziali firmiamo un
 * JWT e facciamo una richiesta di prova a Enable Banking. Se Enable Banking la accetta, sono giuste.
 */
@Service
public class SetupService {

    private final EnableBankingCredentials credentials;
    private final EnableBankingClient client;
    private final EnableBankingProperties properties;

    public SetupService(EnableBankingCredentials credentials, EnableBankingClient client,
            EnableBankingProperties properties) {
        this.credentials = credentials;
        this.client = client;
        this.properties = properties;
    }

    /**
     * Lo stato della configurazione, per la dashboard. La chiave privata NON esce mai dal backend:
     * restituiamo solo se c'è, da dove arriva e l'Application ID (che non è un segreto).
     *
     * @param source      "DASHBOARD", "ENV" oppure null se non configurato
     * @param redirectUrl  il primo indirizzo di ritorno (quello di prima, quando ce n'era uno solo)
     * @param redirectUrls tutti gli indirizzi di ritorno, da registrare uguali nell'applicazione Enable Banking: uno
     *                     per ogni nome con cui si apre l'app (docs/47, parte I)
     */
    public record SetupStatus(boolean configured, String source, String applicationId, String redirectUrl,
            List<String> redirectUrls) {
    }

    public SetupStatus status() {
        return credentials.current()
                .map(c -> new SetupStatus(true, c.source().name(), c.applicationId(),
                        firstRedirectUrl(), properties.redirectUrls()))
                .orElseGet(() -> new SetupStatus(false, null, null, firstRedirectUrl(), properties.redirectUrls()));
    }

    /** Il primo indirizzo di ritorno, o null se non ce n'è nessuno (installazione da ZIP senza Tailscale). */
    private String firstRedirectUrl() {
        List<String> urls = properties.redirectUrls();
        return urls.isEmpty() ? null : urls.getFirst();
    }

    /** Verifica le credenziali con Enable Banking e, se vanno bene, le salva. */
    public SetupStatus saveEnableBanking(String applicationId, String privateKeyPem) {
        String id = applicationId.trim();

        // 1. La chiave è leggibile? (formato PKCS#8, RSA valida)
        PrivateKey key;
        try {
            key = EnableBankingJwtFactory.parsePrivateKey(privateKeyPem, "caricata");
        } catch (InvalidPrivateKeyException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, switch (e.problem()) {
                // messaggio: La chiave caricata non è in formato PKCS#8 ('-----BEGIN PRIVATE KEY-----'). Se inizia con
                //            'BEGIN RSA PRIVATE KEY' convertila con: openssl pkcs8 -topk8 -nocrypt -in vecchia.pem
                //            -out enable-banking.pem
                case NOT_PKCS8 -> "setup.key-not-pkcs8";
                // messaggio: La chiave caricata non contiene una chiave privata RSA valida
                case NOT_RSA -> "setup.key-not-rsa";
            });
        }

        // 2. Enable Banking la accetta? Proviamo una richiesta firmata con queste credenziali.
        try {
            client.checkCredentials(EnableBankingJwtFactory.createToken(id, key));
        } catch (RestClientResponseException e) {
            // messaggio: Enable Banking ha rifiutato queste credenziali (HTTP {0}): controlla che l'Application ID
            //            sia quello dell'applicazione a cui appartiene il file .pem, e che l'applicazione sia attiva.
            throw new ApiException(HttpStatus.BAD_REQUEST, "setup.credentials-rejected", e.getStatusCode().value());
        } catch (RestClientException e) {
            // messaggio: Enable Banking non è raggiungibile, riprova più tardi: {0}
            throw new ApiException(HttpStatus.BAD_GATEWAY, "setup.unreachable", String.valueOf(e.getMessage()));
        }

        // 3. Tutto bene: salviamo.
        credentials.save(id, privateKeyPem);
        return status();
    }
}
