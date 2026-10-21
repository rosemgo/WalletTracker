package it.wallettracker.ai;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AiCategoryAnswerRepository extends JpaRepository<AiCategoryAnswer, Long> {

    /** Le risposte con una categoria, insieme alla categoria (JOIN FETCH, docs/24): una query sola. */
    @Query("SELECT a FROM AiCategoryAnswer a JOIN FETCH a.category")
    List<AiCategoryAnswer> findAllWithCategory();

    /** Gli esercenti già chiesti, anche quelli con "non so": non vanno richiesti. */
    @Query("SELECT a.merchantKey FROM AiCategoryAnswer a")
    List<String> findAllMerchantKeys();
}
