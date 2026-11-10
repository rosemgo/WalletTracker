package it.wallettracker.csvimport;

import java.util.List;
import java.util.Locale;

import it.wallettracker.csvimport.ImportMapping.AmountMode;
import it.wallettracker.csvimport.ImportMapping.DecimalSeparator;

/**
 * Propone una mappatura guardando i nomi delle colonne e i primi valori. È solo una proposta: l'utente la
 * vede nella dashboard e la corregge. Classe "pura", provata da ColumnGuesserTest.
 *
 * <p>I nomi cercati sono in italiano, inglese e tedesco, le lingue degli estratti conto più comuni per chi
 * vive in Italia (Trade Republic e N26 sono banche tedesche).
 */
public final class ColumnGuesser {

    // Per ogni campo, le parole da cercare nell'intestazione, dalla più sicura alla meno sicura.
    private static final List<String> DATE = List.of("data operazione", "data contabile", "data", "date",
            "datum", "buchungstag", "booking");
    private static final List<String> AMOUNT = List.of("importo", "amount", "betrag", "valore", "value");
    private static final List<String> INCOME = List.of("entrate", "avere", "accrediti", "credit", "haben", "in");
    private static final List<String> EXPENSE = List.of("uscite", "dare", "addebiti", "debit", "soll", "out");
    // "descrizione completa" prima di "descrizione": Fineco ha tutte e due, e la completa è quella che manda la banca
    // con Enable Banking ("Ritenuta Fiscale BTP-1OT53 4,5% Qta/Val.nom. …", non solo "Ritenuta Fiscale").
    private static final List<String> DESCRIPTION = List.of("descrizione completa", "descrizione", "causale",
            "description",
            "verwendungszweck", "beschreibung", "dettagli", "details", "memo", "note", "type", "tipo");
    private static final List<String> COUNTERPARTY = List.of("controparte", "beneficiario", "ordinante",
            "esercente", "counterparty", "payee", "merchant", "empfänger", "partner", "name");
    // "Valuta" in italiano vuol dire anche "data valuta": per la valuta cerchiamo solo parole senza dubbi.
    private static final List<String> CURRENCY = List.of("divisa", "currency", "währung", "waehrung");

    private ColumnGuesser() {
    }

    /**
     * Il file dei movimenti di Tinaba si riconosce dalle sue colonne, tutte insieme: nessun'altra banca ha "CRO/NOP" e
     * "Community". I suoi importi non hanno il segno (docs/tinaba.md): sono entrate solo le righe "ricevuto"
     * ("Ricevuto da …", "Trasferimento ricevuto per campagna cashback"), tutte le altre sono uscite (versamenti nel Robo
     * advisor, acquisti del credito Power).
     */
    static final List<String> TINABA_HEADERS = List.of("data", "categoria", "controparte", "descrizione",
            "causale/messaggio", "cro/nop", "importo", "community");

    /** Le parole delle entrate di Tinaba: nella descrizione, "Ricevuto da …" e "Trasferimento ricevuto …". */
    static final String TINABA_INCOME_WORDS = "ricevuto, ricevuta";

    public static ImportMapping guess(String delimiter, List<String> headers, List<List<String>> samples) {
        return guess(delimiter, headers, samples, false);
    }

    /**
     * La proposta. Da un file Excel ({@code spreadsheet}) i numeri escono sempre con il punto (SpreadsheetReader):
     * il separatore dei decimali non si indovina, è il punto. Indovinarlo sarebbe pericoloso: se le prime righe
     * avessero solo importi interi ("-1"), si sceglierebbe la virgola, e "3.95" diventerebbe 395.
     */
    public static ImportMapping guess(String delimiter, List<String> headers, List<List<String>> samples,
            boolean spreadsheet) {
        List<String> names = names(headers);
        if (names.equals(TINABA_HEADERS)) {
            return tinaba(delimiter, samples);
        }
        Integer date = find(names, DATE, null);
        Integer amount = find(names, AMOUNT, date);
        Integer income = find(names, INCOME, date);
        Integer expense = find(names, EXPENSE, date);
        AmountMode mode = amount == null && income != null && expense != null ? AmountMode.SPLIT : AmountMode.SINGLE;
        Integer description = find(names, DESCRIPTION, null);
        Integer counterparty = find(names, COUNTERPARTY, null);
        Integer currency = find(names, CURRENCY, null);
        Integer amountForSamples = mode == AmountMode.SINGLE ? amount : income;
        return new ImportMapping(delimiter, date, guessDateFormat(values(samples, date)), mode,
                mode == AmountMode.SINGLE ? amount : null, mode == AmountMode.SPLIT ? income : null,
                mode == AmountMode.SPLIT ? expense : null,
                spreadsheet ? DecimalSeparator.DOT : guessDecimalSeparator(values(samples, amountForSamples)),
                description,
                counterparty != null && counterparty.equals(description) ? null : counterparty, currency, null,
                null, null);
    }

