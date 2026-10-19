package it.wallettracker.csvimport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;

import it.wallettracker.csvimport.ImportMapping.AmountMode;
import it.wallettracker.csvimport.ImportMapping.DecimalSeparator;

/**
 * Trasforma una riga del CSV in un movimento, secondo la mappatura scelta. Classe "pura", provata da
 * RowReaderTest.
 *
 * <p>Se qualcosa non va (una data impossibile, un importo che non è un numero) lancia una
 * {@link RowException} con un messaggio comprensibile: l'importazione mostra all'utente le righe sbagliate
 * invece di importare dati a metà.
 */
public final class RowReader {

    /** I formati di data che sappiamo leggere: quelli delle banche europee e americane. */
    public static final List<String> DATE_FORMATS = List.of(
            "dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "yyyy-MM-dd", "MM/dd/yyyy", "dd/MM/yy");

    /** I testi più lunghi vengono tagliati: una causale di 500 caratteri basta e avanza. */
    static final int MAX_TEXT = 500;

    private RowReader() {
    }

    /** Un movimento letto da una riga. */
    public record ParsedRow(LocalDate date, BigDecimal amount, String currency, String description,
            String counterparty) {
    }

    /** Una riga che non si può leggere: il messaggio dice perché. */
    public static class RowException extends RuntimeException {
        public RowException(String message) {
            super(message);
        }
    }

    public static ParsedRow read(List<String> row, ImportMapping mapping, String defaultCurrency) {
        LocalDate date = parseDate(cell(row, mapping.dateColumn()), mapping.dateFormat());
        BigDecimal amount = mapping.amountMode() == AmountMode.SINGLE
                ? required(parseAmount(cell(row, mapping.amountColumn()), mapping.decimalSeparator()), "l'importo")
                : splitAmount(row, mapping);
        String currency = currency(cell(row, mapping.currencyColumn()), defaultCurrency);
        return new ParsedRow(date, amount, currency, text(cell(row, mapping.descriptionColumn())),
                text(cell(row, mapping.counterpartyColumn())));
    }

    /**
     * La data, nel formato indicato. Un'eventuale ora dopo la data ("2026-10-03 10:12" o
     * "2026-10-03T10:12:00Z") viene ignorata: ci serve solo il giorno.
     *
     * <p>{@code ResolverStyle.STRICT}: il 31/02 è un errore, non il 3 marzo. Con STRICT il formato vuole
     * "uuuu" (l'anno) invece di "yyyy" (l'anno "dell'era"): lo sostituiamo noi.
     */
    static LocalDate parseDate(String value, String format) {
        if (!DATE_FORMATS.contains(format)) {
            throw new RowException("formato di data \"" + format + "\" non previsto");
        }
        if (value == null || value.isBlank()) {
            throw new RowException("manca la data");
        }
        String day = value.trim().split("[ T]")[0];
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format.replace("yyyy", "uuuu").replace("yy", "uu"),
                    Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
            return LocalDate.parse(day, formatter);
        } catch (DateTimeParseException e) {
            throw new RowException("la data \"" + value.trim() + "\" non è nel formato " + humanFormat(format));
        }
    }

    /**
     * Un importo scritto come lo scrivono le banche: "-1.234,56", "1,234.56", "12,50 €", "(12.50)",
     * "12,50-". Restituisce null se la cella è vuota.
     */
    static BigDecimal parseAmount(String value, DecimalSeparator separator) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        boolean negative = false;
        if (text.startsWith("(") && text.endsWith(")")) {     // (12,50) = -12,50, all'americana
            negative = true;
            text = text.substring(1, text.length() - 1);
        }
        text = text.replaceAll("[^0-9,.+\\-]", "");          // via simboli di valuta, spazi, lettere
        if (text.endsWith("-")) {                             // 12,50- (alcune banche tedesche)
            negative = true;
            text = text.substring(0, text.length() - 1);
        }
        if (separator == DecimalSeparator.COMMA) {
            text = text.replace(".", "").replace(',', '.');   // 1.234,56 → 1234.56
        } else {
            text = text.replace(",", "");                     // 1,234.56 → 1234.56
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new RowException("l'importo \"" + value.trim() + "\" non è un numero");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new RowException("l'importo \"" + value.trim() + "\" ha più di due decimali: controlla il separatore");
        }
        amount = amount.setScale(2);
        return negative ? amount.negate() : amount;
    }

    /** Due colonne: entrate (positive) e uscite (le facciamo sempre negative, con o senza segno nel file). */
    private static BigDecimal splitAmount(List<String> row, ImportMapping mapping) {
        BigDecimal income = parseAmount(cell(row, mapping.incomeColumn()), mapping.decimalSeparator());
        BigDecimal expense = parseAmount(cell(row, mapping.expenseColumn()), mapping.decimalSeparator());
        if (income == null && expense == null) {
            throw new RowException("mancano sia l'entrata sia l'uscita");
        }
        BigDecimal in = income == null ? BigDecimal.ZERO : income.abs();
        BigDecimal out = expense == null ? BigDecimal.ZERO : expense.abs();
        return in.subtract(out).setScale(2);
    }

    private static String currency(String value, String defaultCurrency) {
        if (value == null || value.isBlank()) {
            return defaultCurrency;
        }
        String code = value.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z]{3}")) {
            throw new RowException("la valuta \"" + value.trim() + "\" non è un codice come EUR o USD");
        }
        return code;
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim().replaceAll("\\s+", " ");
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
    }

    private static <T> T required(T value, String what) {
        if (value == null) {
            throw new RowException("manca " + what);
        }
        return value;
    }

    /** Il valore di una colonna; null se la colonna non è indicata o se la riga è più corta. */
    private static String cell(List<String> row, Integer column) {
        return column == null || column >= row.size() ? null : row.get(column);
    }

    /** "dd/MM/yyyy" → "gg/mm/aaaa", come lo scrive un italiano. */
    static String humanFormat(String format) {
        return format.replace("dd", "gg").replace("MM", "mm").replace("yyyy", "aaaa").replace("yy", "aa");
    }
}
