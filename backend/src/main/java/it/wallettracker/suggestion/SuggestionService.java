package it.wallettracker.suggestion;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import it.wallettracker.category.Category;
import it.wallettracker.category.CategoryService;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.subscription.MerchantKey;
import it.wallettracker.suggestion.HistorySuggester.Candidate;
import it.wallettracker.suggestion.HistorySuggester.Dismissal;
import it.wallettracker.suggestion.HistorySuggester.Example;
import it.wallettracker.suggestion.HistorySuggester.Suggestion;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.TransactionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * I suggerimenti di categoria dalla tua storia: li calcola {@link HistorySuggester}, qui leggiamo i dati e
 * applichiamo le tue decisioni ("accetta" e "no").
 */
@Service
public class SuggestionService {

    /**
     * A quali movimenti si suggerisce una categoria: spese ed entrate. Trasferimenti, investimenti e
     * movimenti ignorati non ne hanno bisogno; quelli "da verificare" prima devono avere un tipo.
     */
    static final Set<TransactionType> SUGGESTED_TYPES = EnumSet.of(TransactionType.EXPENSE, TransactionType.INCOME);

    private final BankTransactionRepository transactionRepository;
    private final SuggestionDismissalRepository dismissalRepository;
    private final CategoryService categoryService;
    private final ClassificationService classificationService;

    public SuggestionService(BankTransactionRepository transactionRepository,
            SuggestionDismissalRepository dismissalRepository, CategoryService categoryService,
            ClassificationService classificationService) {
        this.transactionRepository = transactionRepository;
        this.dismissalRepository = dismissalRepository;
        this.categoryService = categoryService;
        this.classificationService = classificationService;
    }

    /** I suggerimenti di adesso. Tre query, qualunque sia il numero di movimenti. */
    @Transactional(readOnly = true)
    public List<Suggestion> suggestions() {
        List<Example> examples = transactionRepository.findAllWithManualCategory().stream()
                .map(t -> new Example(merchantKeyOf(t), t.getManualCategory().getId(), t.getManualCategory().getName()))
                .filter(e -> e.merchantKey() != null) // un movimento senza testo non ha un esercente
                .toList();
        List<Candidate> candidates = transactionRepository.findUncategorized(SUGGESTED_TYPES).stream()
                .map(t -> new Candidate(merchantKeyOf(t), textOf(t), t.getBookingDate()))
                .filter(c -> c.merchantKey() != null)
                .toList();
        // getCategory().getId() non carica la categoria: l'id Hibernate lo conosce già (è nella colonna category_id).
        Set<Dismissal> dismissals = dismissalRepository.findAll().stream()
                .map(d -> new Dismissal(d.getMerchantKey(), d.getCategory().getId()))
                .collect(Collectors.toSet());
        return HistorySuggester.suggest(examples, candidates, dismissals);
    }

    /**
     * "Accetta": la categoria diventa la scelta a mano di tutti i movimenti senza categoria di quell'esercente.
     * Come una correzione fatta movimento per movimento, ma in un clic.
     *
     * @return quanti movimenti sono stati aggiornati
     */
    @Transactional
    public int accept(String merchantKey, Long categoryId) {
        Category category = categoryService.require(categoryId);
        List<BankTransaction> toUpdate = transactionRepository.findUncategorized(SUGGESTED_TYPES).stream()
                .filter(t -> merchantKey.equals(merchantKeyOf(t)))
                .toList();
        toUpdate.forEach(t -> t.setManualCategory(category));
        if (!toUpdate.isEmpty()) {
            classificationService.classifyAll(); // la categoria scelta a mano diventa la categoria del movimento
        }
        return toUpdate.size();
    }

    /** "No": per questo esercente, questa categoria non va più suggerita. Ripeterlo non cambia niente. */
    @Transactional
    public void dismiss(String merchantKey, Long categoryId) {
        Category category = categoryService.require(categoryId);
        if (!dismissalRepository.existsByMerchantKeyAndCategory(merchantKey, category)) {
            dismissalRepository.save(new SuggestionDismissal(merchantKey, category));
        }
    }

    private static String merchantKeyOf(BankTransaction transaction) {
        return MerchantKey.of(transaction.getCounterparty(), transaction.getDescription());
    }

    /** Il testo da cui nasce la chiave (controparte, altrimenti causale): serve per il nome da mostrare. */
    private static String textOf(BankTransaction transaction) {
        String counterparty = transaction.getCounterparty();
        return counterparty != null && !counterparty.isBlank() ? counterparty
                : Objects.requireNonNullElse(transaction.getDescription(), "");
    }
}
