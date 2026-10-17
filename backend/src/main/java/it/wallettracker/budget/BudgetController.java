package it.wallettracker.budget;

import java.time.YearMonth;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Le API dei budget: lo stesso schema CRUD delle regole (vedi RuleController).
 * <ul>
 *   <li>{@code GET    /api/budgets?month=2026-10}  elenco, con l'andamento del mese (predefinito: quello in corso)</li>
 *   <li>{@code POST   /api/budgets}                crea (201)</li>
 *   <li>{@code PUT    /api/budgets/{id}}           modifica</li>
 *   <li>{@code DELETE /api/budgets/{id}}           cancella (204)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/budgets")
public class BudgetController {

    private final BudgetService budgetService;

    public BudgetController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    @GetMapping
    public List<BudgetDto> list(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return budgetService.budgets(month != null ? month : YearMonth.now());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BudgetDto create(@Valid @RequestBody BudgetRequest request) {
        return budgetService.create(request);
    }

    @PutMapping("/{id}")
    public BudgetDto update(@PathVariable Long id, @Valid @RequestBody BudgetRequest request) {
        return budgetService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        budgetService.delete(id);
    }
}
