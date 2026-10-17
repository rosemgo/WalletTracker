package it.wallettracker.category;

import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import it.wallettracker.budget.BudgetRepository;
import it.wallettracker.category.CategoryDtos.CategoryDto;
import it.wallettracker.category.CategoryDtos.CategoryRequest;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.BankTransactionRepository.CategoryUsage;
import it.wallettracker.web.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * L'elenco delle categorie: lettura, aggiunta, modifica, eliminazione, e il controllo che una categoria
 * scelta (per un movimento, una regola, un budget) esista davvero.
 *
 * <p>Movimenti, regole e budget salvano il <b>nome</b> della categoria. Il database garantisce che quel nome
 * sia nell'elenco (chiave esterna) e lo aggiorna da solo quando la categoria viene rinominata
 * ({@code ON UPDATE CASCADE}, migrazione V12).
 */
@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final BankTransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;

    public CategoryService(CategoryRepository categoryRepository, BankTransactionRepository transactionRepository,
            BudgetRepository budgetRepository) {
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
    }

    /** Tutte le categorie: per gruppo, poi nell'ordine dell'elenco, poi in ordine alfabetico. */
    @Transactional(readOnly = true)
    public List<CategoryDto> categories() {
        Map<String, Long> uses = transactionRepository.countUsesByCategory().stream()
                .collect(Collectors.toMap(CategoryUsage::getCategory, CategoryUsage::getUses));
        Collator collator = Collator.getInstance(Locale.ITALIAN);
        return categoryRepository.findAll().stream()
                .sorted(Comparator.comparing(Category::getKind)
                        .thenComparing(Category::getPosition)
                        .thenComparing(Category::getName, collator))
                .map(c -> new CategoryDto(c.getId(), c.getName(), c.getKind(), c.isBuiltin(),
                        uses.getOrDefault(c.getName(), 0L)))
                .toList();
    }

    @Transactional
    public CategoryDto create(CategoryRequest request) {
        String name = request.name().trim();
        checkNameFree(name, null);
        Category category = categoryRepository.save(new Category(name, request.kind()));
        return new CategoryDto(category.getId(), category.getName(), category.getKind(), false, 0);
    }

    /** Rinomina (o cambia gruppo): movimenti, regole e budget seguono da soli, grazie al database. */
    @Transactional
    public CategoryDto update(Long id, CategoryRequest request) {
        Category category = find(id);
        String name = request.name().trim();
        checkNameFree(name, category);
        category.update(name, request.kind());
        categoryRepository.flush(); // manda subito l'UPDATE: il CASCADE nel database parte adesso
        return categories().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * Elimina una categoria: movimenti e regole restano senza categoria. Se c'è un budget, no: il budget
     * perderebbe senso, ed è meglio che l'utente decida prima che cosa farne.
     */
    @Transactional
    public void delete(Long id) {
        Category category = find(id);
        if (budgetRepository.existsByCategory(category.getName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "C'è un budget per \"" + category.getName() + "\": eliminalo prima dalla pagina Budget.");
        }
        categoryRepository.delete(category);
    }

    /**
     * Il nome "ufficiale" di una categoria scelta dall'utente: "spesa alimentare" diventa "Spesa alimentare".
     * Se la categoria non è nell'elenco → 400, con un messaggio che dice dove aggiungerla.
     *
     * @return null se {@code name} è null o vuoto (nessuna categoria)
     */
    @Transactional(readOnly = true)
    public String resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return categoryRepository.findByNameIgnoreCase(name.trim())
                .map(Category::getName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La categoria \"" + name.trim() + "\" non esiste: aggiungila dalla pagina Categorie."));
    }

    /** Il nome è libero? Maiuscole e minuscole non contano: "spesa" e "Spesa" sono lo stesso nome. */
    private void checkNameFree(String name, Category current) {
        categoryRepository.findByNameIgnoreCase(name)
                .filter(existing -> current == null || !existing.getId().equals(current.getId()))
                .ifPresent(existing -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Esiste già una categoria \"" + existing.getName() + "\".");
                });
    }

    private Category find(Long id) {
        return categoryRepository.findById(id).orElseThrow(() -> new NotFoundException("Categoria", id));
    }
}
