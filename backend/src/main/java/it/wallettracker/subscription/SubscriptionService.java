package it.wallettracker.subscription;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import it.wallettracker.account.AccountRepository;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.subscription.RecurringDetector.Payment;
import it.wallettracker.subscription.SubscriptionDtos.ConfirmRequest;
import it.wallettracker.subscription.SubscriptionDtos.Confirmed;
import it.wallettracker.subscription.SubscriptionDtos.DismissRequest;
import it.wallettracker.subscription.SubscriptionDtos.Dismissed;
import it.wallettracker.subscription.SubscriptionDtos.Overview;
import it.wallettracker.subscription.SubscriptionDtos.Suggestion;
import it.wallettracker.subscription.SubscriptionDtos.Total;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.TransactionType;
import it.wallettracker.web.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Gli abbonamenti ricorrenti: suggerimenti, conferme, rifiuti.
 *
 * <p>Il flusso:
 * <ol>
 *   <li>le spese degli ultimi 13 mesi vengono raggruppate per esercente ({@link MerchantKey}) e valuta;</li>
 *   <li>ogni gruppo passa da {@link RecurringDetector}: se ha l'aspetto di un abbonamento, diventa un
 *       <b>suggerimento</b>;</li>
 *   <li>l'utente decide: "sì" (CONFIRMED) o "no" (DISMISSED). La decisione resta salvata e quella serie
 *       non viene più suggerita.</li>
 * </ol>
 * Una "serie" è esercente + valuta + <b>importo</b> (V21): lo stesso esercente può avere più abbonamenti
 * (Fastweb: la SIM da 7,99 € e la linea di casa da 23,95 €), ognuno con i suoi pagamenti.
 * Gli abbonamenti non cambiano la classificazione dei movimenti: sono solo un modo diverso di guardarli.
 */
@Service
public class SubscriptionService {

    /** Quanto indietro guardare: 13 mesi, così un abbonamento annuale si vede due volte. */
    static final int LOOKBACK_DAYS = 400;

    /**
     * Quando un importo è "della stessa serie" di una decisione: entro il 50%. Più largo del 20% con cui
     * RecurringDetector riconosce una serie, perché un abbonamento confermato può rincarare (12,99 → 15,99 è il
     * 23%) e deve restare lui, con l'avviso del rincaro. Due contratti diversi dello stesso esercente di solito
     * differiscono molto di più (7,99 e 23,95: il triplo).
     */
    static final BigDecimal SAME_SERIES = new BigDecimal("0.50");

    static boolean sameSeries(BigDecimal amount, BigDecimal reference) {
        return amount.subtract(reference).abs().compareTo(reference.multiply(SAME_SERIES)) <= 0;
    }

