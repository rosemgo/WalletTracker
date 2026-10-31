package it.wallettracker.investment;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import it.wallettracker.account.AccountRepository;
import it.wallettracker.account.AccountRole;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.investment.InvestmentDtos.Overview;
import it.wallettracker.investment.InvestmentDtos.Period;
import it.wallettracker.investment.Investments.Movement;
import it.wallettracker.subscription.MerchantKey;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.TransactionDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Gli investimenti (docs/investimenti.md): quanto hai versato e quanto ti hanno reso cedole, dividendi e interessi.
 * Legge i movimenti con una query e passa i calcoli a {@link Investments}.
 *
 * <p>Attenzione a che cosa <b>non</b> è: il valore di oggi degli investimenti. Per quello servirebbero le quotazioni
 * dei titoli, che l'app non conosce. "Versato" sono i soldi messi, non quanto valgono adesso.
 */
@Service
public class InvestmentService {

    /** Quanti versamenti recenti mostrare. */
    static final int RECENT = 8;

    /** "Tutto": al massimo 10 anni di mesi nel grafico. */
    static final int MAX_MONTHS = 120;

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final BankTransactionRepository repository;
    private final AccountRepository accountRepository;

    public InvestmentService(BankTransactionRepository repository, AccountRepository accountRepository) {
        this.repository = repository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public Overview overview(Period period, String currency, LocalDate today) {
        if (currency != null && !CURRENCY.matcher(currency.toUpperCase(Locale.ROOT)).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La valuta è un codice di tre lettere, come EUR.");
        }
        List<BankTransaction> transactions = repository.findInvestmentMovements().stream()
                .filter(t -> !t.getBookingDate().isAfter(today))
                .toList();
        Set<String> currencies = new TreeSet<>();
        transactions.forEach(t -> currencies.add(t.getCurrency()));
        String chosen = currency != null ? currency.toUpperCase(Locale.ROOT) : mainCurrency(transactions);

        List<Set<String>> owners = ClassificationService.ownerNames(accountRepository.findAll());
        List<BankTransaction> inCurrency = new ArrayList<>();
        List<Movement> movements = new ArrayList<>();
        for (BankTransaction t : transactions) {
            if (t.getCurrency().equals(chosen)) {
                inCurrency.add(t);
                movements.add(movementOf(t, owners));
            }
        }

        YearMonth current = YearMonth.from(today);
        YearMonth first = firstMonth(period, current, movements);
        LocalDate from = first.atDay(1);
        LocalDate yearStart = today.withDayOfYear(1);
        LocalDate monthStart = today.withDayOfMonth(1);

        List<TransactionDto> recent = new ArrayList<>();
        for (int i = 0; i < inCurrency.size(); i++) {
            if (Investments.contribution(movements.get(i)).signum() != 0) {
                recent.add(TransactionDto.from(inCurrency.get(i)));
            }
        }
        recent.sort(Comparator.comparing(TransactionDto::bookingDate).thenComparing(TransactionDto::id).reversed());

        return new Overview(chosen, new ArrayList<>(currencies), period, first, current,
                Investments.sum(movements, Investments::contribution, LocalDate.MIN, today),
                Investments.sum(movements, Investments::contribution, yearStart, today),
                Investments.sum(movements, Investments::contribution, monthStart, today),
                Investments.sum(movements, Investments::income, yearStart, today),
                Investments.sum(movements, Investments::contribution, from, today),
                Investments.sum(movements, Investments::income, from, today),
                Investments.sum(movements, Investments::taxes, from, today),
                Investments.months(movements, first, current),
                Investments.incomeBy(movements, from, today, Movement::category, "Altre rendite"),
                Investments.incomeBy(movements, from, today, Movement::source, "Senza descrizione"),
                recent.subList(0, Math.min(RECENT, recent.size())));
    }

    private static Movement movementOf(BankTransaction t, List<Set<String>> owners) {
        String text = MerchantKey.merchantText(t.getCounterparty(), t.getDescription(), owners);
        return new Movement(t.getBookingDate(), t.getAmount(), t.getType(),
                t.getAccount().getRole() == AccountRole.INVESTMENT,
                t.getCategory() == null ? null : t.getCategory().getName(),
                text == null ? null : MerchantKey.displayName(text));
    }

    /** Il primo mese del grafico: 12 o 24 mesi fa, oppure il mese del primo movimento (almeno 12, al massimo 120). */
    static YearMonth firstMonth(Period period, YearMonth current, List<Movement> movements) {
        return switch (period) {
            case YEAR -> current.minusMonths(11);
            case TWO_YEARS -> current.minusMonths(23);
            case ALL -> {
                YearMonth oldest = movements.stream()
                        .map(m -> YearMonth.from(m.date()))
                        .min(Comparator.naturalOrder())
                        .orElse(current);
                YearMonth atLeast = current.minusMonths(11);
                YearMonth atMost = current.minusMonths(MAX_MONTHS - 1L);
                YearMonth first = oldest.isBefore(atLeast) ? oldest : atLeast;
                yield first.isBefore(atMost) ? atMost : first;
            }
        };
    }

    /** La valuta con più movimenti di investimento; EUR se non ce ne sono. */
    private static String mainCurrency(List<BankTransaction> transactions) {
        Map<String, Integer> counts = new TreeMap<>();
        transactions.forEach(t -> counts.merge(t.getCurrency(), 1, Integer::sum));
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("EUR");
    }
}
