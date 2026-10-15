package it.wallettracker.bank.enablebanking;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Si prova a chiamare Enable Banking, ma le credenziali non sono ancora state configurate.
 * Diventa una risposta 409 Conflict ("non è possibile, nello stato attuale") con il motivo.
 */
public class EnableBankingNotConfiguredException extends ResponseStatusException {

    public EnableBankingNotConfiguredException() {
        super(HttpStatus.CONFLICT, "Enable Banking non è ancora configurato: apri la pagina Impostazioni.");
    }
}
