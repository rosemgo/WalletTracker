package it.wallettracker.investment;

import java.time.LocalDate;

import it.wallettracker.investment.InvestmentDtos.Overview;
import it.wallettracker.investment.InvestmentDtos.Period;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** L'API degli investimenti (vedi AccountController per le annotazioni). */
@RestController
public class InvestmentController {

    private final InvestmentService investmentService;

    public InvestmentController(InvestmentService investmentService) {
        this.investmentService = investmentService;
    }

    /**
     * {@code GET /api/investments?period=YEAR&currency=EUR}: il versato, le rendite, i mesi per il grafico. Senza
     * {@code period}, gli ultimi 12 mesi; senza {@code currency}, quella con più movimenti di investimento.
     */
    @GetMapping("/api/investments")
    public Overview overview(@RequestParam(defaultValue = "YEAR") Period period,
            @RequestParam(required = false) String currency) {
        return investmentService.overview(period, currency, LocalDate.now());
    }
}
