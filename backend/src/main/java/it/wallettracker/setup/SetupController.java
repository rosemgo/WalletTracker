package it.wallettracker.setup;

import it.wallettracker.setup.SetupService.SetupStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Le API della configurazione guidata (pagina Impostazioni). */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private final SetupService setupService;

    public SetupController(SetupService setupService) {
        this.setupService = setupService;
    }

    /** {@code GET /api/setup}: Enable Banking è configurato? Da dove arrivano le credenziali? */
    @GetMapping
    public SetupStatus status() {
        return setupService.status();
    }

    /**
     * {@code PUT /api/setup/enable-banking} con {@code {"applicationId": "...", "privateKeyPem": "-----BEGIN..."}}:
     * verifica e salva le credenziali. È un PUT perché sostituisce le credenziali (ce n'è una sola copia).
     */
    @PutMapping("/enable-banking")
    public SetupStatus saveEnableBanking(@Valid @RequestBody EnableBankingSetup request) {
        return setupService.saveEnableBanking(request.applicationId(), request.privateKeyPem());
    }

    /** Il corpo della richiesta. Una chiave RSA in PEM è lunga circa 1700 caratteri: 10000 bastano e avanzano. */
    public record EnableBankingSetup(
            @NotBlank @Size(max = 100) String applicationId,
            @NotBlank @Size(max = 10_000) String privateKeyPem) {
    }
}
