package it.wallettracker.investment;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import it.wallettracker.account.AccountRepository;
import it.wallettracker.account.AccountRole;
import it.wallettracker.account.AccountService;
import it.wallettracker.account.AccountSource;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.investment.InvestmentDtos.Cash;
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
 * Gli investimenti (docs/investimenti.md, parte I): quanto hai investito in titoli (acquisti e vendite), quanto hai
 * spostato con i bonifici verso i conti di investimento, e quanto ti hanno reso cedole, dividendi e interessi. Legge i
 * movimenti con una query e passa i calcoli a {@link Investments}.
 *
 * <p>Attenzione a che cosa <b>non</b> è: il valore di oggi degli investimenti. Per quello servirebbero le quotazioni
 * dei titoli, che l'app non conosce. L'investito sono i soldi messi nei titoli, non quanto valgono adesso.
 */
@Service
public class InvestmentService {

    /** "Tutto": al massimo 10 anni di mesi nel grafico. */
    static final int MAX_MONTHS = 120;

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final BankTransactionRepository repository;
    private final AccountRepository accountRepository;
    private final AccountService accountService;

    public InvestmentService(BankTransactionRepository repository, AccountRepository accountRepository,
            AccountService accountService) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.accountService = accountService;
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
        // Le ritenute nel giorno della loro rendita, su tutta la storia (Investments.withholdingsOnTheirIncome): una
        // cedola e la sua ritenuta cadono sempre nello stesso periodo. Stessa lista, stesso ordine di inCurrency.
        movements = Investments.withholdingsOnTheirIncome(movements);

        YearMonth current = YearMonth.from(today);
        YearMonth first = firstMonth(period, current, movements);
        LocalDate from = first.atDay(1);
        LocalDate yearStart = today.withDayOfYear(1);
        LocalDate monthStart = today.withDayOfMonth(1);

        // Gli elenchi sotto i grafici: i movimenti del periodo che li fanno, dal più recente.
        List<TransactionDto> trades = new ArrayList<>();
        List<TransactionDto> transfers = new ArrayList<>();
        for (int i = 0; i < inCurrency.size(); i++) {
            Movement m = movements.get(i);
            if (m.date().isBefore(from)) {
                continue;
            }
            if (Investments.bought(m).signum() != 0 || Investments.sold(m).signum() != 0) {
                trades.add(TransactionDto.from(inCurrency.get(i)));
            }
            if (Investments.transferred(m).signum() != 0) {
                transfers.add(TransactionDto.from(inCurrency.get(i)));
            }
        }
        Comparator<TransactionDto> newestFirst = Comparator.comparing(TransactionDto::bookingDate)
                .thenComparing(TransactionDto::id).reversed();
        trades.sort(newestFirst);
        transfers.sort(newestFirst);

        return new Overview(chosen, new ArrayList<>(currencies), period, first, current,
                Investments.sum(movements, Investments::invested, LocalDate.MIN, today),
                Investments.sum(movements, Investments::invested, yearStart, today),
                Investments.sum(movements, Investments::invested, monthStart, today),
                Investments.sum(movements, Investments::transferred, LocalDate.MIN, today),
                // Le rendite dell'anno al netto delle ritenute, come tutte le cifre della pagina (parte M): prima erano
                // lorde, e la scheda del Riepilogo non tornava con la pagina Investimenti.
                Investments.sum(movements, Investments::income, yearStart, today)
                        .add(Investments.sum(movements, Investments::withholding, yearStart, today)),
                Investments.sum(movements, Investments::bought, from, today),
                Investments.sum(movements, Investments::sold, from, today),
                Investments.sum(movements, Investments::transferred, from, today),
                Investments.sum(movements, Investments::income, from, today),
                Investments.sum(movements, Investments::withholding, from, today),
                Investments.sum(movements, Investments::otherTaxes, from, today),
                Investments.months(movements, first, current),
                Investments.netIncomeBy(movements, from, today, true),
                Investments.netIncomeBy(movements, from, today, false),
                Investments.otherTaxesBy(movements, from, today),
                trades, transfers, onlyTransfers(transactions), cash(chosen));
    }

    /**
     * La liquidità: il saldo di ogni conto di investimento nella valuta scelta, con il suo nome (docs/saldi.md, parte
     * D). Un conto senza saldo c'è lo stesso, con il saldo null: la pagina dice come averlo.
     */
    private List<Cash> cash(String currency) {
        return accountService.allAccounts().stream()
                .filter(a -> a.role() == AccountRole.INVESTMENT && a.currency().equals(currency))
                .map(a -> new Cash(a.id(), a.label(), a.source() == AccountSource.MANUAL, a.balance()))
                .toList();
    }

    /**
     * I conti di investimento con dei bonifici (versamenti o prelievi) ma, in tutta la storia, nessun acquisto né
     * vendita di titoli: per loro l'investito non si vede, e la pagina lo dice. Succede con un broker di cui si è
     * creato il conto ma non si importano i movimenti. In ordine di nome.
     */
    static List<String> onlyTransfers(List<BankTransaction> transactions) {
        Map<Long, String> withTransfers = new TreeMap<>();
        Set<Long> withTrades = new HashSet<>();
        for (BankTransaction t : transactions) {
            if (t.getAccount().getRole() != AccountRole.INVESTMENT) {
                continue;
            }
            switch (t.getType()) {
                case INVESTMENT_DEPOSIT, INVESTMENT_WITHDRAWAL -> withTransfers.put(t.getAccount().getId(),
                        t.getAccount().label());
                case SECURITIES_BUY, SECURITIES_SELL -> withTrades.add(t.getAccount().getId());
                default -> {
                    // rendite e tasse: non dicono niente sugli acquisti
                }
            }
        }
        withTrades.forEach(withTransfers::remove);
        return withTransfers.values().stream().sorted().toList();
    }

    private static Movement movementOf(BankTransaction t, List<Set<String>> owners) {
        // "Da dove": il nome del titolo (Investments.securityName); se il testo non ne ha uno (gli interessi di Trade
        // Republic), il nome del conto, che almeno dice da dove arriva.
        String text = MerchantKey.merchantText(t.getCounterparty(), t.getDescription(), owners);
        String security = Investments.securityName(text);
        return new Movement(t.getBookingDate(), t.getAmount(), t.getType(),
                t.getAccount().getRole() == AccountRole.INVESTMENT,
                t.getCategory() == null ? null : t.getCategory().getName(),
                security != null ? security : t.getAccount().label(),
                security != null ? null : t.getAccount().getId(),
                security != null ? null : Investments.searchWords(text));
    }

    /**
     * Il primo mese del grafico: 12 o 24 mesi fa, gennaio dell'anno in corso, oppure il mese del primo movimento (almeno
     * 12, al massimo 120).
     */
    static YearMonth firstMonth(Period period, YearMonth current, List<Movement> movements) {
        return switch (period) {
            case YEAR -> current.minusMonths(11);
            case YEAR_TO_DATE -> YearMonth.of(current.getYear(), 1);
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
