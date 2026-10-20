package it.wallettracker.suggestion;

import java.util.List;

import it.wallettracker.suggestion.HistorySuggester.Suggestion;
import it.wallettracker.suggestion.HistorySuggester.UsedCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Le API dei suggerimenti di categoria:
 * <ul>
 *   <li>{@code GET  /api/category-suggestions}           i suggerimenti di adesso;</li>
 *   <li>{@code POST /api/category-suggestions/accept}    "accetta": categoria a tutti i movimenti dell'esercente;</li>
 *   <li>{@code POST /api/category-suggestions/dismissed} "no, non suggerirlo più" (204);</li>
 *   <li>{@code GET  /api/category-suggestions/for-transaction/{id}} le categorie già usate per l'esercente
 *       di quel movimento, per il suo menu della categoria.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/category-suggestions")
public class SuggestionController {

    private final SuggestionService suggestionService;

    public SuggestionController(SuggestionService suggestionService) {
        this.suggestionService = suggestionService;
    }

    @GetMapping
    public List<Suggestion> list() {
        return suggestionService.suggestions();
    }

    @GetMapping("/for-transaction/{id}")
    public List<UsedCategory> usedCategories(@PathVariable Long id) {
        return suggestionService.usedCategoriesFor(id);
    }

    @PostMapping("/accept")
    public Accepted accept(@Valid @RequestBody Decision decision) {
        return new Accepted(suggestionService.accept(decision.merchantKey(), decision.categoryId()));
    }

    @PostMapping("/dismissed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismiss(@Valid @RequestBody Decision decision) {
        suggestionService.dismiss(decision.merchantKey(), decision.categoryId());
    }

    /** Il corpo di "accetta" e "no": l'esercente e la categoria del suggerimento. */
    public record Decision(@NotBlank @Size(max = 100) String merchantKey, @NotNull Long categoryId) {
    }

    /** La risposta di "accetta": quanti movimenti hanno ora la categoria. */
    public record Accepted(int updated) {
    }
}
