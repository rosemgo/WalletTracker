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
 * <p>Movimenti, regole e budget salvano l'<b>id</b> della categoria (migrazione V13): il nome sta solo nella
 * tabella {@code category}. Il database garantisce che l'id esista (chiave esterna).
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
        Map<Long, Long> uses = transactionRepository.countUsesByCategory().stream()
                .collect(Collectors.toMap(CategoryUsage::getCategoryId, CategoryUsage::getUses));
        Collator collator = Collator.getInstance(Locale.ITALIAN);
        return categoryRepository.findAll().stream()
                .sorted(Comparator.comparing(Category::getKind)
                        .thenComparing(Category::getPosition)
                        .thenComparing(Category::getName, collator))
                .map(c -> new CategoryDto(c.getId(), c.getName(), c.getKind(), c.isBuiltin(),
                        uses.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    @Transactional
    public CategoryDto create(CategoryRequest request) {
        String name = request.name().trim();
        checkNameFree(name, null);
        Category category = categoryRepository.save(new Category(name, request.kind()));
        return new CategoryDto(category.getId(), category.getName(), category.getKind(), false, 0);
    }

    /**
     * Rinomina (o cambia gruppo). Movimenti, regole e budget tengono l'id della categoria, che non cambia:
     * il nuovo nome compare ovunque senza toccare nessun'altra riga.
     */
    @Transactional
    public CategoryDto update(Long id, CategoryRequest request) {
        Category category = find(id);
        String name = request.name().trim();
        checkNameFree(name, category);
        category.update(name, request.kind());
        return categories().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * Elimina una categoria: movimenti e regole restano senza categoria. Se c'è un budget, no: il budget
     * perderebbe senso, ed è meglio che l'utente decida prima che cosa farne.
     */
    @Transactional
    public void delete(Long id) {
        Category category = find(id);
        if (budgetRepository.existsByCategory(category)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "C'è un budget per \"" + category.getName() + "\": eliminalo prima dalla pagina Budget.");
        }
        categoryRepository.delete(category);
    }

    /**
     * La categoria scelta dall'utente (per un movimento, una regola, un budget), dal suo id.
     *
     * <p>Se l'id non è nell'elenco → 400 (la richiesta indica una categoria che non c'è: ad esempio è stata
     * eliminata in un'altra finestra). Senza questo controllo arriverebbe al database, che la rifiuterebbe
     * con la chiave esterna: un errore 500 incomprensibile. Il controllo qui dà il messaggio chiaro, il
     * vincolo nel database resta l'ultima difesa.
     *
     * @return null se {@code id} è null (nessuna categoria)
     */
    @Transactional(readOnly = true)
    public Category require(Long id) {
        if (id == null) {
            return null;
        }
        return categoryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La categoria " + id + " non esiste: forse è stata eliminata. Ricarica la pagina."));
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
