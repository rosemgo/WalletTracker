package it.wallettracker.language;

import java.util.Arrays;
import java.util.List;

import it.wallettracker.message.ApiException;
import jakarta.validation.constraints.NotBlank;
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
 *   <li>{@code GET /api/language}: la lingua scelta e quelle disponibili. Risponde anche senza PIN ({@code
 *       SecurityConfig}): la dashboard la chiede prima di disegnare qualsiasi cosa, anche la pagina del PIN, e non
 *       dice niente di riservato;</li>
 *   <li>{@code PUT /api/language}: la cambia. Come ogni altra modifica, vuole il PIN.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/language")
public class LanguageController {

    private final LanguageService languages;
    private final LanguageSwitch languageSwitch;

    public LanguageController(LanguageService languages, LanguageSwitch languageSwitch) {
        this.languages = languages;
        this.languageSwitch = languageSwitch;
    }

    /**
     * @param language  il codice della lingua scelta ("it", "en"), o null se non è ancora stata scelta
     * @param supported i codici delle lingue che questo backend conosce
     */
    public record LanguageDto(String language, List<String> supported) {
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
        return new LanguageDto(languages.current().map(Language::code).orElse(null),
                Arrays.stream(Language.values()).map(Language::code).toList());
    }
}
