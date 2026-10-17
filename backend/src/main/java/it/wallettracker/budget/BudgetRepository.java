package it.wallettracker.budget;

import org.springframework.data.jpa.repository.JpaRepository;

/** Accesso alla tabella {@code budget}. */
public interface BudgetRepository extends JpaRepository<Budget, Long> {

    /** Esiste già un budget per questa categoria e valuta? (per dare un messaggio chiaro prima del vincolo UNIQUE) */
    boolean existsByCategoryAndCurrency(String category, String currency);

    /** C'è un budget per questa categoria (in qualsiasi valuta)? Una categoria con un budget non si elimina. */
    boolean existsByCategory(String category);
}
