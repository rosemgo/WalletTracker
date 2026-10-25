package it.wallettracker.explore;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import it.wallettracker.account.AccountRepository;
import it.wallettracker.budget.BudgetDto;
import it.wallettracker.budget.BudgetService;
import it.wallettracker.category.Category;
import it.wallettracker.category.CategoryKind;
import it.wallettracker.category.CategoryRepository;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.explore.ExploreDtos.Bar;
import it.wallettracker.explore.ExploreDtos.By;
import it.wallettracker.explore.ExploreDtos.CategoryOverview;
import it.wallettracker.explore.ExploreDtos.Groups;
import it.wallettracker.explore.ExploreDtos.Metric;
import it.wallettracker.explore.ExploreDtos.MonthValue;
import it.wallettracker.explore.ExploreDtos.Period;
import it.wallettracker.explore.ExploreDtos.Series;
import it.wallettracker.explore.Explorer.Movement;
import it.wallettracker.overview.MonthCurves;
import it.wallettracker.subscription.MerchantKey;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.TransactionType;
import it.wallettracker.web.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Analisi (docs/38): le barre dell'esploratore, i movimenti di una barra raggruppati, la pagina di una categoria.
 * Legge i movimenti del periodo con una query e passa i calcoli a {@link Explorer}. Contano le spese e le entrate
 * vere, come nel Riepilogo (docs/36).
 */
@Service
public class ExploreService {

    /** La media "di solito" viene dagli ultimi sei mesi completi, come nel Riepilogo. */
    static final int USUAL_MONTHS = 6;

    /** Fin dove si può tornare indietro: 10 anni. */
    static final int MAX_YEARS_BACK = 10;

    /** Il periodo più lungo per i gruppi: un anno (con qualche giorno di margine). */
    static final int MAX_GROUP_DAYS = 370;

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final BankTransactionRepository repository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final BudgetService budgetService;

