package it.wallettracker.sync;

import it.wallettracker.message.ApiException;
import it.wallettracker.message.Message;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Il motivo di un aggiornamento non riuscito, in un messaggio per chi usa l'app (docs/39, parte E). Finisce nell'avviso
 * del Riepilogo e nella pagina Conti; i dettagli tecnici (codice, risposta della banca, stack trace) restano nel log
 * del backend, dove servono a chi deve capire che cosa è successo.
 */
public final class SyncErrors {

    private SyncErrors() {
    }

    public static Message describe(RuntimeException e) {
        if (e instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            if (status == 429) {
                // messaggio: La banca ha ricevuto troppe richieste oggi: riproveremo al prossimo aggiornamento.
                return Message.of("sync.too-many-requests");
            }
            if (status == 401 || status == 403) {
                // messaggio: La banca non ha accettato l'accesso: forse il consenso è scaduto o è stato revocato.
                //            Ricollega la banca.
                return Message.of("sync.access-denied");
            }
            if (status >= 500) {
                // messaggio: La banca o Enable Banking non rispondono come dovrebbero: riproveremo al prossimo
                //            aggiornamento.
                return Message.of("sync.bank-unavailable");
            }
            // messaggio: La banca ha risposto con un errore (codice {0}): riproveremo al prossimo aggiornamento.
            return Message.of("sync.bank-error", status);
        }
        if (e instanceof RestClientException) {
            // messaggio: Enable Banking non raggiungibile: controlla la connessione a Internet. Riproveremo al
            //            prossimo aggiornamento.
            return Message.of("sync.unreachable");
        }
        // I nostri errori con un messaggio per l'utente (per esempio "Enable Banking non è ancora configurato").
        if (e instanceof ApiException api) {
            return api.message();
        }
        // messaggio: Errore imprevisto durante l'aggiornamento: i dettagli sono nel log del backend.
        return Message.of("sync.unexpected");
    }
}
