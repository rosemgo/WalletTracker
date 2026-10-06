package it.wallettracker.classification;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Accesso alla tabella {@code classification_rule}. */
public interface ClassificationRuleRepository extends JpaRepository<ClassificationRule, Long> {

    /** Le regole attive, nell'ordine in cui vanno provate. */
    List<ClassificationRule> findByEnabledTrueOrderByPriorityAscIdAsc();
}