    public ExploreService(BankTransactionRepository repository, AccountRepository accountRepository,
            CategoryRepository categoryRepository, BudgetService budgetService) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
        this.budgetService = budgetService;
    }

    /** Le barre del periodo che finisce il giorno {@code end}, con la media di solito per barra. */
    @Transactional(readOnly = true)
    public Series series(Metric metric, Period period, LocalDate end, Long categoryId, String currency,
            LocalDate today) {
        checkDate(end, today);
        List<Bar> bars = Explorer.bars(period, end, today);
        YearMonth current = YearMonth.from(today);
        LocalDate from = min(bars.getFirst().from(), current.minusMonths(USUAL_MONTHS).atDay(1));
        Loaded loaded = load(from, bars.getLast().to(), currency);
        List<YearMonth> usualMonths = MonthCurves.usualMonths(current, USUAL_MONTHS,
                repository.findFirstDate(loaded.currency()));
        return new Series(metric, period, categoryId, loaded.currency(), loaded.currencies(),
                Explorer.unitOf(period), Explorer.withValues(bars, loaded.movements(), metric, categoryId),
                Explorer.averagePerUnit(loaded.movements(), metric, categoryId, usualMonths, Explorer.unitOf(period)),
                usualMonths.size());
    }

    /**
     * I movimenti dal giorno {@code from} al giorno {@code to} raggruppati. Il solito di ogni categoria c'è quando il
     * periodo è un mese (intero, o fino a oggi per il mese in corso).
     *
     * <p>Una barra del periodo in corso finisce nel futuro (la settimana fino a domenica, il mese fino al 31): il
     * periodo si accorcia a oggi, perché dopo oggi non c'è ancora niente da contare.
     */
    @Transactional(readOnly = true)
    public Groups groups(Metric metric, LocalDate from, LocalDate requestedTo, By by, Long categoryId,
            String currency, LocalDate today) {
        checkDate(from, today);
        LocalDate to = requestedTo.isAfter(today) ? today : requestedTo;
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > MAX_GROUP_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Il periodo va da un giorno a un altro più avanti, al massimo un anno.");
        }
        YearMonth month = YearMonth.from(from);
        boolean isMonth = from.getDayOfMonth() == 1 && YearMonth.from(to).equals(month)
                && (to.equals(month.atEndOfMonth()) || to.equals(today));
        Loaded loaded = load(isMonth ? month.minusMonths(USUAL_MONTHS).atDay(1) : from, to, currency);
        List<YearMonth> usualMonths = isMonth && by == By.CATEGORY
                ? MonthCurves.usualMonths(month, USUAL_MONTHS, repository.findFirstDate(loaded.currency()))
                : List.of();
        int day = to.getDayOfMonth();
        List<Movement> movements = loaded.movements();
        if (metric == Metric.NET) {
            return new Groups(metric, by, from, to, loaded.currency(),
                    Explorer.value(movements, metric, from, to, categoryId), List.of(),
                    Explorer.groups(movements, Metric.INCOME, from, to, by, categoryId, usualMonths, day),
                    Explorer.groups(movements, Metric.EXPENSES, from, to, by, categoryId, usualMonths, day));
        }
        return new Groups(metric, by, from, to, loaded.currency(),
                Explorer.value(movements, metric, from, to, categoryId),
                Explorer.groups(movements, metric, from, to, by, categoryId, usualMonths, day), List.of(), List.of());
    }

    /** La pagina di una categoria nel mese {@code month}: il mese rispetto al solito, 12 mesi, dove, il budget. */
    @Transactional(readOnly = true)
    public CategoryOverview category(Long id, YearMonth month, LocalDate today) {
        Category category = categoryRepository.findById(id).orElseThrow(() -> new NotFoundException("Categoria", id));
        YearMonth current = YearMonth.from(today);
        if (month.isAfter(current)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Il mese " + month + " non è ancora cominciato.");
        }
        Metric metric = category.getKind() == CategoryKind.INCOME ? Metric.INCOME : Metric.EXPENSES;
        LocalDate day = month.equals(current) ? today : month.atEndOfMonth();
        Loaded loaded = load(month.minusMonths(11).atDay(1), day, null);
        List<Movement> movements = loaded.movements();
        List<YearMonth> usualMonths = MonthCurves.usualMonths(month, USUAL_MONTHS,
                repository.findFirstDate(loaded.currency()));

        List<MonthValue> months = new ArrayList<>();
        for (YearMonth m = month.minusMonths(11); !m.isAfter(month); m = m.plusMonths(1)) {
            LocalDate to = m.equals(month) ? day : m.atEndOfMonth();
            months.add(new MonthValue(m, Explorer.value(movements, metric, m.atDay(1), to, id)));
        }
        BudgetDto budget = budgetService.budgets(month).stream()
                .filter(b -> b.categoryId().equals(id))
                .findFirst()
                .orElse(null);
        return new CategoryOverview(id, category.getName(), metric, month, day, loaded.currency(),
                Explorer.value(movements, metric, month.atDay(1), day, id),
                Explorer.usualUpTo(movements, metric, id, usualMonths, day.getDayOfMonth()),
                Explorer.usualUpTo(movements, metric, id, usualMonths, 31),
                usualMonths.size(), months,
                Explorer.groups(movements, metric, month.atDay(1), day, By.MERCHANT, id, List.of(), 0),
                budget);
    }

    /** I movimenti letti e la valuta scelta. */
    private record Loaded(List<Movement> movements, String currency, List<String> currencies) {
    }

    /** Legge i movimenti del periodo nella valuta scelta (null = quella con più spese). */
    private Loaded load(LocalDate from, LocalDate to, String currency) {
        if (currency != null && !CURRENCY.matcher(currency.toUpperCase(Locale.ROOT)).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La valuta è un codice di tre lettere, come EUR.");
        }
        List<BankTransaction> transactions = repository.findMovementsBetween(from, to);
        String chosen = currency != null ? currency.toUpperCase(Locale.ROOT) : mainCurrency(transactions);
        Set<String> currencies = new TreeSet<>();
        List<Set<String>> owners = ClassificationService.ownerNames(accountRepository.findAll());
        List<Movement> movements = new ArrayList<>();
        for (BankTransaction t : transactions) {
            currencies.add(t.getCurrency());
            if (t.getCurrency().equals(chosen)) {
                String key = MerchantKey.of(t.getCounterparty(), t.getDescription(), owners);
                movements.add(new Movement(t.getBookingDate(), t.getAmount(), t.getType(),
                        t.getCategory() == null ? null : t.getCategory().getId(),
                        t.getCategory() == null ? null : t.getCategory().getName(),
                        t.getAccount().getId(), t.getAccount().label(), key,
                        key == null ? null : MerchantKey.merchantText(t.getCounterparty(), t.getDescription(), owners)
                                .replaceAll("\\s+", " ").trim()));
            }
        }
        return new Loaded(movements, chosen, new ArrayList<>(currencies));
    }

    /** La valuta con più spese; EUR se non ce ne sono. */
    private static String mainCurrency(List<BankTransaction> transactions) {
        Map<String, Integer> counts = new TreeMap<>();
        for (BankTransaction t : transactions) {
            if (t.getType() == TransactionType.EXPENSE) {
                counts.merge(t.getCurrency(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("EUR");
    }

    private static void checkDate(LocalDate date, LocalDate today) {
        if (date.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Il giorno " + date + " non è ancora arrivato.");
        }
        if (date.isBefore(today.minusYears(MAX_YEARS_BACK))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Puoi tornare indietro al massimo di " + MAX_YEARS_BACK + " anni.");
        }
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
