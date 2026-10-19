package it.wallettracker.csvimport;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

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
 * @param amountMode         SINGLE: una colonna con l'importo con il segno; SPLIT: due colonne, entrate e uscite
 * @param amountColumn       con SINGLE: la colonna dell'importo
 * @param incomeColumn       con SPLIT: la colonna delle entrate
 * @param expenseColumn      con SPLIT: la colonna delle uscite
 * @param decimalSeparator   COMMA (12,50, all'italiana) o DOT (12.50)
 * @param descriptionColumn  la causale, facoltativa
 * @param counterpartyColumn la controparte (chi riceve o chi paga), facoltativa
 * @param currencyColumn     la valuta, facoltativa: se manca, si usa quella del conto
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
        @Min(0) Integer currencyColumn) {

    /** Una colonna con l'importo, oppure due colonne (entrate e uscite, come negli estratti conto). */
    public enum AmountMode {
        SINGLE,
        SPLIT
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
