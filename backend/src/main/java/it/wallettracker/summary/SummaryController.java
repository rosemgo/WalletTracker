package it.wallettracker.summary;

import java.time.YearMonth;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Le API del riepilogo (vedi AccountController per le annotazioni). */
@RestController
@RequestMapping("/api/summary")
public class SummaryController {

    private final SummaryService summaryService;

    public SummaryController(SummaryService summaryService) {
        this.summaryService = summaryService;
    }

    /**
     * {@code GET /api/summary/monthly?from=2025-11&to=2026-10}: entrate, spese e risparmio mese per mese.
     *
     * <p>Senza parametri: gli ultimi 12 mesi, il mese in corso compreso. {@code @DateTimeFormat} dice a
     * Spring come leggere il testo "2026-10"; un valore sbagliato (es. "2026-13") riceve 400 Bad Request.
     */
    @GetMapping("/monthly")
    public SummaryDto monthly(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth to) {
        YearMonth end = to != null ? to : YearMonth.now();
        YearMonth start = from != null ? from : end.minusMonths(11);
        return summaryService.monthly(start, end);
    }
}
