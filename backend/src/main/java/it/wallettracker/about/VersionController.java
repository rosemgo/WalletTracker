package it.wallettracker.about;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/version}: la versione dell'app, per la pagina Impostazioni (docs/50, parte B).
 *
 * <p>Arriva dalla variabile d'ambiente {@code WALLETTRACKER_VERSION}, che il Dockerfile scrive nell'immagine quando
 * la CI pubblica una versione (es. "1.0.0"). Costruendo dal codice, o con ./mvnw, vale "sviluppo".
 */
@RestController
public class VersionController {

    /** @param version es. "1.0.0", oppure "sviluppo" */
    public record Version(String version) {
    }

    private final Version version;
    private final UpdateCheckService updates;

    public VersionController(@Value("${wallettracker.version}") String version, UpdateCheckService updates) {
        this.version = new Version(version);
        this.updates = updates;
    }

    @GetMapping("/api/version")
    public Version version() {
        return version;
    }

    /** C'è una versione nuova? (docs/50, parte J): l'ultima risposta di GitHub, senza chiedere niente adesso. */
    @GetMapping("/api/version/update")
    public UpdateCheckService.UpdateStatus update() {
        return updates.status();
    }
}
