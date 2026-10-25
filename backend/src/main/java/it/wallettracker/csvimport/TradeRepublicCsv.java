package it.wallettracker.csvimport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Il file "Esportazione transazioni" di Trade Republic (docs/31). A differenza degli estratti conto delle banche
 * non si legge con una mappatura scelta dall'utente: ha colonne fisse, con un nome, e due particolarità.
 *
 * <ul>
 *   <li><b>L'importo è lordo.</b> Per interessi e dividendi la colonna {@code amount} è prima delle tasse, che
 *       stanno in {@code tax} (e le commissioni in {@code fee}). Sul conto si muove la somma: 10,00 € di interessi
 *       con 2,60 € di tasse sono un movimento di 7,40 €. L'imposta di bollo ha {@code amount} 0 e tutto in
 *       {@code tax}.</li>
 *   <li><b>Non tutte le righe muovono denaro.</b> Il file contiene anche le operazioni sui titoli senza denaro
 *       (azioni trasferite, assegnazioni: {@code category} DELIVERY o CORPORATE_ACTION), con importo e valuta
 *       vuoti. Le saltiamo. Gli acquisti e le vendite invece ({@code category} TRADING) il denaro lo muovono, e
 *       molto: si riconoscono dall'importo, non dalla categoria.</li>
 * </ul>
 * Funzioni pure, provate da TradeRepublicCsvTest.
 */
final class TradeRepublicCsv {

    /** Le colonne che usiamo: se il file le ha tutte, è un'esportazione di Trade Republic. */
    static final List<String> COLUMNS = List.of("date", "category", "type", "name", "amount", "fee", "tax",
            "currency", "description", "transaction_id", "counterparty_name");

    /**
     * Una riga che muove denaro.
     *
     * @param line         il numero di riga nel file (l'intestazione è la riga 1), per i messaggi
     * @param amount       l'importo netto, con il segno: quello che si vede sul conto
     * @param type         il tipo di Trade Republic (INTEREST_PAYMENT, DIVIDEND, TRANSFER_INSTANT_OUTBOUND...)
     * @param counterparty  chi manda o riceve i soldi; per dividendi e acquisti, il titolo; altrimenti null
     * @param transactionId l'identificativo di Trade Republic, unico per ogni operazione (null se manca)
     * @param cells         la riga così com'è, colonna per colonna: diventa il raw_json di un movimento aggiunto
     */
    record Row(int line, LocalDate date, BigDecimal amount, String currency, String type, String description,
            String counterparty, String transactionId, Map<String, String> cells) {
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
                if (cells.get("amount") == null && cells.get("fee") == null && cells.get("tax") == null) {
                    skipped++; // operazioni sui titoli senza denaro (azioni trasferite, assegnazioni)
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
                    counterparty = cells.get("name"); // per dividendi e acquisti: il titolo, "Alfa SpA"...
                }
                read.add(new Row(line, cells.date("date"), net, cells.required("currency"), cells.get("type"),
                        cells.get("description"), counterparty, cells.get("transaction_id"), cells.asMap()));
            } catch (IllegalArgumentException e) {
                errors.add("Riga " + line + ": " + e.getMessage() + ".");
            }
        }
        return new Reading(read, skipped, errors);
    }

    private static String normalized(String header) {
        return header.trim().toLowerCase(Locale.ROOT).replace("\uFEFF", "");
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

        /** La riga come mappa colonna → valore, nell'ordine del file. */
        Map<String, String> asMap() {
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i < names.size() && i < values.size(); i++) {
                map.putIfAbsent(names.get(i), values.get(i));
            }
            return map;
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
