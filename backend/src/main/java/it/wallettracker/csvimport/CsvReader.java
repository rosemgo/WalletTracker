package it.wallettracker.csvimport;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Legge un file CSV, da testo a righe e colonne, con la libreria <b>Apache Commons CSV</b>: è quella che usa
 * l'importazione.
 *
 * <p>Le insidie del formato (virgolette, separatore dentro un valore, a capo dentro un valore, {@code \r\n})
 * le gestisce la libreria, provata da anni su migliaia di progetti. Noi aggiungiamo solo quello che lei non
 * fa: riconoscere il separatore ({@link #detectDelimiter}), togliere il BOM e saltare le righe vuote.
 *
 * <p>Come funzionano quelle insidie lo mostra {@link CsvParser}, una versione scritta a mano a scopo
 * didattico (non usata dall'importazione).
 */
public final class CsvReader {

    /** I separatori che proviamo a riconoscere, in ordine di preferenza a parità di conteggio. */
    static final char[] DELIMITERS = {';', ',', '\t', '|'};

    private CsvReader() {
    }

    /**
     * Le righe del file, ognuna come lista di valori. Le righe vuote (anche fatte solo di separatori, come
     * {@code ;;;}) vengono saltate.
     *
     * <p>{@link CSVFormat#RFC4180} è il formato standard. Con il builder cambiamo:
 * <ul>
 *   <li>il separatore;</li>
 *   <li>{@code ignoreSurroundingSpaces}: gli spazi attorno a un valore (fuori dalle virgolette) si tolgono.
 *       Serve ai file scritti con "virgola e spazio" ({@code 03/10/2026, "Rossi, Mario", -1.20}): senza,
 *       lo spazio prima della virgoletta fa sì che {@code "Rossi, Mario"} non sia riconosciuto come un
 *       valore solo, e la virgola dentro lo spezza in due colonne. Gli spazi dentro le virgolette restano;</li>
 *   <li>{@code ignoreEmptyLines}: le righe vuote si saltano.</li>
 * </ul>
 * Il resto è RFC 4180 rigoroso: una virgoletta in mezzo a un valore senza virgolette ({@code PAGAMENTO
 * "AMAZON"}) è un carattere come gli altri, mentre del testo dopo la virgoletta di chiusura
 * ({@code "ab"c}) è un errore: il file è malformato, e non proviamo a indovinare che cosa volesse dire.
     *
     * @throws IllegalArgumentException se il file non è un CSV valido (es. una virgoletta mai chiusa)
     */
    public static List<List<String>> read(String text, char delimiter) {
        CSVFormat format = CSVFormat.RFC4180.builder()
                .setDelimiter(delimiter)
                .setIgnoreSurroundingSpaces(true)
                .setIgnoreEmptyLines(true)
                .get();
        String withoutBom = text.startsWith("﻿") ? text.substring(1) : text; // il BOM che Excel mette all'inizio
        List<List<String>> rows = new ArrayList<>();
        // try-with-resources: il parser viene chiuso anche in caso di errore.
        try (CSVParser parser = CSVParser.parse(new StringReader(withoutBom), format)) {
            for (CSVRecord record : parser) {
                List<String> row = record.toList();
                if (!row.stream().allMatch(String::isBlank)) {
                    rows.add(row);
                }
            }
        } catch (IOException | UncheckedIOException e) {
            // Un file malformato: la libreria lancia una CSVException (una IOException), che mentre scorriamo le
            // righe arriva avvolta in una UncheckedIOException. Il messaggio è in inglese: lo traduciamo.
            throw new IllegalArgumentException("Il file non è un CSV valido: controlla le virgolette (una "
                    + "virgoletta aperta va chiusa) e il separatore.", e);
        }
        return rows;
    }

    /**
     * Il separatore più probabile: quello che compare più volte nella prima riga (fuori dalle virgolette).
     * La prima riga è di solito l'intestazione, con un separatore tra ogni nome di colonna.
     */
    public static char detectDelimiter(String text) {
        String firstLine = firstNonEmptyLine(text);
        char best = ',';
        int bestCount = 0;
        for (char delimiter : DELIMITERS) {
            int count = countOutsideQuotes(firstLine, delimiter);
            if (count > bestCount) {
                best = delimiter;
                bestCount = count;
            }
        }
        return best;
    }

    private static int countOutsideQuotes(String line, char delimiter) {
        int count = 0;
        boolean inQuotes = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == delimiter && !inQuotes) {
                count++;
            }
        }
        return count;
    }

    private static String firstNonEmptyLine(String text) {
        for (String line : text.split("\\r?\\n")) {
            if (!line.isBlank()) {
                return line;
            }
        }
        return "";
    }
}
