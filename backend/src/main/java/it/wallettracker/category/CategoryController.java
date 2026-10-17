package it.wallettracker.category;

import java.util.List;

import it.wallettracker.category.CategoryDtos.CategoryDto;
import it.wallettracker.category.CategoryDtos.CategoryRequest;
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
 * Le API delle categorie (lo stesso schema CRUD delle regole e dei budget).
 * <ul>
 *   <li>{@code GET    /api/categories}       l'elenco, con quanti movimenti usano ogni categoria</li>
 *   <li>{@code POST   /api/categories}       aggiunge (201)</li>
 *   <li>{@code PUT    /api/categories/{id}}  rinomina o cambia gruppo</li>
 *   <li>{@code DELETE /api/categories/{id}}  elimina (204)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public List<CategoryDto> list() {
        return categoryService.categories();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryDto create(@Valid @RequestBody CategoryRequest request) {
        return categoryService.create(request);
    }

    @PutMapping("/{id}")
    public CategoryDto update(@PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        return categoryService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        categoryService.delete(id);
    }
}
