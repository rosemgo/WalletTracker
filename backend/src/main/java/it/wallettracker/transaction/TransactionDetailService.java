package it.wallettracker.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import it.wallettracker.account.AccountRepository;
import it.wallettracker.category.Category;
import it.wallettracker.category.CategoryService;
import it.wallettracker.classification.ClassificationRule;
import it.wallettracker.classification.ClassificationRuleRepository;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.subscription.MerchantKey;
import it.wallettracker.suggestion.SuggestionService;
import it.wallettracker.transaction.TransactionDetailDto.CategoryOrigin;
import it.wallettracker.transaction.TransactionDetailDto.Merchant;
import it.wallettracker.transaction.TransactionDetailDto.Origin;
import it.wallettracker.transaction.TransactionDetailDto.Suggestion;
import it.wallettracker.web.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Il dettaglio di un movimento e le correzioni "in blocco" (docs/37): tutti i suoi dati, perché ha quella categoria,
 * il suo esercente; e, quando cambi la categoria, gli altri movimenti dello stesso esercente da spostare insieme, con
 * la possibilità di annullare.
 */
@Service
public class TransactionDetailService {

    /** Il riepilogo dell'esercente guarda un anno: abbastanza per vedere le abitudini, non tutta la storia. */
    static final int MERCHANT_MONTHS = 12;

    /** Gli altri movimenti dell'esercente mostrati nel dettaglio. */
    static final int RECENT = 5;

    /** Il massimo di movimenti da correggere in una richiesta. */
    static final int MAX_CHANGES = 500;

    /** Solo spese ed entrate si spostano insieme: un giroconto allo stesso nome non è "un altro acquisto". */
    private static final Set<TransactionType> MOVABLE = Set.of(TransactionType.EXPENSE, TransactionType.INCOME);

    private final BankTransactionRepository repository;
    private final AccountRepository accountRepository;
    private final ClassificationRuleRepository ruleRepository;
    private final ClassificationService classificationService;
    private final CategoryService categoryService;
    private final SuggestionService suggestionService;

