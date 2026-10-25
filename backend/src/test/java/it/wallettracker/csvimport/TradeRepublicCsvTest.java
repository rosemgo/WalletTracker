package it.wallettracker.csvimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * La lettura del file di Trade Republic. Le righe hanno la forma di quelle vere (stesse colonne, stessi formati),
 * con nomi, IBAN e identificativi inventati.
 */
class TradeRepublicCsvTest {

    /** L'intestazione vera del file "Esportazione transazioni". */
    static final String HEADER = "\"datetime\",\"date\",\"account_type\",\"category\",\"type\",\"asset_class\",\"name\","
            + "\"symbol\",\"shares\",\"price\",\"amount\",\"fee\",\"tax\",\"currency\",\"original_amount\","
            + "\"original_currency\",\"fx_rate\",\"description\",\"transaction_id\",\"counterparty_name\","
            + "\"counterparty_iban\",\"payment_reference\",\"mcc_code\"";

    /** Un file di esempio: interessi, bonifico, bollo, dividendo annullato e ripagato, un'operazione sui titoli. */
    static final String CSV = HEADER + """

            "2026-07-01T07:00:00.000000Z","2026-07-01","DEFAULT","CASH","INTEREST_PAYMENT","","","","","","10.000000","","-2.60","EUR","","","","Interest payment for payout collection aaaa-1111","id-1","","","",""
            "2026-07-07T13:00:00.000000Z","2026-07-07","DEFAULT","CASH","TRANSFER_INSTANT_OUTBOUND","","Mario Rossi","","","","-150.000000","","","EUR","","","","Outgoing transfer for Mario Rossi (IT00X0000000000000000000001)","id-2","Mario Rossi","IT00X0000000000000000000001","",""
            "2026-07-09T16:00:00.000000Z","2026-07-09","DEFAULT","CASH","TAX_OPTIMIZATION","","","","","","0.000000","","-34.20","EUR","","","","Stamp Duty Tax (Portfolio)","id-3","","","",""
            "2026-07-20T09:05:00.000000Z","2026-07-20","DEFAULT","CASH","DIVIDEND","STOCK","Alfa SpA","IT0000000001","400.0000000000","","200.000000","","-52.00","EUR","","","","Cash Dividend for ISIN IT0000000001","id-4","","","",""
            "2026-07-20T09:01:00.000000Z","2026-07-20","DEFAULT","CASH","DIVIDEND","STOCK","Alfa SpA","IT0000000001","400.0000000000","","-200.000000","","52.00","EUR","","","","Cancellation Cash Dividend for ISIN IT0000000001","id-5","","","",""
            "2026-10-06T10:00:00.000Z","2026-10-01","DEFAULT","DELIVERY","FREE_RECEIPT","STOCK","Alfa SpA","IT0000000001","-400.0000000000","","","","","","","","","FREE_RECEIPT IT0000000001","id-6","","","",""
            """;

    /**
     * Lo stesso file, ma più lungo: parte da maggio, prima dello storico della banca (nei test di CompletionControllerTest
     * la banca ha dato i movimenti dal 1° luglio). Un acquisto di titoli (TRADING: commissione e tassa nel netto), una
     * distribuzione di capitale, un bonifico senza counterparty_name, e un interesse a due giorni dal 1° luglio.
     */
    static final String LONG_CSV = HEADER + """

            "2026-05-04T10:00:00.000Z","2026-05-04","DEFAULT","TRADING","BUY","STOCK","Beta SpA","IT0000000002","100.0000000000","10.0000000000","-1000.00","-1.00","-2.00","EUR","","","","Buy trade IT0000000002 BETA SPA, quantity: 100","id-10","","","",""
            "2026-05-20T10:00:00.000000Z","2026-05-20","DEFAULT","CASH","DIVIDEND_EQUIVALENT_PAYMENT","STOCK","Beta SpA","IT0000000002","100.0000000000","","100.000000","","-26.00","EUR","","","","Capital Distribution for ISIN IT0000000002","id-11","","","",""
            "2026-06-01T10:00:00.000000Z","2026-06-01","DEFAULT","CASH","TRANSFER_INBOUND","","","","","","500.000000","","","EUR","","","","Incoming transfer from MARIO ROSSI (IT00X0000000000000000000002)","id-12","","","",""
            "2026-06-29T10:00:00.000000Z","2026-06-29","DEFAULT","CASH","INTEREST_PAYMENT","","","","","","5.000000","","","EUR","","","","Interest payment for payout collection bbbb-2222","id-13","","","",""
            """ + CSV.substring(HEADER.length() + 1);

