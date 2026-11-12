package it.wallettracker.overview;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import it.wallettracker.overview.MonthCurves.Expense;
import it.wallettracker.overview.OverviewDto.CategoryRow;
import it.wallettracker.overview.OverviewDto.Upcoming;
import it.wallettracker.subscription.SubscriptionDtos.Confirmed;
import it.wallettracker.subscription.SubscriptionService;
import it.wallettracker.suggestion.SuggestionService;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.BankTransactionRepository.DailyTotal;
import it.wallettracker.transaction.TransactionType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Il Riepilogo di un mese (docs/36): quanto hai speso finora e com'è rispetto al solito, dove vanno i soldi, che cosa
 * arriva nei prossimi giorni, che cosa c'è da sistemare.
 *
 * <p>Contano gli stessi movimenti del riepilogo di 12 mesi (SummaryService, docs/18): spese ({@code EXPENSE}) ed
 * entrate ({@code INCOME}) vere, anche in attesa. Trasferimenti tra i tuoi conti, saldo della carta, investimenti e
 * movimenti ignorati non sono né spese né entrate; quelli da verificare restano fuori, e la pagina dice quanti sono.
 */
@Service
public class OverviewService {

    /** Il solito è la media di (al massimo) sei mesi: abbastanza per non dipendere da un mese strano, e recente. */
    static final int USUAL_MONTHS = 6;

    /** Gli abbonamenti "in arrivo": quelli attesi entro una settimana. */
    static final int UPCOMING_DAYS = 7;

    /** Fin dove si può tornare indietro: 10 anni, come il riepilogo. */
    static final int MAX_MONTHS_BACK = 120;

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final BankTransactionRepository repository;
    private final SubscriptionService subscriptionService;
    private final SuggestionService suggestionService;

    public OverviewService(BankTransactionRepository repository, SubscriptionService subscriptionService,
            SuggestionService suggestionService) {
        this.repository = repository;
        this.subscriptionService = subscriptionService;
        this.suggestionService = suggestionService;
    }

    /**
     * Il Riepilogo del mese {@code month}, visto il giorno {@code today}.
     *
     * @param currency la valuta (es. "EUR"); null = quella con più spese nel periodo
     * @param today    il giorno di oggi: è un parametro (e non {@code LocalDate.now()}) perché i test possano
     *                 scegliere "oggi"
     */
    @Transactional(readOnly = true)
    public OverviewDto overview(YearMonth month, String currency, LocalDate today) {
        YearMonth current = YearMonth.from(today);
        if (month.isAfter(current)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Il mese " + month + " non è ancora cominciato.");
        }
        if (month.isBefore(current.minusMonths(MAX_MONTHS_BACK))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Puoi tornare indietro al massimo di " + MAX_MONTHS_BACK + " mesi.");
        }
        if (currency != null && !CURRENCY.matcher(currency.toUpperCase(Locale.ROOT)).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La valuta è un codice di tre lettere, come EUR.");
        }
        boolean isCurrent = month.equals(current);
        LocalDate day = isCurrent ? today : month.atEndOfMonth();

        // 1. Una query sola: spese ed entrate, giorno per giorno, dal primo mese del solito a oggi.
        YearMonth firstUsual = month.minusMonths(USUAL_MONTHS);
        List<DailyTotal> rows = repository.sumByDay(firstUsual.atDay(1), day.plusDays(1));
        String chosen = currency != null ? currency.toUpperCase(Locale.ROOT) : mainCurrency(rows);
        List<String> currencies = new ArrayList<>(new TreeSet<>(rows.stream().map(DailyTotal::getCurrency).toList()));

        List<Expense> expenses = new ArrayList<>();
        BigDecimal income = BigDecimal.ZERO;
        for (DailyTotal row : rows) {
            if (!row.getCurrency().equals(chosen)) {
                continue;
            }
            if (row.getType() == TransactionType.EXPENSE) {
                // Le spese sono negative nel database; qui positive. Un rimborso (positivo) diventa negativo e le riduce.
                expenses.add(new Expense(row.getDay(), row.getCategoryId(), row.getCategory(), row.getTotal().negate(),
                        row.getMovements()));
            } else if (YearMonth.from(row.getDay()).equals(month)) {
                income = income.add(row.getTotal());
            }
        }

        // 2. Il mese e il solito.
        int dayOfMonth = day.getDayOfMonth();
        List<YearMonth> usualMonths = MonthCurves.usualMonths(month, USUAL_MONTHS, repository.findFirstDate(chosen));
        List<BigDecimal> spentByDay = MonthCurves.cumulative(expenses, month, dayOfMonth);

        return new OverviewDto(month, day, month.lengthOfMonth(), chosen, currencies,
                spentByDay.isEmpty() ? BigDecimal.ZERO : spentByDay.getLast(), income, spentByDay,
                usualMonths.size(),
                MonthCurves.usualUpTo(expenses, usualMonths, dayOfMonth),
                MonthCurves.usualUpTo(expenses, usualMonths, 31),
                MonthCurves.typical(expenses, usualMonths, month.lengthOfMonth()),
                categories(expenses, month, dayOfMonth, usualMonths),
                isCurrent ? upcoming(today, chosen) : List.of(),
                repository.countByTypeOrTypeIsNull(TransactionType.TO_REVIEW),
                suggestionService.suggestions().size());
    }

