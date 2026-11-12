package it.wallettracker.explore;

import java.time.LocalDate;
import java.time.YearMonth;

import it.wallettracker.explore.ExploreDtos.By;
import it.wallettracker.explore.ExploreDtos.CategoryOverview;
import it.wallettracker.explore.ExploreDtos.Groups;
import it.wallettracker.explore.ExploreDtos.Metric;
import it.wallettracker.explore.ExploreDtos.Period;
import it.wallettracker.explore.ExploreDtos.Series;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Le API di Analisi (vedi AccountController per le annotazioni). */
@RestController
public class ExploreController {

    private final ExploreService exploreService;

    public ExploreController(ExploreService exploreService) {
        this.exploreService = exploreService;
    }

    /**
     * {@code GET /api/explore/series?metric=EXPENSES&period=YEAR&end=2026-10-25&categoryId=7}: le barre del grafico.
     * Senza {@code end}, oggi; senza {@code categoryId}, tutte le categorie.
     */
    @GetMapping("/api/explore/series")
    public Series series(
            @RequestParam Metric metric,
            @RequestParam Period period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String currency) {
        LocalDate today = LocalDate.now();
        return exploreService.series(metric, period, end != null ? end : today, categoryId, currency, today);
    }

    /**
     * {@code GET /api/explore/groups?metric=EXPENSES&from=2026-10-01&to=2026-10-25&by=CATEGORY}: i movimenti di una
     * barra raggruppati per categoria, esercente ({@code MERCHANT}) o conto ({@code ACCOUNT}).
     */
    @GetMapping("/api/explore/groups")
    public Groups groups(
            @RequestParam Metric metric,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam By by,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String currency) {
        return exploreService.groups(metric, from, to, by, categoryId, currency, LocalDate.now());
    }

    /** {@code GET /api/categories/7/overview?month=2026-10}: la pagina di una categoria; senza mese, quello in corso. */
    @GetMapping("/api/categories/{id}/overview")
    public CategoryOverview category(@PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        LocalDate today = LocalDate.now();
        return exploreService.category(id, month != null ? month : YearMonth.from(today), today);
    }
}
