package it.wallettracker.setup;

import java.security.PrivateKey;

import it.wallettracker.bank.enablebanking.EnableBankingClient;
import it.wallettracker.bank.enablebanking.EnableBankingCredentials;
import it.wallettracker.bank.enablebanking.EnableBankingJwtFactory;
import it.wallettracker.bank.enablebanking.EnableBankingProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

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
     * @param redirectUrl l'indirizzo di ritorno da registrare nell'applicazione Enable Banking
     */
    public record SetupStatus(boolean configured, String source, String applicationId, String redirectUrl) {
    }

    public SetupStatus status() {
        return credentials.current()
                .map(c -> new SetupStatus(true, c.source().name(), c.applicationId(), properties.redirectUrl()))
                .orElseGet(() -> new SetupStatus(false, null, null, properties.redirectUrl()));
    }

    /** Verifica le credenziali con Enable Banking e, se vanno bene, le salva. */
    public SetupStatus saveEnableBanking(String applicationId, String privateKeyPem) {
        String id = applicationId.trim();

        // 1. La chiave è leggibile? (formato PKCS#8, RSA valida)
        PrivateKey key;
        try {
            key = EnableBankingJwtFactory.parsePrivateKey(privateKeyPem, "caricata");
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        // 2. Enable Banking la accetta? Proviamo una richiesta firmata con queste credenziali.
        try {
            client.checkCredentials(EnableBankingJwtFactory.createToken(id, key));
        } catch (RestClientResponseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Enable Banking ha rifiutato queste credenziali (HTTP " + e.getStatusCode().value() + "): "
                            + "controlla che l'Application ID sia quello dell'applicazione a cui appartiene il file .pem, "
                            + "e che l'applicazione sia attiva.");
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Enable Banking non è raggiungibile, riprova più tardi: " + e.getMessage());
        }

        // 3. Tutto bene: salviamo.
        credentials.save(id, privateKeyPem);
        return status();
    }
}
