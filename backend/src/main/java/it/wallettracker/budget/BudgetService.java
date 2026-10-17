package it.wallettracker.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Collator;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import it.wallettracker.budget.BudgetDto.BudgetStatus;
import it.wallettracker.category.CategoryService;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.BankTransactionRepository.CategoryTotal;
import it.wallettracker.web.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * I budget mensili: elenco con l'andamento del mese, creazione, modifica, cancellazione.
 *
 * <p>Le spese di una categoria si calcolano come nel riepilogo (stessa query, {@code sumExpensesByCategory}):
 * contano solo i movimenti di tipo EXPENSE, quindi un rimborso segnato come "Rimborso di una spesa" riduce la
 * spesa del budget, e un trasferimento tra conti propri non la tocca mai.
 */
@Service
public class BudgetService {

    /** Da questa parte del budget in su, lo stato diventa WARNING. */
    static final BigDecimal WARNING_THRESHOLD = new BigDecimal("0.80");

    private final BudgetRepository budgetRepository;
    private final BankTransactionRepository transactionRepository;
    private final CategoryService categoryService;

    public BudgetService(BudgetRepository budgetRepository, BankTransactionRepository transactionRepository,
            CategoryService categoryService) {
        this.budgetRepository = budgetRepository;
        this.transactionRepository = transactionRepository;
        this.categoryService = categoryService;
    }

    /** I budget con l'andamento del mese {@code month}, in ordine alfabetico di categoria. */
    @Transactional(readOnly = true)
    public List<BudgetDto> budgets(YearMonth month) {
        Map<String, BigDecimal> spentInMonth = expensesByKey(month, month);
        Map<String, BigDecimal> spentInLast3Months = expensesByKey(month.minusMonths(3), month.minusMonths(1));
        Collator collator = Collator.getInstance(Locale.ITALIAN);
        return budgetRepository.findAll().stream()
                .sorted(Comparator.comparing(Budget::getCategory, collator).thenComparing(Budget::getCurrency))
                .map(budget -> describe(budget, month,
                        spentInMonth.getOrDefault(key(budget), BigDecimal.ZERO),
                        spentInLast3Months.getOrDefault(key(budget), BigDecimal.ZERO)))
                .toList();
    }

    @Transactional
    public BudgetDto create(BudgetRequest request) {
        String category = categoryService.resolve(request.category()); // deve essere nell'elenco
        checkNotDuplicate(category, request.currency(), null);
        Budget budget = budgetRepository.save(new Budget(category, request.currency(), request.monthlyAmount()));
        return describeCurrentMonth(budget);
    }

    @Transactional
    public BudgetDto update(Long id, BudgetRequest request) {
        Budget budget = find(id);
        String category = categoryService.resolve(request.category());
        checkNotDuplicate(category, request.currency(), budget);
        budget.update(category, request.currency(), request.monthlyAmount());
        return describeCurrentMonth(budget);
    }

    @Transactional
    public void delete(Long id) {
        budgetRepository.delete(find(id));
    }

    /** Un budget per categoria e valuta: un secondo budget per la stessa coppia → 409 Conflict. */
    private void checkNotDuplicate(String category, String currency, Budget current) {
        boolean sameAsCurrent = current != null
                && current.getCategory().equals(category) && current.getCurrency().equals(currency);
        if (!sameAsCurrent && budgetRepository.existsByCategoryAndCurrency(category, currency)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Esiste già un budget per \"" + category + "\" in " + currency + ".");
        }
    }

    private BudgetDto describeCurrentMonth(Budget budget) {
        return budgets(YearMonth.now()).stream()
                .filter(dto -> dto.id().equals(budget.getId()))
                .findFirst()
                .orElseThrow();
    }

    /** Il budget più i numeri del mese: spesa, quanto resta, ritmo, media, stato. */
    private static BudgetDto describe(Budget budget, YearMonth month, BigDecimal spent, BigDecimal spentIn3Months) {
        BigDecimal amount = budget.getMonthlyAmount();
        BudgetStatus status;
        if (spent.compareTo(amount) > 0) {
            status = BudgetStatus.OVER;
        } else if (spent.compareTo(amount.multiply(WARNING_THRESHOLD)) >= 0) {
            status = BudgetStatus.WARNING;
        } else {
            status = BudgetStatus.OK;
        }
        return new BudgetDto(budget.getId(), budget.getCategory(), budget.getCurrency(), amount, spent,
                amount.subtract(spent), expectedSoFar(amount, month, LocalDate.now()),
                spentIn3Months.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP), status);
    }

    /**
     * Quanto si "dovrebbe" aver speso finora spendendo in modo regolare. Mese in corso: la parte del budget
     * dei giorni passati, oggi compreso. Mese passato: tutto. Mese futuro: niente.
     */
    static BigDecimal expectedSoFar(BigDecimal amount, YearMonth month, LocalDate today) {
        YearMonth current = YearMonth.from(today);
        if (month.isBefore(current)) {
            return amount;
        }
        if (month.isAfter(current)) {
            return BigDecimal.ZERO.setScale(2);
        }
        return amount.multiply(BigDecimal.valueOf(today.getDayOfMonth()))
                .divide(BigDecimal.valueOf(month.lengthOfMonth()), 2, RoundingMode.HALF_UP);
    }

    /** Le spese per categoria tra due mesi (inclusi), come mappa "categoria|valuta" → spesa positiva. */
    private Map<String, BigDecimal> expensesByKey(YearMonth from, YearMonth to) {
        Map<String, BigDecimal> expenses = new HashMap<>();
        for (CategoryTotal row : transactionRepository.sumExpensesByCategory(from.atDay(1), to.plusMonths(1).atDay(1))) {
            if (row.getCategory() != null) {
                expenses.put(row.getCategory() + "|" + row.getCurrency(), row.getTotal().negate());
            }
        }
        return expenses;
    }

    private static String key(Budget budget) {
        return budget.getCategory() + "|" + budget.getCurrency();
    }

    private Budget find(Long id) {
        return budgetRepository.findById(id).orElseThrow(() -> new NotFoundException("Budget", id));
    }
}
