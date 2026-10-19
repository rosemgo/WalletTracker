package it.wallettracker.csvimport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** I record delle API di importazione CSV. */
public final class ImportDtos {

    private ImportDtos() {
    }

    /**
     * L'anteprima di un file, prima di importarlo.
     *
     * @param headers    i nomi delle colonne (la prima riga del file)
     * @param sampleRows le prime righe così come sono nel file
     * @param totalRows  quante righe di movimenti ha il file (intestazione esclusa)
     * @param mapping    la mappatura usata: quella proposta, o quella mandata dall'utente
     * @param parsedRows le prime righe lette con la mappatura: così l'utente vede se è giusta
     * @param errorCount quante righe non si possono leggere con questa mappatura
     * @param errors     i primi errori, con il numero di riga
     */
    public record Preview(List<String> headers, List<List<String>> sampleRows, int totalRows, ImportMapping mapping,
            List<PreviewRow> parsedRows, int errorCount, List<String> errors) {
    }

    /** Una riga letta, da mostrare nell'anteprima. */
    public record PreviewRow(LocalDate date, BigDecimal amount, String currency, String description,
            String counterparty) {
    }

    /**
     * Il risultato di un'importazione.
     *
     * @param imported   i movimenti nuovi salvati
     * @param duplicates le righe già importate in passato (stesso file o file che si sovrappongono), saltate
     */
    public record ImportResult(int imported, int duplicates) {
    }
}