    public TransactionDetailService(BankTransactionRepository repository, AccountRepository accountRepository,
            ClassificationRuleRepository ruleRepository, ClassificationService classificationService,
            CategoryService categoryService, SuggestionService suggestionService) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.ruleRepository = ruleRepository;
        this.classificationService = classificationService;
        this.categoryService = categoryService;
        this.suggestionService = suggestionService;
    }

    /** Il dettaglio di un movimento; 404 se non esiste. */
    @Transactional(readOnly = true)
    public TransactionDetailDto detail(Long id, LocalDate today) {
        BankTransaction transaction = find(id);
        String key = merchantKey(transaction);

        CategoryOrigin categoryOrigin;
        String ruleName = null;
        if (transaction.getManualCategory() != null) {
            categoryOrigin = CategoryOrigin.MANUAL;
        } else if (transaction.getCategory() != null && transaction.getRuleId() != null) {
            categoryOrigin = CategoryOrigin.RULE;
            ruleName = ruleRepository.findById(transaction.getRuleId()).map(ClassificationRule::getName).orElse(null);
        } else {
            categoryOrigin = transaction.getCategory() == null ? CategoryOrigin.NONE : CategoryOrigin.RULE;
        }

        Suggestion suggestion = null;
        if (key != null && transaction.getCategory() == null) {
            suggestion = suggestionService.suggestions().stream()
                    .filter(s -> s.merchantKey().equals(key))
                    .findFirst()
                    .map(s -> new Suggestion(s.merchantKey(), s.categoryId(), s.category(), s.model()))
                    .orElse(null);
        }

        return new TransactionDetailDto(TransactionDto.from(transaction), transaction.getRawJson(),
                transaction.getImportedAt(), origin(transaction), categoryOrigin, ruleName,
                key == null ? null : merchant(transaction, key, today.minusMonths(MERCHANT_MONTHS)), suggestion);
    }

    /**
     * Gli altri movimenti dello stesso esercente che hanno la categoria {@code categoryId} ({@code null} = senza
     * categoria): quelli da proporre di spostare insieme, dopo che hai cambiato la categoria di questo. Solo spese ed
     * entrate, in tutta la storia.
     */
    @Transactional(readOnly = true)
    public List<TransactionDto> similar(Long id, Long categoryId) {
        BankTransaction transaction = find(id);
        String key = merchantKey(transaction);
        if (key == null) {
            return List.of();
        }
        List<Set<String>> owners = owners();
        return repository.findSinceWithCategory(LocalDate.of(1900, 1, 1)).stream()
                .filter(t -> !t.getId().equals(id) && MOVABLE.contains(t.getType()))
                .filter(t -> Objects.equals(categoryIdOf(t), categoryId))
                .filter(t -> key.equals(MerchantKey.of(t.getCounterparty(), t.getDescription(), owners)))
                .sorted(Comparator.comparing(BankTransaction::getBookingDate).reversed())
                .map(TransactionDto::from)
                .toList();
    }

    /**
     * Sceglie a mano la categoria di più movimenti in una volta. Restituisce, per ognuno, la scelta di <b>prima</b>
     * (null = nessuna): rimandarla indietro con la stessa richiesta è l'"Annulla". Tutto o niente: un id o una
     * categoria che non esistono fanno fallire tutta la richiesta, senza cambiare niente.
     */
    @Transactional
    public List<CategoryChange> changeCategories(List<CategoryChange> changes) {
        if (changes.isEmpty() || changes.size() > MAX_CHANGES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Da 1 a " + MAX_CHANGES + " movimenti per volta.");
        }
        Set<Long> ids = new HashSet<>();
        for (CategoryChange change : changes) {
            if (change.id() == null || !ids.add(change.id())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ogni movimento va indicato una volta sola.");
            }
        }
        Map<Long, BankTransaction> transactions = new HashMap<>();
        repository.findAllById(ids).forEach(t -> transactions.put(t.getId(), t));
        for (Long id : ids) {
            if (!transactions.containsKey(id)) {
                throw new NotFoundException("Movimento", id);
            }
        }
        // Prima si controllano tutte le categorie (400 se una non esiste), poi si cambia: niente modifiche a metà.
        Map<Long, Category> categories = new HashMap<>();
        for (CategoryChange change : changes) {
            if (change.categoryId() != null) {
                categories.computeIfAbsent(change.categoryId(), categoryService::require);
            }
        }
        List<CategoryChange> previous = new ArrayList<>();
        for (CategoryChange change : changes) {
            BankTransaction transaction = transactions.get(change.id());
            Category before = transaction.getManualCategory();
            previous.add(new CategoryChange(change.id(), before == null ? null : before.getId()));
            transaction.setManualCategory(change.categoryId() == null ? null : categories.get(change.categoryId()));
        }
        classificationService.classifyAll();
        return previous;
    }

    /** Un movimento e la categoria da scegliere a mano (null = togliere la scelta). */
    public record CategoryChange(Long id, Long categoryId) {
    }

    private Merchant merchant(BankTransaction transaction, String key, LocalDate from) {
        List<Set<String>> owners = owners();
        List<BankTransaction> same = repository.findSinceWithCategory(from).stream()
                .filter(t -> key.equals(MerchantKey.of(t.getCounterparty(), t.getDescription(), owners)))
                .sorted(Comparator.comparing(BankTransaction::getBookingDate).thenComparing(BankTransaction::getId)
                        .reversed())
                .toList();
        BigDecimal total = same.stream().map(BankTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        long categories = same.stream().map(TransactionDetailService::categoryIdOf).filter(Objects::nonNull)
                .distinct().count();
        List<TransactionDto> recent = same.stream()
                .filter(t -> !t.getId().equals(transaction.getId()))
                .limit(RECENT)
                .map(TransactionDto::from)
                .toList();
        return new Merchant(key,
                MerchantKey.merchantText(transaction.getCounterparty(), transaction.getDescription(), owners),
                same.size(), total, (int) categories, recent);
    }

    private static Origin origin(BankTransaction transaction) {
        if (transaction.getDedupKey().startsWith(BankTransaction.FILE_KEY_PREFIX)) {
            return Origin.TRADE_REPUBLIC_FILE;
        }
        return transaction.getAccount().isManual() ? Origin.CSV : Origin.BANK;
    }

    private static Long categoryIdOf(BankTransaction transaction) {
        return transaction.getCategory() == null ? null : transaction.getCategory().getId();
    }

    private String merchantKey(BankTransaction transaction) {
        return MerchantKey.of(transaction.getCounterparty(), transaction.getDescription(), owners());
    }

    private List<Set<String>> owners() {
        return ClassificationService.ownerNames(accountRepository.findAll());
    }

    private BankTransaction find(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Movimento", id));
    }
}