    /** Su quante righe, all'inizio del file, si cerca l'intestazione. */
    static final int HEADER_SEARCH_ROWS = 30;

    /**
     * La riga dell'intestazione (docs/importa-file.md, parte C): la prima, tra le prime {@link #HEADER_SEARCH_ROWS},
     * con almeno tre celle piene, una colonna della data e una dell'importo (o delle entrate e delle uscite). Alcune
     * banche scrivono sopra la tabella delle righe di testo: Fineco ne ha dodici ("Conto Corrente: …", "Periodo Dal:
     * …", delle note) prima di "Data_Operazione | Data_Valuta | Entrate | Uscite | …". Se non ce n'è una così, è la
     * prima riga, come prima.
     */
    public static int headerRow(List<List<String>> rows) {
        for (int i = 0; i < Math.min(rows.size(), HEADER_SEARCH_ROWS); i++) {
            List<String> names = names(rows.get(i));
            if (names.stream().filter(name -> !name.isEmpty()).count() < 3) {
                continue;
            }
            Integer date = find(names, DATE, null);
            boolean amount = find(names, AMOUNT, date) != null
                    || find(names, INCOME, date) != null && find(names, EXPENSE, date) != null;
            if (date != null && amount) {
                return i;
            }
        }
        return 0;
    }

    /** I nomi delle colonne come li confrontiamo: minuscoli, senza spazi ai lati, "_" come spazio (Data_Operazione). */
    private static List<String> names(List<String> headers) {
        return headers.stream().map(h -> h.trim().toLowerCase(Locale.ROOT).replace('_', ' ')).toList();
    }

    /** La mappatura del file di Tinaba: le colonne note, gli importi senza segno, le parole delle entrate. */
    private static ImportMapping tinaba(String delimiter, List<List<String>> samples) {
        int date = TINABA_HEADERS.indexOf("data");
        int amount = TINABA_HEADERS.indexOf("importo");
        int description = TINABA_HEADERS.indexOf("descrizione");
        return new ImportMapping(delimiter, date, guessDateFormat(values(samples, date)), AmountMode.UNSIGNED, amount,
                null, null, guessDecimalSeparator(values(samples, amount)), description,
                TINABA_HEADERS.indexOf("controparte"), null, description, TINABA_INCOME_WORDS,
                TINABA_HEADERS.indexOf("causale/messaggio"));
    }

    /**
     * La prima colonna il cui nome contiene una delle parole, provando le parole in ordine. Le parole corte
     * ("in", "out") devono essere il nome intero, per non trovarle dentro altre parole ("Descrizione"
     * contiene "in"). {@code skip} esclude una colonna già usata.
     */
    private static Integer find(List<String> names, List<String> words, Integer skip) {
        for (String word : words) {
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                boolean matches = word.length() <= 3 ? name.equals(word) : name.contains(word);
                if (matches && (skip == null || i != skip)) {
                    return i;
                }
            }
        }
        return null;
    }

    /** Il primo formato con cui tutte le date di esempio si leggono senza errori. */
    static String guessDateFormat(List<String> values) {
        for (String format : RowReader.DATE_FORMATS) {
            boolean all = !values.isEmpty();
            for (String value : values) {
                try {
                    RowReader.parseDate(value, format);
                } catch (RowReader.RowException e) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return format;
            }
        }
        return RowReader.DATE_FORMATS.getFirst();
    }

    /**
     * Virgola o punto? Se un importo finisce con una virgola seguita da 1 o 2 cifre ("12,5" o "1.234,56"),
     * la virgola è il separatore dei decimali. Altrimenti il punto.
     */
    static DecimalSeparator guessDecimalSeparator(List<String> values) {
        for (String value : values) {
            String text = value.replaceAll("[^0-9,.\\-]", "");
            if (text.matches(".*,\\d{1,2}")) {
                return DecimalSeparator.COMMA;
            }
            if (text.matches(".*\\.\\d{1,2}")) {
                return DecimalSeparator.DOT;
            }
        }
        return DecimalSeparator.COMMA;
    }

    private static List<String> values(List<List<String>> rows, Integer column) {
        if (column == null) {
            return List.of();
        }
        return rows.stream()
                .filter(row -> column < row.size() && !row.get(column).isBlank())
                .map(row -> row.get(column))
                .toList();
    }
}
