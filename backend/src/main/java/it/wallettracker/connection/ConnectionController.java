package it.wallettracker.connection;

import java.util.List;

import it.wallettracker.bank.enablebanking.EnableBankingApi.Aspsp;
import it.wallettracker.connection.BankLinkService.LinkResult;
import it.wallettracker.web.PresentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Le API per collegare una banca dalla dashboard (vedi BankLinkService per il flusso completo). */
@RestController
@RequestMapping("/api")
public class ConnectionController {

    private final BankLinkService bankLinkService;
    private final PresentUser presentUser;

    public ConnectionController(BankLinkService bankLinkService, PresentUser presentUser) {
        this.bankLinkService = bankLinkService;
        this.presentUser = presentUser;
    }

    /** {@code GET /api/banks?country=IT}: le banche di un paese. */
    @GetMapping("/banks")
    public List<BankDto> banks(@RequestParam(defaultValue = "IT") String country) {
        return bankLinkService.banks(country.toUpperCase()).stream().map(BankDto::from).toList();
    }

    /**
     * {@code POST /api/connections} con {@code {"bank": "ING", "country": "IT"}}: avvia il collegamento. L'indirizzo
     * della pagina da cui parte (l'header {@code Origin}, che il browser manda con ogni POST: schema, nome e porta)
     * sceglie l'indirizzo di ritorno ({@link it.wallettracker.bank.enablebanking.EnableBankingProperties#redirectUrlFor}):
     * dal telefono la banca rimanda al telefono, dal server all'indirizzo di Tailscale (docs/47, parti I e Q).
     */
    @PostMapping("/connections")
    public StartedLink start(@Valid @RequestBody StartLink request, HttpServletRequest httpRequest) {
        return new StartedLink(bankLinkService.start(request.bank(), request.country().toUpperCase(),
                httpRequest.getHeader("Origin")));
    }

    /**
     * {@code POST /api/connections/complete} con {@code {"code": "...", "state": "..."}}: completa il
     * collegamento. La chiama la pagina di ritorno della dashboard, con i parametri ricevuti dalla banca.
     *
     * <p>{@link HttpServletRequest} è la richiesta HTTP "grezza": Spring la passa al metodo se la chiediamo
     * come parametro. Ci serve per leggere il browser dell'utente (header User-Agent).
     */
    @PostMapping("/connections/complete")
    public LinkResult complete(@Valid @RequestBody CompleteLink request, HttpServletRequest httpRequest) {
        return bankLinkService.complete(request.code(), request.state(), presentUser.headersFor(httpRequest));
    }

    /** Una banca, come la vede la dashboard. */
    public record BankDto(String name, String country, Long maxConsentDays) {

        static BankDto from(Aspsp aspsp) {
            Long days = aspsp.maximumConsentValidity() != null ? aspsp.maximumConsentValidity() / 86_400 : null;
            return new BankDto(aspsp.name(), aspsp.country(), days);
        }
    }

    public record StartLink(@NotBlank String bank, @NotBlank String country) {
    }

    /** @param url la pagina di login della banca, dove mandare il browser */
    public record StartedLink(String url) {
    }

    public record CompleteLink(@NotBlank String code, @NotBlank String state) {
    }
}
