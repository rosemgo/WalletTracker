package it.wallettracker.csvimport;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Come leggere un file CSV: quale colonna contiene che cosa, e in che formato. Le colonne si indicano con
 * il loro numero, da 0 (la prima). Il backend propone una mappatura (ColumnGuesser), l'utente la corregge.
 *
 * <p>Le colonne della data e dell'importo non hanno {@code @NotNull}: mentre l'utente compone la mappatura,
 * l'anteprima ne accetta una incompleta e dice che cosa manca. L'importazione invece la vuole completa
 * (il controllo è in {@code CsvImportService.checkColumns}).
 *
 * @param delimiter          il separatore: ";" "," "\t" (tabulazione) oppure "|"
 * @param dateColumn         la colonna della data del movimento
 * @param dateFormat         il formato della data, es. "dd/MM/yyyy" (vedi {@link RowReader#DATE_FORMATS})
 * @param amountMode         SINGLE: una colonna con l'importo con il segno; SPLIT: due colonne, entrate e uscite;
 *                           UNSIGNED: una colonna con l'importo SENZA segno, e il verso si legge dal testo (Tinaba)
 * @param amountColumn       con SINGLE e UNSIGNED: la colonna dell'importo
 * @param incomeColumn       con SPLIT: la colonna delle entrate
 * @param expenseColumn      con SPLIT: la colonna delle uscite
 * @param decimalSeparator   COMMA (12,50, all'italiana) o DOT (12.50)
 * @param descriptionColumn  la causale, facoltativa
 * @param counterpartyColumn la controparte (chi riceve o chi paga), facoltativa
 * @param currencyColumn     la valuta, facoltativa: se manca, si usa quella del conto
 * @param signColumn         con UNSIGNED: la colonna in cui cercare le parole delle entrate (di solito la descrizione)
 * @param incomeWords        con UNSIGNED: le parole delle entrate, separate da virgole ("ricevuto, ricevuta"): una
 *                           riga che ne contiene una è un'entrata, tutte le altre sono uscite
 */
public record ImportMapping(
        @NotNull @Pattern(regexp = "[;,|\\t]") String delimiter,
        @Min(0) Integer dateColumn,
        @NotNull String dateFormat,
        @NotNull AmountMode amountMode,
        @Min(0) Integer amountColumn,
        @Min(0) Integer incomeColumn,
        @Min(0) Integer expenseColumn,
        @NotNull DecimalSeparator decimalSeparator,
        @Min(0) Integer descriptionColumn,
        @Min(0) Integer counterpartyColumn,
        @Min(0) Integer currencyColumn,
        @Min(0) Integer signColumn,
        @Size(max = 300) String incomeWords) {

    /**
     * Una colonna con l'importo, oppure due colonne (entrate e uscite, come negli estratti conto), oppure una colonna
     * senza segno (docs/tinaba.md): il verso si capisce dalle parole del testo.
     */
    public enum AmountMode {
        SINGLE,
        SPLIT,
        UNSIGNED
    }

    /** Le parole delle entrate, pulite: minuscole, senza spazi ai lati, senza parole vuote. */
    public List<String> incomeWordList() {
        if (incomeWords == null) {
            return List.of();
        }
        return Arrays.stream(incomeWords.split(","))
                .map(word -> word.trim().toLowerCase(Locale.ROOT))
                .filter(word -> !word.isEmpty())
                .toList();
    }

    /** Il separatore dei decimali. */
    public enum DecimalSeparator {
        COMMA,
        DOT
    }

    char delimiterChar() {
        return delimiter.charAt(0);
    }
}
