package it.wallettracker.message;

/**
 * Un errore con un messaggio per chi usa l'app, lanciato dal codice che non sa niente di HTTP (chi legge un file, chi
 * controlla una chiave). Chi lo riceve decide che risposta dare: di solito lo trasforma in una {@link ApiException}.
 */
public class MessageException extends RuntimeException {

    private final transient Message userMessage;

    public MessageException(Message message) {
        this(message, null);
    }

    public MessageException(Message message, Throwable cause) {
        super(message.toString(), cause);
        this.userMessage = message;
    }

    /** Il messaggio per l'utente: codice e parametri. */
    public Message message() {
        return userMessage;
    }
}
