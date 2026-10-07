package it.wallettracker.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Non trovato": diventa una risposta HTTP 404.
 *
 * <p>Estende {@link ResponseStatusException}, l'eccezione di Spring che porta con sé uno stato HTTP.
 * Se un controller (o un servizio chiamato da un controller) la lancia, Spring risponde con quello stato
 * e, grazie a {@code spring.mvc.problemdetails.enabled}, con un corpo JSON standard (RFC 9457):
 * <pre>
 * { "status": 404, "title": "Not Found", "detail": "Conto 42 non trovato", "instance": "/api/accounts/42/role" }
 * </pre>
 */
public class NotFoundException extends ResponseStatusException {

    public NotFoundException(String what, Long id) {
        super(HttpStatus.NOT_FOUND, what + " " + id + " non trovato");
    }
}
