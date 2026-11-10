package it.wallettracker.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** I record delle API delle categorie. */
public final class CategoryDtos {

    private CategoryDtos() {
    }

    /**
     * Una categoria come la vede la dashboard.
     *
     * @param role il ruolo (V37), null per quasi tutte: la dashboard dice a che cosa serve
     * @param uses quanti movimenti la usano
     */
    public record CategoryDto(Long id, String name, CategoryKind kind, boolean builtin, CategoryRole role, long uses) {
    }

    /** Il corpo di POST e PUT. */
    public record CategoryRequest(@NotBlank @Size(max = 100) String name, @NotNull CategoryKind kind) {
    }
}