    /** La valuta con più spese nel periodo; EUR se non ce ne sono. */
    private static String mainCurrency(List<DailyTotal> rows) {
        Map<String, Long> movements = new TreeMap<>();
        for (DailyTotal row : rows) {
            if (row.getType() == TransactionType.EXPENSE) {
                movements.merge(row.getCurrency(), row.getMovements(), Long::sum);
            }
        }
        return movements.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("EUR");
    }

    /** Le categorie con spese nel mese, dalla più alta, ognuna con il suo solito. */
    private static List<CategoryRow> categories(List<Expense> expenses, YearMonth month, int day,
            List<YearMonth> usualMonths) {
        // LinkedHashMap: una voce per categoria, nell'ordine in cui le troviamo (poi le ordiniamo per importo).
        Map<Long, String> names = new LinkedHashMap<>();
        Map<Long, long[]> movements = new LinkedHashMap<>();
        for (Expense expense : expenses) {
            if (YearMonth.from(expense.day()).equals(month) && expense.day().getDayOfMonth() <= day) {
                names.putIfAbsent(expense.categoryId(), expense.category());
                movements.computeIfAbsent(expense.categoryId(), id -> new long[1])[0] += expense.movements();
            }
        }
        List<CategoryRow> rows = new ArrayList<>();
        names.forEach((categoryId, name) -> {
            List<Expense> ofCategory = MonthCurves.ofCategory(expenses, categoryId);
            BigDecimal amount = MonthCurves.upTo(ofCategory, month, day);
            if (amount.signum() > 0) { // una categoria fatta solo di rimborsi non è "dove vanno i soldi"
                rows.add(new CategoryRow(categoryId, name, amount, movements.get(categoryId)[0],
                        MonthCurves.usualUpTo(ofCategory, usualMonths, day),
                        MonthCurves.usualUpTo(ofCategory, usualMonths, 31)));
            }
        });
        rows.sort(Comparator.comparing(CategoryRow::amount).reversed());
        return rows;
    }

    /** Gli abbonamenti confermati, non disdetti, attesi da oggi a una settimana, nella valuta scelta. */
    private List<Upcoming> upcoming(LocalDate today, String currency) {
        LocalDate until = today.plusDays(UPCOMING_DAYS);
        return subscriptionService.overview(today).subscriptions().stream()
                .filter(s -> s.currency().equals(currency) && !s.late() && s.nextDate() != null)
                .filter(s -> !s.nextDate().isBefore(today) && !s.nextDate().isAfter(until))
                .sorted(Comparator.comparing(Confirmed::nextDate))
                .map(s -> new Upcoming(s.id(), s.name(), s.nextDate(),
                        Objects.requireNonNullElse(s.lastAmount(), s.expectedAmount())))
                .toList();
    }
}
