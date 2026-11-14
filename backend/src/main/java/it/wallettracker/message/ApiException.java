package it.wallettracker.message;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/**
 * Un errore da restituire alla dashboard: lo stato HTTP e il messaggio per l'utente, come codice e parametri.
 *
 * <p>Prende il posto di {@code new ResponseStatusException(stato, "frase in italiano")}. Estende {@link
 * ResponseStatusException}, così resta un errore HTTP per Spring e per chi lo intercetta; la frase la scrive {@code
 * ApiExceptionHandler} al momento della risposta, nella lingua della richiesta, e nel corpo aggiunge il codice
 * ({@code "code": "category.duplicate"}).
 */
public class ApiException extends ResponseStatusException {

    private final transient Message userMessage;

    /** @param code il codice della frase in {@code messages.properties}; {@code args} i suoi parametri */
    public ApiException(HttpStatusCode status, String code, Object... args) {
        this(status, Message.of(code, args));
    }

    public ApiException(HttpStatusCode status, Message message) {
        this(status, message, null);
    }

    public ApiException(HttpStatusCode status, Message message, Throwable cause) {
        super(status, message.toString(), cause);
        this.userMessage = message;
    }

    /** Il messaggio per l'utente: codice e parametri. */
    public Message message() {
        return userMessage;
    }
}
