package it.wallettracker.csvimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import it.wallettracker.csvimport.CompletionService.Candidate;
import it.wallettracker.csvimport.CompletionService.Matching;
import org.junit.jupiter.api.Test;

/** L'abbinamento tra le righe del file e i movimenti: una funzione pura. */
class CompletionMatchingTest {

    static TradeRepublicCsv.Row row(int line, String date, String amount) {
        return new TradeRepublicCsv.Row(line, LocalDate.parse(date), new BigDecimal(amount), "EUR", "TYPE",
                "riga " + line, null, "id-" + line, Map.of());
    }

    static Candidate movement(long id, String date, String amount) {
        return new Candidate(id, LocalDate.parse(date), new BigDecimal(amount), "EUR");
    }

    @Test
    void sameAmountAndNearbyDate() {
        // Un bonifico delle 23:40 UTC del 18 settembre: nel file è del 19, per la banca del 18.
        Matching m = CompletionService.match(List.of(row(2, "2026-09-19", "-50.00")),
                List.of(movement(1, "2026-09-18", "-50.00")));

        assertThat(m.pairs()).containsValue(1L);
        assertThat(m.unmatchedRows()).isEmpty();
    }

    @Test
    void theSignTellsACancellationFromAPayment() {
        TradeRepublicCsv.Row paid = row(2, "2026-07-20", "148.00");
        TradeRepublicCsv.Row cancelled = row(3, "2026-07-20", "-148.00");

        Matching m = CompletionService.match(List.of(paid, cancelled),
                List.of(movement(10, "2026-07-20", "-148.00"), movement(11, "2026-07-20", "148.00")));

        assertThat(m.pairs()).containsEntry(paid, 11L).containsEntry(cancelled, 10L);
    }

    @Test
    void theClosestDateWinsAndEachMovementIsUsedOnce() {
        // Due bonifici uguali a una settimana di distanza.
        TradeRepublicCsv.Row first = row(2, "2026-10-01", "-100.00");
        TradeRepublicCsv.Row second = row(3, "2026-10-08", "-100.00");

        Matching m = CompletionService.match(List.of(second, first),
                List.of(movement(20, "2026-10-08", "-100.00"), movement(21, "2026-10-01", "-100.00")));

        assertThat(m.pairs()).containsEntry(first, 21L).containsEntry(second, 20L);
    }

    @Test
    void tooFarOrDifferentAmountsDoNotMatch() {
        Matching m = CompletionService.match(
                List.of(row(2, "2026-10-01", "-40.00"), row(3, "2026-10-01", "-12.00")),
                List.of(movement(30, "2026-10-06", "-40.00"), movement(31, "2026-10-01", "-12.01")));

        assertThat(m.pairs()).isEmpty();
        assertThat(m.unmatchedRows()).hasSize(2);
        assertThat(m.unusedIds()).containsExactly(30L, 31L);
    }

    @Test
    void tradeRepublicIsRecognizedHoweverItsNameIsWritten() {
        assertThat(CompletionService.isTradeRepublic("Trade Republic")).isTrue();
        assertThat(CompletionService.isTradeRepublic("Trade Republic Bank GmbH")).isTrue();
        assertThat(CompletionService.isTradeRepublic("TradeRepublic")).isTrue();
        assertThat(CompletionService.isTradeRepublic("ING")).isFalse();
        assertThat(CompletionService.isTradeRepublic(null)).isFalse();
    }
}
