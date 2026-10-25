package it.wallettracker.csvimport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Il file "Esportazione transazioni" di Trade Republic (docs/31). A differenza degli estratti conto delle banche
 * non si legge con una mappatura scelta dall'utente: ha colonne fisse, con un nome, e due particolarità.
 *
 * <ul>
 *   <li><b>L'importo è lordo.</b> Per interessi e dividendi la colonna {@code amount} è prima delle tasse, che
 *       stanno in {@code tax} (e le commissioni in {@code fee}). Sul conto si muove la somma: 10,00 € di interessi
 *       con 2,60 € di tasse sono un movimento di 7,40 €. L'imposta di bollo ha {@code amount} 0 e tutto in
 *       {@code tax}.</li>
 *   <li><b>Non tutte le righe muovono denaro.</b> Il file contiene anche le operazioni sui titoli (azioni
 *       trasferite, assegnazioni): hanno una {@code category} diversa da {@code CASH} e nessun importo. Le
 *       saltiamo.</li>
 * </ul>
 * Funzioni pure, provate da TradeRepublicCsvTest.
 */
final class TradeRepublicCsv {

    /** Le colonne che usiamo: se il file le ha tutte, è un'esportazione di Trade Republic. */
    static final List<String> COLUMNS = List.of("date", "category", "type", "name", "amount", "fee", "tax",
            "currency", "description", "counterparty_name");

    /**
     * Una riga che muove denaro.
     *
     * @param line         il numero di riga nel file (l'intestazione è la riga 1), per i messaggi
     * @param amount       l'importo netto, con il segno: quello che si vede sul conto
     * @param type         il tipo di Trade Republic (INTEREST_PAYMENT, DIVIDEND, TRANSFER_INSTANT_OUTBOUND...)
     * @param counterparty chi manda o riceve i soldi; per i dividendi, la società che li paga; altrimenti null
     */
    record Row(int line, LocalDate date, BigDecimal amount, String currency, String type, String description,
            String counterparty) {
    }

    /** Le righe lette, quelle saltate perché non muovono denaro, e gli errori. */
    record Reading(List<Row> rows, int skipped, List<String> errors) {
    }

    private TradeRepublicCsv() {
    }

    static boolean recognizes(List<String> headers) {
        return headers.stream().map(TradeRepublicCsv::normalized).toList().containsAll(COLUMNS);
    }

    static Reading read(List<String> headers, List<List<String>> rows) {
        List<String> names = headers.stream().map(TradeRepublicCsv::normalized).toList();
        List<Row> read = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < rows.size(); i++) {
            int line = i + 2;
            Cells cells = new Cells(names, rows.get(i));
            try {
                if (!"CASH".equals(cells.get("category"))) {
                    skipped++; // operazioni sui titoli: nessun denaro sul conto
                    continue;
                }
                BigDecimal net = cells.number("amount").add(cells.number("fee")).add(cells.number("tax"))
                        .setScale(2, RoundingMode.HALF_UP);
                if (net.signum() == 0) {
                    skipped++;
                    continue;
                }
                String counterparty = cells.get("counterparty_name");
                if (counterparty == null) {
                    counterparty = cells.get("name"); // per i dividendi: "Alfa SpA"...
                }
                read.add(new Row(line, cells.date("date"), net, cells.required("currency"), cells.get("type"),
                        cells.get("description"), counterparty));
            } catch (IllegalArgumentException e) {
                errors.add("Riga " + line + ": " + e.getMessage() + ".");
            }
        }
        return new Reading(read, skipped, errors);
    }

    private static String normalized(String header) {
        return header.trim().toLowerCase(Locale.ROOT).replace("﻿", "");
    }

    /** Le celle di una riga, per nome di colonna. Vuoto vuol dire null. */
    private record Cells(List<String> names, List<String> values) {

        String get(String column) {
            int index = names.indexOf(column);
            if (index < 0 || index >= values.size()) {
                return null;
            }
            String value = values.get(index).trim();
            return value.isEmpty() ? null : value;
        }

        String required(String column) {
            String value = get(column);
            if (value == null) {
                throw new IllegalArgumentException("manca " + column);
            }
            return value;
        }

        /** Un numero con il punto per i decimali ("-150.000000"); vuoto vale 0. */
        BigDecimal number(String column) {
            String value = get(column);
            if (value == null) {
                return BigDecimal.ZERO;
            }
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(column + " \"" + value + "\" non è un numero");
            }
        }

        /** La data nel formato del file, "2026-07-01". */
        LocalDate date(String column) {
            String value = required(column);
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(column + " \"" + value + "\" non è una data");
            }
        }
    }
}
