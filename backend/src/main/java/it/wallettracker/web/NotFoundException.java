package it.wallettracker.web;

import it.wallettracker.message.ApiException;
import org.springframework.http.HttpStatus;

/**
 * "Non trovato": diventa una risposta HTTP 404.
 *
 * <p>È una {@link ApiException} (che a sua volta estende {@code ResponseStatusException}, l'eccezione di Spring che
 * porta con sé uno stato HTTP). Se un controller (o un servizio chiamato da un controller) la lancia, Spring risponde
 * con quello stato e con un corpo JSON standard (RFC 9457):
 * <pre>
 * { "status": 404, "title": "Not Found", "detail": "Conto 42 non trovato", "code": "not-found.account",
 *   "instance": "/api/accounts/42/role" }
 * </pre>
 */
public class NotFoundException extends ApiException {

    /**
     * @param code il codice della frase, uno per ogni cosa che si può cercare: {@code "not-found.account"} è
     *             "Conto {0} non trovato"
     */
    public NotFoundException(String code, Long id) {
        super(HttpStatus.NOT_FOUND, code, id);
    }
}
