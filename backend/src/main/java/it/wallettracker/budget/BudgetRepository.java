package it.wallettracker.budget;

import java.util.List;

import it.wallettracker.category.Category;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/** Accesso alla tabella {@code budget}. */
public interface BudgetRepository extends JpaRepository<Budget, Long> {

    /**
     * Tutti i budget, con la loro categoria già caricata: la pagina Budget ne mostra il nome.
     * {@code @Override}: è il {@code findAll()} di JpaRepository, a cui aggiungiamo l'{@code @EntityGraph}.
     */
    @Override
    @EntityGraph(attributePaths = "category")
    List<Budget> findAll();

    /** Esiste già un budget per questa categoria e valuta? (per dare un messaggio chiaro prima del vincolo UNIQUE) */
    boolean existsByCategoryAndCurrency(Category category, String currency);

    /** C'è un budget per questa categoria (in qualsiasi valuta)? Una categoria con un budget non si elimina. */
    boolean existsByCategory(Category category);
}
