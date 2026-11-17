package it.wallettracker.csvimport;

import java.util.List;

import it.wallettracker.message.Message;
import it.wallettracker.message.MessageException;

/**
 * Il file ha righe che non si possono leggere: l'importazione viene rifiutata tutta, con l'elenco degli
 * errori. Meglio niente che un'importazione a metà, con movimenti mancanti che nessuno nota.
 */
public class ImportRejectedException extends MessageException {

    private final transient List<Message> errors;

    public ImportRejectedException(Message message, List<Message> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }

    public List<Message> getErrors() {
        return errors;
    }
}
