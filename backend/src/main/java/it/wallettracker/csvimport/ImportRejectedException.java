package it.wallettracker.csvimport;

import java.util.List;

/**
 * Il file ha righe che non si possono leggere: l'importazione viene rifiutata tutta, con l'elenco degli
 * errori. Meglio niente che un'importazione a metà, con movimenti mancanti che nessuno nota.
 */
public class ImportRejectedException extends RuntimeException {

    private final transient List<String> errors;

    public ImportRejectedException(String message, List<String> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
