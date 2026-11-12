package it.wallettracker.csvimport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Legge il primo foglio di un file Excel ({@code .xlsx}) come righe e colonne di testo, come {@link CsvReader} fa con
 * un CSV (docs/importa-file.md, parte B). Così il resto dell'importazione non cambia: la mappatura, l'anteprima, i
 * controlli. Le banche esportano spesso in Excel (Fineco: "Esporta in excel").
 *
 * <p><b>Che cos'è un .xlsx.</b> Un archivio ZIP con dentro dei file XML (il formato si chiama Office Open XML):
 * <ul>
 *   <li>{@code xl/workbook.xml}: l'elenco dei fogli; {@code xl/_rels/workbook.xml.rels} dice in quale file sta
 *       ognuno (di solito {@code xl/worksheets/sheet1.xml});</li>
 *   <li>il foglio: righe {@code <row>} di celle {@code <c r="B14" t="s" s="3"><v>7</v></c>};</li>
 *   <li>{@code xl/sharedStrings.xml}: i testi, scritti una volta sola. Una cella con {@code t="s"} contiene il
 *       numero del testo, non il testo;</li>
 *   <li>{@code xl/styles.xml}: i formati. Una data in Excel è un <b>numero</b> (i giorni dal 30/12/1899: 46331 è il
 *       5/11/2026): solo il formato della cella ({@code s="3"}) dice che va letto come data.</li>
 * </ul>
 * Le date escono come "05/11/2026" e i numeri con il punto ("-255.38"): la mappatura proposta lo sa
 * ({@link ColumnGuesser}).
 *
 * <p><b>Perché non una libreria</b> (Apache POI): fa molto di più, ma è grande e ha avuto problemi di sicurezza
 * proprio nella lettura dell'XML. Qui serve poco, e con gli strumenti di Java si fa in sicurezza:
 * <ul>
 *   <li>niente entità esterne né DTD nell'XML (l'attacco "XXE": un XML che chiede di leggere un file del server);</li>
 *   <li>limiti sulle dimensioni decompresse: una "bomba ZIP" di pochi kilobyte può diventare gigabyte.</li>
 * </ul>
 * Classe "pura", provata da SpreadsheetReaderTest.
 */
public final class SpreadsheetReader {

    /** Un .xlsx (come ogni ZIP) comincia con questi quattro byte: "PK" e 3, 4. */
    private static final byte[] ZIP_SIGNATURE = {0x50, 0x4B, 0x03, 0x04};

