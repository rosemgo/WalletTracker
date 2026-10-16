package it.wallettracker.classification;

import java.text.Collator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import it.wallettracker.transaction.BankTransactionRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/categories}: le categorie già usate, nelle regole e nei movimenti.
 *
 * <p>Le categorie sono testo libero: non c'è una tabella da gestire. Questo elenco serve alla dashboard
 * per <b>suggerirle</b> mentre scrivi, così non nascono doppioni come "Spesa" e "spesa".
 */
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final ClassificationRuleRepository ruleRepository;
    private final BankTransactionRepository transactionRepository;

    public CategoryController(ClassificationRuleRepository ruleRepository,
            BankTransactionRepository transactionRepository) {
        this.ruleRepository = ruleRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Le categorie in ordine alfabetico, senza doppioni. Il {@link Collator} confronta le parole come un
     * dizionario italiano: senza, "Viaggi" verrebbe prima di "auto", perché nei codici dei caratteri le
     * maiuscole vengono tutte prima delle minuscole.
     */
    @GetMapping
    @Transactional(readOnly = true)
    public List<String> list() {
        TreeSet<String> categories = new TreeSet<>(Collator.getInstance(Locale.ITALIAN));
        categories.addAll(ruleRepository.findDistinctCategories());
        categories.addAll(transactionRepository.findDistinctCategories());
        return List.copyOf(categories);
    }
}
