package it.wallettracker.classification;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Accesso alla tabella {@code classification_rule}. */
public interface ClassificationRuleRepository extends JpaRepository<ClassificationRule, Long> {

    /** Le regole attive, nell'ordine in cui vanno provate. */
    List<ClassificationRule> findByEnabledTrueOrderByPriorityAscIdAsc();

    /** Le categorie usate nelle regole, senza doppioni. */
    @Query("SELECT DISTINCT r.category FROM ClassificationRule r WHERE r.category IS NOT NULL")
    List<String> findDistinctCategories();

    /** Tutte le regole, attive e non, nell'ordine in cui vengono provate (per la dashboard). */
    List<ClassificationRule> findAllByOrderByPriorityAscIdAsc();
}
