package it.wallettracker.suggestion;

import it.wallettracker.category.Category;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SuggestionDismissalRepository extends JpaRepository<SuggestionDismissal, Long> {

    boolean existsByMerchantKeyAndCategory(String merchantKey, Category category);
}
