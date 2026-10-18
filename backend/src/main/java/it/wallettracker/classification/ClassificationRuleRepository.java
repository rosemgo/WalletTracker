package it.wallettracker.classification;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/** Accesso alla tabella {@code classification_rule}. */
public interface ClassificationRuleRepository extends JpaRepository<ClassificationRule, Long> {

    /** Le regole attive, nell'ordine in cui vanno provate. */
    List<ClassificationRule> findByEnabledTrueOrderByPriorityAscIdAsc();

    /**
     * Tutte le regole, attive e non, nell'ordine in cui vengono provate (per la dashboard).
     *
     * <p>{@code @EntityGraph}: carica anche la categoria di ogni regola, nella stessa query (un JOIN), perché
     * la dashboard ne mostra il nome. È l'alternativa a un {@code JOIN FETCH} scritto a mano, per i metodi
     * di cui Spring Data scrive la query da solo.
     * L'annotazione @EntityGraph(attributePaths = "category") è il fulcro di questo metodo. Fa esattamente lo stesso lavoro del LEFT JOIN FETCH, ma senza a scrivere la query a mano.
     */
    @EntityGraph(attributePaths = "category")
    List<ClassificationRule> findAllByOrderByPriorityAscIdAsc();
}
