package it.wallettracker.csvimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

/** Test del lettore dei file Excel (docs/importa-file.md, parte B). File costruiti da XlsxFixture, dati inventati. */
class SpreadsheetReaderTest {

    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    @Test
    void aFileLikeFinecoBecomesRowsOfText() {
        List<List<Object>> movements = new ArrayList<>();
        movements.add(XlsxFixture.finecoRow(LocalDate.of(2026, 11, 5), "3.95", "Sconto Canone Mensile",
                "Sconto Canone Mensile Ottobre 2026"));
        movements.add(XlsxFixture.finecoRow(LocalDate.of(2026, 3, 25), "-60.80", "Ritenuta Fiscale",
                "Ritenuta Fiscale ROMANIA-7OT45 6,5% Qta/Val.nom. 16000,000000"));
        byte[] file = XlsxFixture.build(XlsxFixture.finecoLike(movements));

        assertThat(SpreadsheetReader.isSpreadsheet(file)).isTrue();
        List<List<String>> rows = SpreadsheetReader.read(file);

        // Le righe vuote si saltano: 6 righe di note, l'intestazione, 2 movimenti.
        assertThat(rows).hasSize(9);
        assertThat(rows.get(0)).containsExactly("Conto Corrente: 0000001");
        assertThat(rows.get(6)).containsExactly("Data_Operazione", "Data_Valuta", "Entrate", "Uscite", "Descrizione",
                "Descrizione_Completa", "Stato");
        // Le date come gg/mm/aaaa, i numeri con il punto, le celle vuote al loro posto.
        assertThat(rows.get(7)).containsExactly("05/11/2026", "05/11/2026", "3.95", "", "Sconto Canone Mensile",
                "Sconto Canone Mensile Ottobre 2026", "Contabilizzato");
        assertThat(rows.get(8).subList(0, 4)).containsExactly("25/03/2026", "25/03/2026", "", "-60.8");
    }

    @Test
    void numbersDatesAndTextsOfEveryKind() {
        String sheet = """
                <worksheet xmlns="%s"><sheetData>
                  <row r="1">
                    <c r="A1"><v>0.15000000000000002</v></c>
                    <c r="C1" t="inlineStr"><is><t>scritto nella cella</t></is></c>
                    <c r="D1" t="str"><f>A1*2</f><v>calcolato</v></c>
                    <c r="E1" t="b"><v>1</v></c>
                    <c r="F1" s="2"><v>46331.75</v></c>
                    <c r="AA1" t="s"><v>0</v></c>
                  </row>
                </sheetData></worksheet>""".formatted(MAIN);
        byte[] file = XlsxFixture.zip(Map.of(
                "xl/workbook.xml", "<workbook xmlns=\"" + MAIN + "\"><sheets><sheet name=\"A\"/></sheets></workbook>",
                "xl/worksheets/sheet1.xml", sheet,
                // un testo fatto di pezzi formattati (<r>), con un & scritto come entità
                "xl/sharedStrings.xml", "<sst xmlns=\"" + MAIN + "\"><si><r><t>AT</t></r><r><t>&amp;T</t></r></si></sst>",
                // stile 2: un formato scritto apposta, con il testo tra virgolette che non conta
                "xl/styles.xml", "<styleSheet xmlns=\"" + MAIN + "\"><numFmts><numFmt numFmtId=\"164\" "
                        + "formatCode=\"&quot;giorno&quot; dd/mm/yyyy\"/></numFmts><cellXfs><xf numFmtId=\"0\"/>"
                        + "<xf numFmtId=\"2\"/><xf numFmtId=\"164\"/></cellXfs></styleSheet>"));

        List<String> row = SpreadsheetReader.read(file).getFirst();

        assertThat(row.get(0)).isEqualTo("0.15"); // il resto binario si toglie
        assertThat(row.get(1)).isEmpty(); // la colonna B non c'è: resta vuota
        assertThat(row.get(2)).isEqualTo("scritto nella cella");
        assertThat(row.get(3)).isEqualTo("calcolato"); // di una formula, il valore
        assertThat(row.get(4)).isEqualTo("VERO");
        assertThat(row.get(5)).isEqualTo("05/11/2026"); // una data con l'ora: conta il giorno
        assertThat(row).hasSize(27);
        assertThat(row.get(26)).isEqualTo("AT&T");
    }

    @Test
    void formatsAndColumns() {
        assertThat(SpreadsheetReader.isDateFormat("dd/mm/yyyy")).isTrue();
        assertThat(SpreadsheetReader.isDateFormat("[$-410]d mmmm yyyy")).isTrue();
        assertThat(SpreadsheetReader.isDateFormat("#,##0.00 \"EUR\"")).isFalse(); // "EUR" tra virgolette non conta
        assertThat(SpreadsheetReader.isDateFormat("0.00")).isFalse();
        assertThat(SpreadsheetReader.isDateFormat(null)).isFalse();
        assertThat(SpreadsheetReader.columnIndex("A14")).isZero();
        assertThat(SpreadsheetReader.columnIndex("Z1")).isEqualTo(25);
        assertThat(SpreadsheetReader.columnIndex("AA3")).isEqualTo(26);
    }

    @Test
    void notAnExcelFileOrAnOldOne() {
        assertThat(SpreadsheetReader.isSpreadsheet("Data;Importo\n".getBytes())).isFalse();
        assertThat(SpreadsheetReader.isOldExcel(new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0})).isTrue();
        // uno ZIP qualsiasi, senza l'elenco dei fogli
        assertThatThrownBy(() -> SpreadsheetReader.read(XlsxFixture.zip(Map.of("leggimi.txt", "ciao"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("manca l'elenco dei fogli");
    }

    @Test
    void anXmlThatAsksToReadAFileOfTheServerIsRefused() {
        // L'attacco "XXE": un'entità esterna che punta a un file del server. Senza DTD non si legge niente.
        String sheet = """
                <?xml version="1.0"?>
                <!DOCTYPE worksheet [<!ENTITY segreto SYSTEM "file:///etc/hostname">]>
                <worksheet xmlns="%s"><sheetData><row r="1">
                  <c r="A1" t="inlineStr"><is><t>&segreto;</t></is></c>
                </row></sheetData></worksheet>""".formatted(MAIN);
        byte[] file = XlsxFixture.zip(Map.of(
                "xl/workbook.xml", "<workbook xmlns=\"" + MAIN + "\"><sheets><sheet name=\"A\"/></sheets></workbook>",
                "xl/worksheets/sheet1.xml", sheet));

        assertThatThrownBy(() -> SpreadsheetReader.read(file)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aZipBombIsStoppedBeforeFillingTheMemory() throws IOException {
        // Pochi kilobyte nello ZIP, oltre 30 MB una volta decompresso.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
            zip.write("<workbook/>".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            byte[] zeros = new byte[1024 * 1024];
            Arrays.fill(zeros, (byte) ' ');
            for (long i = 0; i <= SpreadsheetReader.MAX_ENTRY_BYTES / zeros.length; i++) {
                zip.write(zeros);
            }
            zip.closeEntry();
        }
        assertThat(out.size()).isLessThan(200_000);

        assertThatThrownBy(() -> SpreadsheetReader.read(out.toByteArray()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("troppo grande");
    }
}
