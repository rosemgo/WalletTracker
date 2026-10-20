package it.wallettracker.suggestion;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import it.wallettracker.account.AccountRepository;
import it.wallettracker.category.Category;
import it.wallettracker.category.CategoryService;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.subscription.MerchantKey;
import it.wallettracker.suggestion.HistorySuggester.Candidate;
import it.wallettracker.suggestion.HistorySuggester.Dismissal;
import it.wallettracker.suggestion.HistorySuggester.Example;
import it.wallettracker.suggestion.HistorySuggester.Suggestion;
import it.wallettracker.suggestion.HistorySuggester.UsedCategories;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.TransactionType;
import it.wallettracker.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * I suggerimenti di categoria dalla tua storia: li calcola {@link HistorySuggester}, qui leggiamo i dati e
 * applichiamo le tue decisioni ("accetta" e "no").
 *
 * <p>L'esercente di un movimento è la sua {@link MerchantKey}. Serve sapere chi sono i titolari dei conti:
 * se la controparte è il titolare, l'esercente è nella causale.
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
    private final AccountRepository accountRepository;
    private final CategoryService categoryService;
    private final ClassificationService classificationService;

    public SuggestionService(BankTransactionRepository transactionRepository,
            SuggestionDismissalRepository dismissalRepository, AccountRepository accountRepository,
            CategoryService categoryService, ClassificationService classificationService) {
        this.transactionRepository = transactionRepository;
        this.dismissalRepository = dismissalRepository;
        this.accountRepository = accountRepository;
        this.categoryService = categoryService;
        this.classificationService = classificationService;
    }

    /** I suggerimenti di adesso. Quattro query, qualunque sia il numero di movimenti. */
    @Transactional(readOnly = true)
    public List<Suggestion> suggestions() {
        List<Set<String>> owners = owners();
        List<Example> examples = transactionRepository.findAllWithManualCategory().stream()
                .map(t -> new Example(MerchantKey.of(t.getCounterparty(), t.getDescription(), owners),
                        t.getManualCategory().getId(), t.getManualCategory().getName()))
                .filter(e -> e.merchantKey() != null) // un movimento senza testo non ha un esercente
                .toList();
        List<Candidate> candidates = transactionRepository.findUncategorized(SUGGESTED_TYPES).stream()
                .map(t -> new Candidate(MerchantKey.of(t.getCounterparty(), t.getDescription(), owners),
                        MerchantKey.merchantText(t.getCounterparty(), t.getDescription(), owners), t.getBookingDate()))
                .filter(c -> c.merchantKey() != null)
                .toList();
        // getCategory().getId() non carica la categoria: l'id Hibernate lo conosce già (è nella colonna category_id).
        Set<Dismissal> dismissals = dismissalRepository.findAll().stream()
                .map(d -> new Dismissal(d.getMerchantKey(), d.getCategory().getId()))
                .collect(Collectors.toSet());
        return HistorySuggester.suggest(examples, candidates, dismissals);
    }

    /**
     * Le categorie già usate per gli <b>altri</b> movimenti di esercenti con la stessa prima parola
     * (AMAZON…): il menu della categoria di questo movimento le mette in cima. Contano sia quelle scelte a
     * mano sia quelle messe dalle regole. Il confronto è largo (la prima parola) perché qui non si assegna
     * niente: cambia solo l'ordine del menu. Quattro query.
     */
    @Transactional(readOnly = true)
    public UsedCategories usedCategoriesFor(Long transactionId) {
        BankTransaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new NotFoundException("Movimento", transactionId));
        List<Set<String>> owners = owners();
        String word = MerchantKey.firstWord(
                MerchantKey.of(transaction.getCounterparty(), transaction.getDescription(), owners));
        List<Example> examples = transactionRepository.findAllWithCategory().stream()
                .filter(t -> !t.getId().equals(transactionId)) // la categoria di questo movimento non conta
                .map(t -> new Example(MerchantKey.firstWord(MerchantKey.of(t.getCounterparty(), t.getDescription(), owners)),
                        t.getCategory().getId(), t.getCategory().getName()))
                .toList();
        return new UsedCategories(word, HistorySuggester.usedCategories(examples, word));
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
        List<Set<String>> owners = owners();
        List<BankTransaction> toUpdate = transactionRepository.findUncategorized(SUGGESTED_TYPES).stream()
                .filter(t -> merchantKey.equals(MerchantKey.of(t.getCounterparty(), t.getDescription(), owners)))
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

    /** I nomi dei titolari dei conti (una query). */
    private List<Set<String>> owners() {
        return ClassificationService.ownerNames(accountRepository.findAll());
    }
}
