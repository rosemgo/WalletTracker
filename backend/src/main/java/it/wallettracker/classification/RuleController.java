package it.wallettracker.classification;

import java.util.List;

import it.wallettracker.classification.RuleService.RulePreview;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Le API delle regole di classificazione.
 *
 * <p>È il classico insieme "CRUD" (Create, Read, Update, Delete) di una risorsa REST:
 * <ul>
 *   <li>{@code GET    /api/rules}           elenco</li>
 *   <li>{@code POST   /api/rules}           crea (201 Created)</li>
 *   <li>{@code PUT    /api/rules/{id}}      modifica</li>
 *   <li>{@code DELETE /api/rules/{id}}      cancella (204 No Content)</li>
 * </ul>
 * più {@code POST /api/rules/preview}, che prova una regola senza salvarla.
 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleService ruleService;

    public RuleController(RuleService ruleService) {
        this.ruleService = ruleService;
    }

    @GetMapping
    public List<RuleDto> list() {
        return ruleService.rules();
    }

    /** {@code @ResponseStatus(CREATED)}: risponde 201 invece di 200, il codice HTTP per "risorsa creata". */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RuleDto create(@Valid @RequestBody RuleRequest request) {
        return ruleService.create(request);
    }

    @PutMapping("/{id}")
    public RuleDto update(@PathVariable Long id, @Valid @RequestBody RuleRequest request) {
        return ruleService.update(id, request);
    }

    /** 204 No Content: tutto bene, e non c'è niente da restituire. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        ruleService.delete(id);
    }

    @PostMapping("/preview")
    public RulePreview preview(@Valid @RequestBody RuleRequest request) {
        return ruleService.preview(request);
    }
}