    /** Un vecchio .xls (il formato binario di Excel 97-2003) comincia così. */
    private static final byte[] OLD_EXCEL_SIGNATURE = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0};

    /** Al massimo per ogni file dentro lo ZIP, e in tutto, una volta decompresso: contro le "bombe ZIP". */
    static final long MAX_ENTRY_BYTES = 30L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 60L * 1024 * 1024;
    static final int MAX_ENTRIES = 2_000;

    /** I formati di data predefiniti di Excel (ECMA-376, parte 1, 18.8.30): 14 = "d/m/yyyy", 22 = data e ora... */
    private static final Set<Integer> BUILTIN_DATE_FORMATS = Set.of(14, 15, 16, 17, 18, 19, 20, 21, 22, 45, 46, 47);

    /** Il giorno zero delle date di Excel (sistema "1900", con il suo famoso 29 febbraio 1900 che non esiste). */
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private SpreadsheetReader() {
    }

    /** Il file è un .xlsx (un ZIP)? Si guarda l'inizio, non il nome: un file rinominato si riconosce lo stesso. */
    public static boolean isSpreadsheet(byte[] content) {
        return startsWith(content, ZIP_SIGNATURE);
    }

    /** Il file è un vecchio .xls? Non lo leggiamo: si salva come .xlsx o CSV. */
    public static boolean isOldExcel(byte[] content) {
        return startsWith(content, OLD_EXCEL_SIGNATURE);
    }

    /**
     * Le righe del primo foglio, ognuna come lista di testi. Le righe completamente vuote si saltano; una cella vuota
     * in mezzo resta, come testo vuoto, così le colonne restano al loro posto.
     *
     * @throws IllegalArgumentException se il file non è un Excel leggibile (con un messaggio per l'utente)
     */
    public static List<List<String>> read(byte[] content) {
        Map<String, byte[]> parts = unzip(content);
        String sheet = firstSheetPath(parts);
        byte[] sheetXml = parts.get(sheet);
        if (sheetXml == null) {
            throw new IllegalArgumentException("Il file Excel non ha un foglio leggibile.");
        }
        List<String> strings = parts.containsKey("xl/sharedStrings.xml")
                ? sharedStrings(parts.get("xl/sharedStrings.xml")) : List.of();
        Set<Integer> dateStyles = parts.containsKey("xl/styles.xml") ? dateStyles(parts.get("xl/styles.xml")) : Set.of();
        return rows(sheetXml, strings, dateStyles);
    }

    /** I file dentro lo ZIP che ci servono (solo gli XML sotto xl/), con i limiti di dimensione. */
    private static Map<String, byte[]> unzip(byte[] content) {
        Map<String, byte[]> parts = new HashMap<>();
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IllegalArgumentException("Il file Excel ha troppe parti: non sembra un estratto conto.");
                }
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("xl/") || !name.endsWith(".xml") && !name.endsWith(".rels")) {
                    continue;
                }
                byte[] data = readLimited(zip);
                total += data.length;
                if (total > MAX_TOTAL_BYTES) {
                    throw new IllegalArgumentException("Il file Excel, decompresso, è troppo grande.");
                }
                parts.put(name, data);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Il file non è un Excel (.xlsx) leggibile: è danneggiato?");
        }
        if (!parts.containsKey("xl/workbook.xml")) {
            throw new IllegalArgumentException("Il file non è un Excel (.xlsx) leggibile: manca l'elenco dei fogli.");
        }
        return parts;
    }

    /** Un file dello ZIP, fermandosi oltre il limite (la dimensione dichiarata nello ZIP può mentire). */
    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long size = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            size += read;
            if (size > MAX_ENTRY_BYTES) {
                throw new IllegalArgumentException("Il file Excel, decompresso, è troppo grande.");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** Il percorso del primo foglio: il primo {@code <sheet>} di workbook.xml, cercato nelle relazioni. */
    private static String firstSheetPath(Map<String, byte[]> parts) {
        String relationId = null;
        try {
            XMLStreamReader xml = parser(parts.get("xl/workbook.xml"));
            while (xml.hasNext() && relationId == null) {
                if (xml.next() == XMLStreamReader.START_ELEMENT && "sheet".equals(xml.getLocalName())) {
                    relationId = attribute(xml, "id"); // r:id, nello spazio dei nomi delle relazioni
                }
            }
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("Il file Excel ha un elenco dei fogli illeggibile.");
        }
        byte[] rels = parts.get("xl/_rels/workbook.xml.rels");
        if (relationId != null && rels != null) {
            try {
                XMLStreamReader xml = parser(rels);
                while (xml.hasNext()) {
                    if (xml.next() == XMLStreamReader.START_ELEMENT && "Relationship".equals(xml.getLocalName())
                            && relationId.equals(xml.getAttributeValue(null, "Id"))) {
                        String target = xml.getAttributeValue(null, "Target");
                        return target.startsWith("/") ? target.substring(1) : "xl/" + target;
                    }
                }
            } catch (XMLStreamException e) {
                throw new IllegalArgumentException("Il file Excel ha un elenco dei fogli illeggibile.");
            }
        }
        return "xl/worksheets/sheet1.xml"; // il nome usato da quasi tutti i programmi
    }

    /** I testi condivisi, in ordine: un {@code <si>} può avere un {@code <t>} o più pezzi formattati {@code <r><t>}. */
    private static List<String> sharedStrings(byte[] content) {
        List<String> strings = new ArrayList<>();
        try {
            XMLStreamReader xml = parser(content);
            StringBuilder current = null;
            boolean inPhonetic = false; // <rPh>: la pronuncia (giapponese), non fa parte del testo
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamReader.START_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "si" -> current = new StringBuilder();
                        case "rPh" -> inPhonetic = true;
                        case "t" -> {
                            if (current != null && !inPhonetic) {
                                current.append(xml.getElementText());
                            }
                        }
                        default -> {
                            // altri elementi (formattazione): il testo sta solo nei <t>
                        }
                    }
                } else if (event == XMLStreamReader.END_ELEMENT) {
                    if ("si".equals(xml.getLocalName()) && current != null) {
                        strings.add(current.toString());
                        current = null;
                    } else if ("rPh".equals(xml.getLocalName())) {
                        inPhonetic = false;
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("Il file Excel ha i testi illeggibili.");
        }
        return strings;
    }

    /**
     * Quali stili di cella ({@code s="3"}) sono date: quelli con un formato predefinito di data, o con un formato
     * scritto apposta che ha giorni, mesi o anni ("dd/mm/yyyy"). I numeri della posizione nell'elenco
     * {@code <cellXfs>} sono il valore di {@code s}.
     */
    private static Set<Integer> dateStyles(byte[] content) {
        Map<Integer, String> customFormats = new HashMap<>();
        List<Integer> styleFormats = new ArrayList<>();
        try {
            XMLStreamReader xml = parser(content);
            boolean inCellXfs = false;
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamReader.START_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "numFmt" -> customFormats.put(Integer.parseInt(xml.getAttributeValue(null, "numFmtId")),
                                xml.getAttributeValue(null, "formatCode"));
                        case "cellXfs" -> inCellXfs = true;
                        case "xf" -> {
                            if (inCellXfs) {
                                String id = xml.getAttributeValue(null, "numFmtId");
                                styleFormats.add(id == null ? 0 : Integer.parseInt(id));
                            }
                        }
                        default -> {
                            // il resto (caratteri, bordi, colori) non conta
                        }
                    }
                } else if (event == XMLStreamReader.END_ELEMENT && "cellXfs".equals(xml.getLocalName())) {
                    inCellXfs = false;
                }
            }
        } catch (XMLStreamException | NumberFormatException e) {
            return Set.of(); // senza stili leggibili le date restano numeri: l'anteprima lo mostra
        }
        Set<Integer> dates = new HashSet<>();
        for (int style = 0; style < styleFormats.size(); style++) {
            int format = styleFormats.get(style);
            if (BUILTIN_DATE_FORMATS.contains(format) || isDateFormat(customFormats.get(format))) {
                dates.add(style);
            }
        }
        return dates;
    }

    /** Un formato scritto apposta è una data se, tolti i testi tra virgolette e le parti tra [ ], ha d, m o y. */
    static boolean isDateFormat(String code) {
        if (code == null) {
            return false;
        }
        String bare = code.replaceAll("\"[^\"]*\"", "").replaceAll("\\[[^]]*]", "").replaceAll("\\\\.", "")
                .toLowerCase();
        return bare.matches(".*[dmy].*");
    }

    /** Le righe del foglio. */
    private static List<List<String>> rows(byte[] sheet, List<String> strings, Set<Integer> dateStyles) {
        List<List<String>> rows = new ArrayList<>();
        try {
            XMLStreamReader xml = parser(sheet);
            List<String> row = null;
            String type = null;
            String reference = null;
            int style = 0;
            String value = null;
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamReader.START_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "row" -> row = new ArrayList<>();
                        case "c" -> {
                            type = xml.getAttributeValue(null, "t");
                            reference = xml.getAttributeValue(null, "r");
                            String s = xml.getAttributeValue(null, "s");
                            style = s == null ? 0 : Integer.parseInt(s);
                            value = null;
                        }
                        case "v" -> value = xml.getElementText();
                        case "t" -> value = (value == null ? "" : value) + xml.getElementText(); // <is><t>: inlineStr
                        default -> {
                            // <f> (la formula): basta il valore calcolato, in <v>
                        }
                    }
                } else if (event == XMLStreamReader.END_ELEMENT) {
                    if ("c".equals(xml.getLocalName()) && row != null) {
                        int column = reference == null ? row.size() : columnIndex(reference);
                        while (row.size() < column) {
                            row.add(""); // le celle vuote non sono scritte: le rimettiamo, così le colonne tornano
                        }
                        row.add(cellText(type, value, style, strings, dateStyles));
                    } else if ("row".equals(xml.getLocalName()) && row != null) {
                        if (row.stream().anyMatch(cell -> !cell.isBlank())) {
                            rows.add(row);
                        }
                        row = null;
                    }
                }
            }
        } catch (XMLStreamException | NumberFormatException e) {
            throw new IllegalArgumentException("Il foglio del file Excel non si legge.", e);
        }
        return rows;
    }

    /** Il testo di una cella, secondo il suo tipo. */
    private static String cellText(String type, String value, int style, List<String> strings, Set<Integer> dateStyles) {
        if (value == null) {
            return "";
        }
        if ("s".equals(type)) {
            int index = Integer.parseInt(value.trim());
            return index >= 0 && index < strings.size() ? strings.get(index) : "";
        }
        if ("inlineStr".equals(type) || "str".equals(type) || "e".equals(type)) {
            return value;
        }
        if ("b".equals(type)) {
            return "1".equals(value.trim()) ? "VERO" : "FALSO";
        }
        // un numero: una data se lo stile lo dice, altrimenti il numero com'è, con il punto
        BigDecimal number = new BigDecimal(value.trim());
        if (dateStyles.contains(style)) {
            return EXCEL_EPOCH.plusDays(number.setScale(0, RoundingMode.FLOOR).longValueExact()).format(DATE);
        }
        // Excel salva i numeri in binario: 0,15 può diventare 0.15000000000000002. Dieci decimali bastano.
        return number.setScale(10, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** "B14" → 1: le lettere della colonna in base 26 (A = 0, Z = 25, AA = 26...). */
    static int columnIndex(String reference) {
        int column = 0;
        for (char c : reference.toUpperCase().toCharArray()) {
            if (c < 'A' || c > 'Z') {
                break;
            }
            column = column * 26 + (c - 'A' + 1);
        }
        return column - 1;
    }

    /**
     * Un lettore XML "a flusso" (StAX), senza DTD né entità esterne. Le cinque entità predefinite (&amp;amp; &amp;lt;
     * ...) restano: "AT&amp;amp;T" diventa "AT&amp;T", come deve.
     */
    private static XMLStreamReader parser(byte[] content) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory.createXMLStreamReader(new ByteArrayInputStream(content));
    }

    private static String attribute(XMLStreamReader xml, String localName) {
        for (int i = 0; i < xml.getAttributeCount(); i++) {
            if (localName.equals(xml.getAttributeLocalName(i))) {
                return xml.getAttributeValue(i);
            }
        }
        return null;
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content == null || content.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (content[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
