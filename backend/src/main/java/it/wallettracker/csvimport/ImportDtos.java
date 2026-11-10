package it.wallettracker.csvimport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** I record delle API di importazione CSV. */
public final class ImportDtos {

    private ImportDtos() {
    }

    /**
     * L'anteprima di un file, prima di importarlo (docs/importa-file.md).
     *
     * @param headers       i nomi delle colonne (la riga dell'intestazione, anche se non è la prima del file)
     * @param sampleRows    le prime righe così come sono nel file
     * @param totalRows     quante righe di movimenti ha il file (intestazione esclusa)
     * @param mapping       la mappatura usata: quella proposta, o quella mandata dall'utente
     * @param parsedRows    le prime righe che verranno aggiunte, lette con la mappatura (se non se ne aggiunge
     *                      nessuna, le prime del file): così l'utente vede se è giusta
     * @param errorCount    quante righe non si possono leggere con questa mappatura
     * @param errors        i primi errori, con il numero di riga
     * @param spreadsheet   true per un file Excel: il separatore non c'entra, e date e numeri hanno già un formato
     * @param linkedAccount true se il conto è collegato a una banca: si aggiungono solo le righe più vecchie
     * @param bankFrom      con un conto collegato, il primo giorno dei movimenti arrivati dalla banca (null se nessuno)
     * @param newRows       le righe che verranno aggiunte
     * @param duplicateRows le righe già aggiunte da un'importazione precedente, che si saltano
     * @param coveredRows   con un conto collegato, le righe del periodo che la banca porta da sé, che si saltano
     */
    public record Preview(List<String> headers, List<List<String>> sampleRows, int totalRows, ImportMapping mapping,
            List<PreviewRow> parsedRows, int errorCount, List<String> errors, boolean spreadsheet,
            boolean linkedAccount, LocalDate bankFrom, int newRows, int duplicateRows, int coveredRows) {
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
     * @param completed  le righe già presenti a cui è stata completata la descrizione
     * @param covered    con un conto collegato, le righe del periodo che la banca porta da sé, saltate
     */
    public record ImportResult(int imported, int duplicates, int completed, int covered) {
    }

    /**
     * L'anteprima del completamento di un conto collegato con un file (docs/31). Non salva niente.
     *
     * @param from                il primo giorno del file
     * @param to                  l'ultimo giorno del file
     * @param bankFrom            il primo giorno dei movimenti arrivati dalla banca, o null se non ce ne sono
     * @param fileRows            le righe del file che muovono denaro
     * @param skippedRows         le righe saltate: operazioni sui titoli, senza denaro sul conto
     * @param toComplete          i movimenti che verranno completati, con i testi presi dal file
     * @param toAdd               le righe più vecchie dello storico della banca, che diventeranno movimenti
     * @param alreadyComplete     le righe già fatte (movimenti con i testi, o aggiunti da un file caricato prima)
     * @param rowsWithoutMovement le righe del file senza un movimento che non si aggiungono: più recenti
     *                            dell'ultimo aggiornamento, o nel periodo della banca ma senza abbinamento
     * @param movementsWithoutRow i movimenti del periodo del file che nel file non ci sono
     */
    public record CompletionPreview(LocalDate from, LocalDate to, LocalDate bankFrom, int fileRows, int skippedRows,
            List<Completion> toComplete, List<NewMovement> toAdd, int alreadyComplete,
            List<FileRow> rowsWithoutMovement, List<Movement> movementsWithoutRow) {
    }

    /** Una riga del file che diventerà un movimento. */
    public record NewMovement(LocalDate date, BigDecimal amount, String currency, String counterparty,
            String description) {
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

    /** Il risultato del completamento: movimenti completati, movimenti aggiunti, righe già fatte. */
    public record CompletionResult(int completed, int added, int alreadyComplete) {
    }
}
