package it.wallettracker.bank.enablebanking;

import it.wallettracker.message.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Si prova a chiamare Enable Banking, ma le credenziali non sono ancora state configurate.
 * Diventa una risposta 409 Conflict ("non è possibile, nello stato attuale") con il motivo.
 */
public class EnableBankingNotConfiguredException extends ApiException {

    public EnableBankingNotConfiguredException() {
        // messaggio: Enable Banking non è ancora configurato: apri la pagina Impostazioni.
        super(HttpStatus.CONFLICT, "enable-banking.not-configured");
    }
}
