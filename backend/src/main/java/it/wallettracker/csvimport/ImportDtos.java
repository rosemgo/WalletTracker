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

    /**
     * L'anteprima del completamento di un conto collegato con un file (docs/31). Non salva niente.
     *
     * @param from                il primo giorno del file
     * @param to                  l'ultimo giorno del file
     * @param fileRows            le righe del file che muovono denaro
     * @param skippedRows         le righe saltate: operazioni sui titoli, senza denaro sul conto
     * @param toComplete          i movimenti che verranno completati, con i testi presi dal file
     * @param alreadyComplete     i movimenti trovati che hanno già controparte e causale (es. file già caricato)
     * @param rowsWithoutMovement le righe del file senza un movimento: più recenti dell'ultimo aggiornamento, o
     *                            più vecchie dello storico che la banca ha dato
     * @param movementsWithoutRow i movimenti del periodo del file che nel file non ci sono
     */
    public record CompletionPreview(LocalDate from, LocalDate to, int fileRows, int skippedRows,
            List<Completion> toComplete, int alreadyComplete, List<FileRow> rowsWithoutMovement,
            List<Movement> movementsWithoutRow) {
    }

    /** Un movimento da completare, e i testi che riceve. */
    public record Completion(long transactionId, LocalDate date, BigDecimal amount, String currency,
            String counterparty, String description) {
    }

    /** Una riga del file. */
    public record FileRow(int line, LocalDate date, BigDecimal amount, String currency, String description) {
    }

    /** Un movimento del conto. */
    public record Movement(long id, LocalDate date, BigDecimal amount, String currency) {
    }

    /** Il risultato del completamento. */
    public record CompletionResult(int completed, int alreadyComplete) {
    }
}
