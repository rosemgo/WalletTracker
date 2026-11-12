package it.wallettracker.sync;

import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Il motivo di un aggiornamento non riuscito, in una frase per chi usa l'app (docs/39, parte E). Finisce nell'avviso
 * del Riepilogo e nella pagina Conti; i dettagli tecnici (codice, risposta della banca, stack trace) restano nel log
 * del backend, dove servono a chi deve capire che cosa è successo.
 */
public final class SyncErrors {

    private SyncErrors() {
    }

    public static String describe(RuntimeException e) {
        if (e instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            if (status == 429) {
                return "La banca ha ricevuto troppe richieste oggi: riproveremo al prossimo aggiornamento.";
            }
            if (status == 401 || status == 403) {
                return "La banca non ha accettato l'accesso: forse il consenso è scaduto o è stato revocato. "
                        + "Ricollega la banca.";
            }
            if (status >= 500) {
                return "La banca o Enable Banking non rispondono come dovrebbero: riproveremo al prossimo aggiornamento.";
            }
            return "La banca ha risposto con un errore (codice " + status + "): riproveremo al prossimo aggiornamento.";
        }
        if (e instanceof RestClientException) {
            return "Enable Banking non raggiungibile: controlla la connessione a Internet. "
                    + "Riproveremo al prossimo aggiornamento.";
        }
        // I nostri errori con un messaggio per l'utente (per esempio "Enable Banking non è ancora configurato").
        if (e instanceof ResponseStatusException status && status.getReason() != null) {
            return status.getReason();
        }
        return "Errore imprevisto durante l'aggiornamento: i dettagli sono nel log del backend.";
    }
}
