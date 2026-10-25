package it.wallettracker.overview;

import java.time.LocalDate;
import java.time.YearMonth;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** L'API del Riepilogo (vedi AccountController per le annotazioni). */
@RestController
@RequestMapping("/api/overview")
public class OverviewController {

    private final OverviewService overviewService;

    public OverviewController(OverviewService overviewService) {
        this.overviewService = overviewService;
    }

    /**
     * {@code GET /api/overview?month=2026-10&currency=EUR}: il Riepilogo di un mese. Senza {@code month}, il mese in
     * corso; senza {@code currency}, la valuta con più spese.
     */
    @GetMapping
    public OverviewDto overview(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(required = false) String currency) {
        LocalDate today = LocalDate.now();
        return overviewService.overview(month != null ? month : YearMonth.from(today), currency, today);
    }
}