    private final SubscriptionRepository subscriptionRepository;
    private final BankTransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
            BankTransactionRepository transactionRepository, AccountRepository accountRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
    }

    /** I pagamenti a uno stesso esercente, nella stessa valuta, con il testo dell'ultimo (per proporre un nome). */
    record Group(String merchantKey, String currency, List<Payment> payments, String lastText) {
    }

    /** I suggerimenti: le serie che sembrano abbonamenti e su cui l'utente non ha ancora deciso. */
    @Transactional(readOnly = true)
    public List<Suggestion> suggestions(LocalDate today) {
        List<Subscription> decisions = subscriptionRepository.findAll();
        List<Suggestion> suggestions = new ArrayList<>();
        for (Group group : groups(today).values()) {
            for (RecurringDetector.Pattern pattern : RecurringDetector.detectAll(group.payments(), today)) {
                boolean decided = decisions.stream().anyMatch(
                        d -> d.covers(group.merchantKey(), group.currency(), pattern.typicalAmount()));
                if (!decided) {
                    suggestions.add(new Suggestion(group.merchantKey(), group.currency(), group.lastText(),
                            pattern.frequency(), pattern.typicalAmount(), pattern.occurrences(), pattern.lastDate(),
                            pattern.nextDate()));
                }
            }
        }
        // Prima i più costosi (al mese): sono quelli che conviene controllare per primi.
        suggestions.sort(Comparator.comparing(
                (Suggestion s) -> monthlyCost(s.typicalAmount(), s.frequency())).reversed());
        return suggestions;
    }

    /** Gli abbonamenti confermati con i dati aggiornati, i totali e gli esercenti ignorati. */
    @Transactional(readOnly = true)
    public Overview overview(LocalDate today) {
        Map<String, Group> groups = groups(today);
        List<Confirmed> confirmed = new ArrayList<>();
        Map<String, BigDecimal[]> totals = new TreeMap<>(); // valuta -> {mensile, annuale}

        List<Subscription> allConfirmed = subscriptionRepository.findByStatus(Subscription.Status.CONFIRMED);
        for (Subscription subscription : allConfirmed) {
            Group group = groups.get(key(subscription.getMerchantKey(), subscription.getCurrency()));
            Optional<Payment> last = group == null ? Optional.empty()
                    : paymentsOf(subscription, group, allConfirmed).stream().max(Comparator.comparing(Payment::date));
            BigDecimal currentAmount = last.map(Payment::amount).orElse(subscription.getExpectedAmount());
            Frequency frequency = subscription.getFrequency();
            LocalDate nextDate = last.map(p -> frequency == Frequency.MONTHLY
                    ? p.date().plusMonths(1) : p.date().plusYears(1)).orElse(null);
            // In ritardo: il pagamento previsto non è arrivato, con un margine (le banche contabilizzano con
            // qualche giorno di ritardo). Nessun pagamento in 13 mesi = in ritardo di sicuro.
            int graceDays = frequency == Frequency.MONTHLY ? 7 : 30;
            boolean late = nextDate == null || today.isAfter(nextDate.plusDays(graceDays));
            BigDecimal monthlyCost = monthlyCost(currentAmount, frequency);

            confirmed.add(new Confirmed(subscription.getId(), subscription.getMerchantKey(),
                    subscription.getCurrency(), subscription.getName(), frequency, subscription.getExpectedAmount(),
                    last.map(Payment::amount).orElse(null), last.map(Payment::date).orElse(null), nextDate,
                    monthlyCost, last.isPresent() && currentAmount.compareTo(subscription.getExpectedAmount()) != 0,
                    late));
            if (!late) { // un abbonamento probabilmente disdetto non conta nel costo
                BigDecimal[] total = totals.computeIfAbsent(subscription.getCurrency(),
                        c -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                total[0] = total[0].add(monthlyCost);
                total[1] = total[1].add(currentAmount.multiply(BigDecimal.valueOf(frequency.timesPerYear)));
            }
        }
        confirmed.sort(Comparator.comparing(Confirmed::monthlyCost).reversed());

        List<Total> totalList = totals.entrySet().stream()
                .map(e -> new Total(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
        List<Dismissed> dismissed = subscriptionRepository.findByStatus(Subscription.Status.DISMISSED).stream()
                .map(s -> new Dismissed(s.getId(), s.getMerchantKey(), s.getCurrency(), s.getExpectedAmount()))
                .toList();
        return new Overview(confirmed, totalList, dismissed);
    }

    /** "Sì, è un abbonamento". */
    @Transactional
    public void confirm(ConfirmRequest request) {
        checkUndecided(request.merchantKey(), request.currency(), request.expectedAmount());
        subscriptionRepository.save(Subscription.confirmed(request.merchantKey(), request.currency(),
                request.name().trim(), request.frequency(), request.expectedAmount()));
    }

    /** "No, non è un abbonamento": quella serie non verrà più suggerita. */
    @Transactional
    public void dismiss(DismissRequest request) {
        checkUndecided(request.merchantKey(), request.currency(), request.typicalAmount());
        subscriptionRepository.save(
                Subscription.dismissed(request.merchantKey(), request.currency(), request.typicalAmount()));
    }

    /**
     * Cancella una decisione: un abbonamento confermato sparisce dall'elenco, un esercente ignorato può
     * tornare tra i suggerimenti.
     */
    @Transactional
    public void delete(Long id) {
        subscriptionRepository.delete(subscriptionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Abbonamento", id)));
    }

    private void checkUndecided(String merchantKey, String currency, BigDecimal amount) {
        boolean decided = subscriptionRepository.findByMerchantKeyAndCurrency(merchantKey, currency).stream()
                .anyMatch(d -> d.covers(merchantKey, currency, amount));
        if (decided) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Hai già deciso per questo esercente e questo importo: lo trovi nella pagina Abbonamenti.");
        }
    }

    /**
     * I pagamenti di un abbonamento confermato: quelli dello stesso esercente e valuta con un importo della stessa
     * serie ({@link #SAME_SERIES}). Se l'esercente ha più abbonamenti confermati, ogni pagamento va a quello con
     * l'importo più vicino: la SIM da 7,99 € non si prende la bolletta da 23,95 €.
     */
    static List<Payment> paymentsOf(Subscription subscription, Group group, List<Subscription> confirmed) {
        List<Subscription> sameMerchant = confirmed.stream()
                .filter(s -> s.getMerchantKey().equals(subscription.getMerchantKey())
                        && s.getCurrency().equals(subscription.getCurrency()))
                .toList();
        return group.payments().stream()
                .filter(p -> sameSeries(p.amount(), subscription.getExpectedAmount()))
                .filter(p -> sameMerchant.stream()
                        .min(Comparator.comparing((Subscription s) -> p.amount().subtract(s.getExpectedAmount()).abs())
                                .thenComparing(Subscription::getId))
                        .orElseThrow() == subscription)
                .toList();
    }

    /**
     * Le spese degli ultimi 13 mesi, raggruppate per esercente e valuta. Solo gli importi negativi: un
     * rimborso non è un pagamento dell'abbonamento.
     */
    private Map<String, Group> groups(LocalDate today) {
        List<BankTransaction> expenses = new ArrayList<>(transactionRepository.findByTypeAndBookingDateGreaterThanEqual(
                TransactionType.EXPENSE, today.minusDays(LOOKBACK_DAYS)));
        expenses.sort(Comparator.comparing(BankTransaction::getBookingDate));

        // I nomi dei titolari: se la controparte è il titolare, l'esercente è nella causale (vedi MerchantKey).
        List<Set<String>> owners = ClassificationService.ownerNames(accountRepository.findAll());
        Map<String, Group> groups = new LinkedHashMap<>();
        for (BankTransaction transaction : expenses) {
            String merchantKey = MerchantKey.of(transaction.getCounterparty(), transaction.getDescription(), owners);
            if (merchantKey == null || transaction.getAmount().signum() >= 0) {
                continue;
            }
            String text = MerchantKey.merchantText(transaction.getCounterparty(), transaction.getDescription(), owners);
            Group previous = groups.get(key(merchantKey, transaction.getCurrency()));
            List<Payment> payments = previous != null ? previous.payments() : new ArrayList<>();
            payments.add(new Payment(transaction.getBookingDate(), transaction.getAmount().negate()));
            // L'ordine per data fa sì che il testo salvato sia quello del pagamento più recente.
            groups.put(key(merchantKey, transaction.getCurrency()),
                    new Group(merchantKey, transaction.getCurrency(), payments, MerchantKey.displayName(text)));
        }
        return groups;
    }

    /** Il costo al mese: un annuale da 120 € costa 10 € al mese. */
    static BigDecimal monthlyCost(BigDecimal amount, Frequency frequency) {
        return amount.multiply(BigDecimal.valueOf(frequency.timesPerYear))
                .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
    }

    private static String key(String merchantKey, String currency) {
        return merchantKey + "|" + currency;
    }
}
