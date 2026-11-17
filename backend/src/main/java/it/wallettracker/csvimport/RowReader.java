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
import it.wallettracker.message.Message;
import it.wallettracker.message.MessageException;

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

    /**
     * Un movimento letto da una riga.
     *
     * @param description la causale, dalla sua colonna
     * @param detail      il testo della seconda colonna, se c'è (docs/tinaba.md, parte H)
     */
    public record ParsedRow(LocalDate date, BigDecimal amount, String currency, String description,
            String counterparty, String detail) {

        /** La causale da salvare: la causale e il testo della seconda colonna, separati da " · ". */
        public String fullDescription() {
            if (detail == null) {
                return description;
            }
            String joined = description == null ? detail : description + " · " + detail;
            return joined.length() > MAX_TEXT ? joined.substring(0, MAX_TEXT) : joined;
        }
    }

    /** Una riga che non si può leggere: il messaggio (codice e parametri) dice perché. */
    public static class RowException extends MessageException {
        public RowException(Message message) {
            super(message);
        }
    }

    public static ParsedRow read(List<String> row, ImportMapping mapping, String defaultCurrency) {
        LocalDate date = parseDate(cell(row, mapping.dateColumn()), mapping.dateFormat());
        BigDecimal amount = switch (mapping.amountMode()) {
            case SINGLE -> required(parseAmount(cell(row, mapping.amountColumn()), mapping.decimalSeparator()),
                    "import.row.missing-amount");
            case SPLIT -> splitAmount(row, mapping);
            case UNSIGNED -> unsignedAmount(row, mapping);
        };
        String currency = currency(cell(row, mapping.currencyColumn()), defaultCurrency);
        String description = text(cell(row, mapping.descriptionColumn()));
        String detail = mapping.detailColumn() == null || mapping.detailColumn().equals(mapping.descriptionColumn())
                ? null
                : text(cell(row, mapping.detailColumn()));
        return new ParsedRow(date, amount, currency, description, text(cell(row, mapping.counterpartyColumn())),
                detail);
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
            // messaggio: formato di data "{0}" non previsto
            throw new RowException(Message.of("import.row.unknown-date-format", format));
        }
        if (value == null || value.isBlank()) {
            // messaggio: manca la data
            throw new RowException(Message.of("import.row.missing-date"));
        }
        String day = value.trim().split("[ T]")[0];
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format.replace("yyyy", "uuuu").replace("yy", "uu"),
                    Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
            return LocalDate.parse(day, formatter);
        } catch (DateTimeParseException e) {
            // messaggio: la data "{0}" non è nel formato {1}
            throw new RowException(Message.of("import.row.wrong-date-format", value.trim(), humanFormat(format)));
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
            // messaggio: l'importo "{0}" non è un numero
            throw new RowException(Message.of("import.row.amount-not-a-number", value.trim()));
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            // messaggio: l'importo "{0}" ha più di due decimali: controlla il separatore
            throw new RowException(Message.of("import.row.amount-too-many-decimals", value.trim()));
        }
        amount = amount.setScale(2);
        return negative ? amount.negate() : amount;
    }

    /** Due colonne: entrate (positive) e uscite (le facciamo sempre negative, con o senza segno nel file). */
    private static BigDecimal splitAmount(List<String> row, ImportMapping mapping) {
        BigDecimal income = parseAmount(cell(row, mapping.incomeColumn()), mapping.decimalSeparator());
        BigDecimal expense = parseAmount(cell(row, mapping.expenseColumn()), mapping.decimalSeparator());
        if (income == null && expense == null) {
            // messaggio: mancano sia l'entrata sia l'uscita
            throw new RowException(Message.of("import.row.no-income-no-expense"));
        }
        BigDecimal in = income == null ? BigDecimal.ZERO : income.abs();
        BigDecimal out = expense == null ? BigDecimal.ZERO : expense.abs();
        return in.subtract(out).setScale(2);
    }

    /**
     * Un importo senza segno (docs/tinaba.md): è un'entrata se il testo della colonna indicata contiene una delle
     * parole delle entrate, altrimenti un'uscita. Le maiuscole non contano. Un eventuale segno nel file si ignora:
     * conta solo il testo, così una riga non può essere letta in due modi diversi.
     */
    private static BigDecimal unsignedAmount(List<String> row, ImportMapping mapping) {
        BigDecimal amount = required(parseAmount(cell(row, mapping.amountColumn()), mapping.decimalSeparator()),
                "import.row.missing-amount").abs();
        String text = cell(row, mapping.signColumn());
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        boolean income = mapping.incomeWordList().stream().anyMatch(lower::contains);
        return income ? amount : amount.negate();
    }

    private static String currency(String value, String defaultCurrency) {
        if (value == null || value.isBlank()) {
            return defaultCurrency;
        }
        String code = value.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z]{3}")) {
            // messaggio: la valuta "{0}" non è un codice come EUR o USD
            throw new RowException(Message.of("import.row.invalid-currency", value.trim()));
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

    /** @param code il codice del messaggio se il valore manca: {@code "import.row.missing-amount"} è "manca l'importo" */
    private static <T> T required(T value, String code) {
        if (value == null) {
            throw new RowException(Message.of(code));
        }
        return value;
    }

    /** Il valore di una colonna; null se la colonna non è indicata o se la riga è più corta. */
    private static String cell(List<String> row, Integer column) {
        return column == null || column >= row.size() ? null : row.get(column);
    }

    /**
     * Il formato come lo scrive una persona, con le lettere della sua lingua: in italiano "gg/mm/aaaa" (giorno, mese,
     * anno). È un messaggio del catalogo, uno per ogni formato di {@link #DATE_FORMATS}.
     */
    static Message humanFormat(String format) {
        return Message.of(switch (format) {
            case "dd/MM/yyyy" -> "import.date-format.day-month-year-slash"; // messaggio: gg/mm/aaaa
            case "dd-MM-yyyy" -> "import.date-format.day-month-year-dash";  // messaggio: gg-mm-aaaa
            case "dd.MM.yyyy" -> "import.date-format.day-month-year-dot";   // messaggio: gg.mm.aaaa
            case "yyyy-MM-dd" -> "import.date-format.year-month-day";       // messaggio: aaaa-mm-gg
            case "MM/dd/yyyy" -> "import.date-format.month-day-year";       // messaggio: mm/gg/aaaa
            case "dd/MM/yy" -> "import.date-format.day-month-short-year";   // messaggio: gg/mm/aa
            default -> throw new IllegalArgumentException(format);
        });
    }
}
