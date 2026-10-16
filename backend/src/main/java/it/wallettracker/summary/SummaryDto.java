package it.wallettracker.summary;

import java.time.YearMonth;
import java.util.List;

/** La risposta di {@code GET /api/summary/monthly}: il periodo chiesto e i riepiloghi, uno per valuta. */
public record SummaryDto(YearMonth from, YearMonth to, List<CurrencySummary> currencies) {
}
