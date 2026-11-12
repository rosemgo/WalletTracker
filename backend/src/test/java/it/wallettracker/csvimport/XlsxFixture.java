package it.wallettracker.csvimport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Costruisce un piccolo file Excel (.xlsx) per i test, come lo salva Excel: i testi in sharedStrings.xml, le date come
 * numeri con uno stile di data (numFmtId 14), le celle vuote non scritte. Ogni valore di una riga può essere un testo,
 * un numero (BigDecimal o Double), una data (LocalDate) o null (cella vuota).
 */
final class XlsxFixture {

    private XlsxFixture() {
    }

    /** Il file con queste righe nel primo foglio. */
    static byte[] build(List<List<Object>> rows) {
        return build(sheetXml(rows, new LinkedHashMap<>()), rows);
    }

    /** Il file con un foglio scritto a mano (per i casi speciali), e i testi condivisi delle righe. */
    static byte[] build(String sheetXml, List<List<Object>> rowsForStrings) {
        Map<String, Integer> strings = new LinkedHashMap<>();
        if (rowsForStrings != null) {
            sheetXml(rowsForStrings, strings); // riempie l'elenco dei testi, nello stesso ordine
        }
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/"
                + "package/2006/content-types\"/>");
        parts.put("xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="Movimenti" sheetId="1" r:id="rId1"/></sheets>
                </workbook>""");
        parts.put("xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="worksheet" Target="worksheets/foglio.xml"/>
                </Relationships>""");
        parts.put("xl/worksheets/foglio.xml", sheetXml);
        StringBuilder shared = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><sst xmlns=\"http://"
                + "schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        strings.keySet().forEach(s -> shared.append("<si><t>").append(escape(s)).append("</t></si>"));
        parts.put("xl/sharedStrings.xml", shared.append("</sst>").toString());
        // Stile 0: normale; stile 1: data (formato predefinito 14, "d/m/yyyy").
        parts.put("xl/styles.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="14"/></cellXfs>
                </styleSheet>""");
        return zip(parts);
    }

    /** Lo ZIP con questi file. */
    static byte[] zip(Map<String, String> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static String sheetXml(List<List<Object>> rows, Map<String, Integer> strings) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://"
                + "schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            xml.append("<row r=\"").append(r + 1).append("\">");
            List<Object> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                Object value = row.get(c);
                if (value == null) {
                    continue; // come Excel: una cella vuota non si scrive
                }
                String ref = column(c) + (r + 1);
                if (value instanceof LocalDate date) {
                    long serial = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), date);
                    xml.append("<c r=\"").append(ref).append("\" s=\"1\"><v>").append(serial).append("</v></c>");
                } else if (value instanceof BigDecimal || value instanceof Double || value instanceof Integer) {
                    xml.append("<c r=\"").append(ref).append("\"><v>").append(value).append("</v></c>");
                } else {
                    int index = strings.computeIfAbsent(value.toString(), k -> strings.size());
                    xml.append("<c r=\"").append(ref).append("\" t=\"s\"><v>").append(index).append("</v></c>");
                }
            }
            xml.append("</row>");
        }
        return xml.append("</sheetData></worksheet>").toString();
    }

    /** 0 → "A", 25 → "Z", 26 → "AA". */
    private static String column(int index) {
        StringBuilder name = new StringBuilder();
        for (int n = index + 1; n > 0; n = (n - 1) / 26) {
            name.insert(0, (char) ('A' + (n - 1) % 26));
        }
        return name.toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Le righe di un estratto conto come quello di Fineco: dodici righe di note sopra la tabella. Dati inventati. */
    static List<List<Object>> finecoLike(List<List<Object>> movements) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Conto Corrente: 0000001"));
        rows.add(List.of("Intestazione Conto Corrente: MARIO ROSSI"));
        rows.add(List.of("Periodo Dal: 01/01/2026 Al: 10/11/2026"));
        rows.add(new ArrayList<>());
        rows.add(List.of("Saldo Finale: 1.000,00"));
        rows.add(List.of("Nota: Il saldo finale del periodo temporale selezionato considera solo i movimenti "
                + "contabilizzati, sul conto corrente."));
        rows.add(new ArrayList<>());
        rows.add(List.of("Risultati Ricerca"));
        rows.add(new ArrayList<>());
        rows.add(List.of("Data_Operazione", "Data_Valuta", "Entrate", "Uscite", "Descrizione", "Descrizione_Completa",
                "Stato"));
        rows.addAll(movements);
        return rows;
    }

    /** Una riga di movimento come quelle di Fineco: entrate o uscite, una delle due vuota. */
    static List<Object> finecoRow(LocalDate date, String amount, String description, String full) {
        List<Object> row = new ArrayList<>();
        row.add(date);
        row.add(date);
        BigDecimal value = new BigDecimal(amount);
        row.add(value.signum() > 0 ? value : null);
        row.add(value.signum() < 0 ? value : null);
        row.add(description);
        row.add(full);
        row.add("Contabilizzato");
        return row;
    }
}
