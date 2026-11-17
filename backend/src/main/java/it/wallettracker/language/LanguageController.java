package it.wallettracker.language;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import it.wallettracker.message.ApiException;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La lingua dell'app.
 *
 * <ul>
 *   <li>{@code GET /api/language}: la lingua scelta, quelle disponibili e, finché nessuno ha scelto, quella
 *       dell'installazione. Risponde anche senza PIN ({@code SecurityConfig}): la dashboard la chiede prima di
 *       disegnare qualsiasi cosa, anche la pagina del PIN, e non dice niente di riservato;</li>
 *   <li>{@code PUT /api/language}: la cambia. Come ogni altra modifica, vuole il PIN.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/language")
public class LanguageController {

    private final LanguageService languages;
    private final LanguageSwitch languageSwitch;
    private final String installerLanguage;

    /**
     * @param installerLanguage la lingua scelta nello script di installazione ({@code WALLETTRACKER_LANGUAGE} nel
     *                          .env), o vuota: vedi {@link LanguageDto#suggested()}
     */
    public LanguageController(LanguageService languages, LanguageSwitch languageSwitch,
            @Value("${wallettracker.installer-language:}") String installerLanguage) {
        this.languages = languages;
        this.languageSwitch = languageSwitch;
        this.installerLanguage = installerLanguage;
    }

    /**
     * @param language  il codice della lingua scelta ("it", "en"), o null se non è ancora stata scelta
     * @param supported i codici delle lingue che questo backend conosce
     * @param suggested solo finché {@code language} è null (un'installazione nuova, prima della prima schermata): la
     *                  lingua scelta nello script di installazione, o null se non c'è. La dashboard si apre in questa,
     *                  invece che in quella del browser: chi ha installato in inglese trova la prima schermata in
     *                  inglese. Resta una proposta: diventa la lingua dell'installazione solo quando si entra
     */
    public record LanguageDto(String language, List<String> supported, String suggested) {
    }

    public record LanguageChange(@NotBlank String language) {
    }

    @GetMapping
    public LanguageDto language() {
        return dto();
    }

    @PutMapping
    public LanguageDto choose(@RequestBody LanguageChange change) {
        // messaggio: Lingua non disponibile: {0}
        Language language = Language.of(change.language())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "language.unsupported", change.language()));
        languageSwitch.choose(language);
        return dto();
    }

    private LanguageDto dto() {
        String chosen = languages.current().map(Language::code).orElse(null);
        String suggested = chosen != null ? null : Language.of(installerLanguage.trim().toLowerCase(Locale.ROOT)).map(Language::code).orElse(null);
        return new LanguageDto(chosen, Arrays.stream(Language.values()).map(Language::code).toList(), suggested);
    }
}
