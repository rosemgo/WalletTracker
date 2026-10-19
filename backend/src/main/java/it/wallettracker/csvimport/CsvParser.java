package it.wallettracker.csvimport;

import java.util.ArrayList;
import java.util.List;

/**
 * Legge un file CSV: da testo a righe e colonne.
 *
 * <p>Sembra facile ("dividi ogni riga dove c'è la virgola"), ma un CSV vero ha delle insidie, gestite qui
 * secondo lo standard RFC 4180:
 * <ul>
 *   <li>il <b>separatore</b> cambia: in Italia spesso è il punto e virgola (la virgola serve per i decimali);</li>
 *   <li>un valore tra <b>virgolette</b> può contenere il separatore: {@code "Rossi; Mario"} è un valore solo;</li>
 *   <li>dentro le virgolette, due virgolette di fila sono una virgoletta: {@code "Il ""Bar"""} → {@code Il "Bar"};</li>
 *   <li>un valore tra virgolette può andare <b>a capo</b>;</li>
 *   <li>le righe finiscono con {@code \n} (Linux, Mac) o {@code \r\n} (Windows).</li>
 * </ul>
 * È una classe "pura" (niente database, niente Spring), provata da CsvParserTest.
 */
public final class CsvParser {

    /** I separatori che proviamo a riconoscere, in ordine di preferenza a parità di conteggio. */
    static final char[] DELIMITERS = {';', ',', '\t', '|'};

    private CsvParser() {
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

    /**
     * Le righe del file, ognuna come lista di valori. Le righe completamente vuote vengono saltate.
     *
     * @throws IllegalArgumentException se una virgoletta aperta non viene mai chiusa
     */
    public static List<List<String>> parse(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean inQuotes = false;
        int i = text.startsWith("﻿") ? 1 : 0; // il "BOM", un carattere invisibile che Excel mette all'inizio

        while (i < text.length()) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        value.append('"'); // "" dentro le virgolette = una virgoletta
                        i++;
                    } else {
                        inQuotes = false;  // fine del valore tra virgolette
                    }
                } else {
                    value.append(c);       // anche il separatore e l'a capo: sono parte del valore
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == delimiter) {
                row.add(value.toString());
                value.setLength(0);
            } else if (c == '\n' || c == '\r') {
                row.add(value.toString());
                value.setLength(0);
                addIfNotEmpty(rows, row);
                row = new ArrayList<>();
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++; // \r\n (Windows) è un solo a capo
                }
            } else {
                value.append(c);
            }
            i++;
        }
        if (inQuotes) {
            throw new IllegalArgumentException("Il file non è un CSV valido: una virgoletta aperta non viene mai chiusa.");
        }
        row.add(value.toString());
        addIfNotEmpty(rows, row);
        return rows;
    }

    private static void addIfNotEmpty(List<List<String>> rows, List<String> row) {
        boolean empty = row.stream().allMatch(v -> v.isBlank());
        if (!empty) {
            rows.add(row);
        }
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