    static List<List<String>> lines(String csv) {
        return CsvReader.read(csv, ',');
    }

    @Test
    void recognizesTheFileByItsColumns() {
        assertThat(TradeRepublicCsv.recognizes(lines(CSV).getFirst())).isTrue();
        assertThat(TradeRepublicCsv.recognizes(List.of("Data", "Descrizione", "Importo"))).isFalse();
    }

    @Test
    void theAmountIsNetOfTaxesAndOnlyMoneyMovementsAreRead() {
        List<List<String>> all = lines(CSV);

        TradeRepublicCsv.Reading reading = TradeRepublicCsv.read(all.getFirst(), all.subList(1, all.size()));

        assertThat(reading.errors()).isEmpty();
        assertThat(reading.skipped()).isEqualTo(1); // l'operazione sui titoli
        assertThat(reading.rows()).extracting(TradeRepublicCsv.Row::amount).containsExactly(
                new BigDecimal("7.40"),      // 10,00 di interessi meno 2,60 di tasse
                new BigDecimal("-150.00"),
                new BigDecimal("-34.20"),    // il bollo: tutto nella colonna delle tasse
                new BigDecimal("148.00"),    // 200,00 meno 52,00
                new BigDecimal("-148.00"));  // l'annullamento
    }

    @Test
    void buyingSecuritiesMovesMoneyToo() {
        // Le righe TRADING hanno una categoria diversa da CASH, ma un importo: vanno lette (la prima versione le
        // saltava). Il netto comprende commissione e tassa.
        List<List<String>> all = lines(LONG_CSV);

        TradeRepublicCsv.Reading reading = TradeRepublicCsv.read(all.getFirst(), all.subList(1, all.size()));

        TradeRepublicCsv.Row buy = reading.rows().getFirst();
        assertThat(buy.amount()).isEqualByComparingTo("-1003.00");
        assertThat(buy.counterparty()).isEqualTo("Beta SpA");
        assertThat(buy.transactionId()).isEqualTo("id-10");
        assertThat(buy.cells()).containsEntry("symbol", "IT0000000002"); // la riga intera, per il raw_json
        assertThat(reading.rows().get(2).counterparty()).isNull(); // bonifico senza counterparty_name né name
        assertThat(reading.skipped()).isEqualTo(1);               // solo l'operazione sui titoli senza denaro
    }

    @Test
    void theCounterpartyIsWhoPaysOrIsPaid() {
        List<List<String>> all = lines(CSV);

        List<TradeRepublicCsv.Row> rows = TradeRepublicCsv.read(all.getFirst(), all.subList(1, all.size())).rows();

        assertThat(rows.get(0).counterparty()).isNull();                // interessi: nessuno
        assertThat(rows.get(1).counterparty()).isEqualTo("Mario Rossi"); // bonifico: counterparty_name
        assertThat(rows.get(3).counterparty()).isEqualTo("Alfa SpA");        // dividendo: la società (name)
        assertThat(rows.get(1).description()).isEqualTo("Outgoing transfer for Mario Rossi (IT00X0000000000000000000001)");
        assertThat(rows.get(1).date()).isEqualTo(LocalDate.of(2026, 7, 7));
        assertThat(rows.get(1).line()).isEqualTo(3);
    }

    @Test
    void wrongCellsAreReportedWithTheirLine() {
        String broken = CSV.replace("\"10.000000\"", "\"dieci\"").replace("\"2026-07-07\"", "\"7 luglio\"");
        List<List<String>> all = lines(broken);

        TradeRepublicCsv.Reading reading = TradeRepublicCsv.read(all.getFirst(), all.subList(1, all.size()));

        assertThat(reading.errors()).containsExactly("Riga 2: amount \"dieci\" non è un numero.",
                "Riga 3: date \"7 luglio\" non è una data.");
    }
}
