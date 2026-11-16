package it.wallettracker.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import it.wallettracker.account.BalanceHistory.Point;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.BankTransactionRepository.AccountDayTotal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo storico della liquidità (docs/saldi.md, parte L): quanti soldi c'erano sui conti, giorno per giorno.
 *
 * <p>Due fonti, tenute distinte:
 * <ul>
 *   <li><b>le fotografie</b>: il saldo di ogni conto salvato ogni ora mentre l'app è accesa
 *       ({@link #recordToday}). Sono valori veri;</li>
 *   <li><b>la stima</b>: per un giorno senza fotografia si parte dal valore vero successivo (una fotografia, o il
 *       saldo di oggi) e si tolgono i movimenti nel mezzo. È esatta solo se nell'app ci sono tutti i movimenti, e non
 *       è sempre così (la banca dà un anno al massimo, un file parte da quando lo si scarica): per questo ogni giorno
 *       dice se è stimato, e il grafico lo disegna tratteggiato.</li>
 * </ul>
 *
 * <p>Contano gli stessi conti della scheda Liquidità: niente carte di credito (hanno un plafond, non un saldo) né conti
 * esclusi, e solo quelli con un saldo. Una valuta alla volta, senza conversioni.
 */
@Service
public class BalanceHistoryService {

    /** Fin dove si guarda indietro: un anno. */
    static final int MAX_DAYS = 365;

    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final BalanceSnapshotRepository snapshotRepository;
    private final BankTransactionRepository transactionRepository;

    public BalanceHistoryService(AccountRepository accountRepository, AccountService accountService,
            BalanceSnapshotRepository snapshotRepository, BankTransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.snapshotRepository = snapshotRepository;
        this.transactionRepository = transactionRepository;
    }

    /** Un conto conta nella liquidità: non una carta di credito, non escluso (come la scheda del Riepilogo). */
    static boolean counts(Account account) {
        return account.getRole() != AccountRole.CREDIT_CARD && account.getRole() != AccountRole.EXCLUDED;
    }

    /**
     * Le fotografie: il saldo della banca di ogni conto che conta e ne ha uno, sotto il giorno a cui è vero
     * ({@link #trueOn}). Restituisce quanti conti ha salvato.
     */
    @Transactional
    public int recordToday(LocalDate today) {
        int saved = 0;
        for (Account account : accountRepository.findAll()) {
            if (!counts(account)) {
                continue;
            }
            CurrentBalance balance = accountService.currentBalance(account);
            // Solo i saldi della banca. Uno scritto a mano più i movimenti si ricalcola ogni volta (vedi reconstruct):
            // fotografato, resterebbe senza i movimenti di un file caricato dopo.
            if (balance != null && balance.source() == BalanceSource.BANK) {
                snapshotRepository.upsert(account.getId(), trueOn(balance, today), balance.amount());
                saved++;
            }
        }
        return saved;
    }

    /**
     * Il giorno a cui un saldo è vero. Quello della banca è vero al giorno che dice lei: in background la banca si
     * rilegge al massimo ogni 20 ore (docs/saldi.md, parte B), e il saldo letto ieri non contiene i movimenti di oggi.
     * Quello scritto a mano segue già ogni movimento importato (parte C): è vero oggi. Mai dopo oggi.
     */
    static LocalDate trueOn(CurrentBalance balance, LocalDate today) {
        LocalDate date = balance.source() == BalanceSource.BANK ? balance.date() : today;
        return date == null || date.isAfter(today) ? today : date;
    }

    /** La liquidità in {@code currency}, giorno per giorno, dall'inizio della storia (al massimo un anno) a oggi. */
    @Transactional(readOnly = true)
    public BalanceHistory history(String currency, LocalDate today) {
        return history(currency, null, today);
    }

    /**
     * Come {@link #history(String, LocalDate)}, ma di un conto solo ({@code accountId}; null = tutti): per confrontare
     * la linea di un conto con il suo estratto. Un conto che non conta nella liquidità, di un'altra valuta o senza saldo
     * dà una storia vuota.
     */
    @Transactional(readOnly = true)
    public BalanceHistory history(String currency, Long accountId, LocalDate today) {
        String chosen = currency.toUpperCase(Locale.ROOT);
        Map<Long, Anchor> current = new LinkedHashMap<>();
        for (Account account : accountRepository.findAll()) {
            if (accountId != null && !accountId.equals(account.getId())) {
                continue;
            }
            if (counts(account) && chosen.equals(account.getCurrency())) {
                CurrentBalance balance = accountService.currentBalance(account);
                if (balance != null) {
                    // Un saldo scritto a mano è vero dal suo giorno di partenza in poi: da lì è il saldo scritto più i
                    // movimenti, come nella pagina Conti.
                    LocalDate trustedFrom = balance.source() == BalanceSource.MANUAL ? balance.startDate() : null;
                    current.put(account.getId(), new Anchor(trueOn(balance, today), balance.amount(), trustedFrom));
                }
            }
        }
        if (current.isEmpty()) {
            return new BalanceHistory(chosen, 0, List.of());
        }

        // Da quando: il primo movimento (o la prima fotografia) di uno dei conti, al massimo un anno fa.
        LocalDate earliest = today.minusDays(MAX_DAYS);
        List<BalanceSnapshot> snapshots = snapshotRepository.findBetween(current.keySet(), earliest, today);
        LocalDate start = today;
        LocalDate firstMovement = transactionRepository.firstBookingDate(current.keySet());
        if (firstMovement != null && firstMovement.isBefore(start)) {
            start = firstMovement;
        }
        for (BalanceSnapshot s : snapshots) {
            if (s.getDate().isBefore(start)) {
                start = s.getDate();
            }
        }
        for (Anchor anchor : current.values()) {
            if (anchor.date().isBefore(start)) {
                start = anchor.date(); // un saldo della banca di ieri, senza movimenti né fotografie: da ieri
            }
        }
        if (start.isBefore(earliest)) {
            start = earliest;
        }

        Map<Long, Map<LocalDate, BigDecimal>> photos = new HashMap<>();
        for (BalanceSnapshot s : snapshots) {
            if (current.get(s.getAccountId()).trustedFrom() != null) {
                continue; // un conto con il saldo scritto a mano: le fotografie non contano (vedi recordToday)
            }
            photos.computeIfAbsent(s.getAccountId(), id -> new HashMap<>()).put(s.getDate(), s.getAmount());
        }
        Map<Long, Map<LocalDate, BigDecimal>> movements = new HashMap<>();
        for (AccountDayTotal row : transactionRepository.sumByAccountAndDay(current.keySet(), start, today)) {
            movements.computeIfAbsent(row.getAccountId(), id -> new HashMap<>()).put(row.getDay(), row.getTotal());
        }
        return new BalanceHistory(chosen, current.size(), reconstruct(start, today, current, photos, movements));
    }

    /**
     * Il saldo di adesso di un conto, con il giorno a cui è vero ({@link #trueOn}).
     *
     * @param trustedFrom per un saldo scritto a mano, il suo giorno di partenza: da lì in poi ogni giorno è il saldo
     *                    scritto più i movimenti, esatto quanto il saldo del conto, e non è una stima. Null per un saldo
     *                    della banca: per lei sono veri solo i giorni con un valore della banca
     */
    record Anchor(LocalDate date, BigDecimal amount, LocalDate trustedFrom) {

        /** Un saldo della banca. */
        Anchor(LocalDate date, BigDecimal amount) {
            this(date, amount, null);
        }
    }

    /**
     * Il calcolo, senza database (per i test). Per ogni conto, i valori veri sono le fotografie e il saldo di adesso
     * (al giorno a cui è vero, che vince su una fotografia dello stesso giorno: è più recente). Ogni altro giorno è una
     * stima:
     * <ul>
     *   <li>prima dell'ultimo valore vero, all'indietro: il valore del giorno dopo meno i movimenti del giorno dopo.
     *       Prima del primo movimento di un conto il suo saldo resta quindi fermo: non sappiamo niente di diverso;</li>
     *   <li>dopo l'ultimo valore vero (un saldo della banca di ieri, oggi), in avanti: l'ultimo valore vero più i
     *       movimenti arrivati dopo.</li>
     * </ul>
     * Per un saldo scritto a mano ({@code trustedFrom}) il calcolo è lo stesso, ma i giorni dal suo giorno di partenza in
     * poi non sono stime: sono il saldo scritto più i movimenti, come il saldo del conto. Si ricalcolano a ogni
     * richiesta, quindi un file caricato dopo corregge anche i giorni passati.
     *
     * @param current   il saldo di adesso di ogni conto, con il suo giorno
     * @param photos    le fotografie, per conto e per giorno
     * @param movements la somma dei movimenti, per conto e per giorno
     */
    static List<Point> reconstruct(LocalDate start, LocalDate today, Map<Long, Anchor> current,
            Map<Long, Map<LocalDate, BigDecimal>> photos, Map<Long, Map<LocalDate, BigDecimal>> movements) {
        int days = (int) (today.toEpochDay() - start.toEpochDay()) + 1;
        BigDecimal[] totals = new BigDecimal[days];
        boolean[] estimated = new boolean[days];
        Arrays.fill(totals, BigDecimal.ZERO);
        current.forEach((accountId, anchor) -> {
            Map<LocalDate, BigDecimal> shots = new HashMap<>(photos.getOrDefault(accountId, Map.of()));
            shots.put(anchor.date(), anchor.amount());
            Map<LocalDate, BigDecimal> moved = movements.getOrDefault(accountId, Map.of());
            BigDecimal[] values = new BigDecimal[days];
            boolean[] guessed = new boolean[days];
            // Il punto di partenza: il giorno del saldo di adesso. Se è più vecchio dell'inizio del grafico (una banca
            // che non lo aggiorna da molto), si porta all'inizio aggiungendo i movimenti nel mezzo: è una stima.
            int last = (int) (anchor.date().toEpochDay() - start.toEpochDay());
            if (last < 0) {
                BigDecimal value = anchor.amount();
                for (LocalDate d = anchor.date().plusDays(1); !d.isAfter(start); d = d.plusDays(1)) {
                    value = value.add(moved.getOrDefault(d, BigDecimal.ZERO));
                }
                last = 0;
                values[0] = value;
                guessed[0] = true;
            } else {
                values[last] = anchor.amount();
            }
            // All'indietro: una fotografia, o il giorno dopo meno i suoi movimenti.
            for (int k = last - 1; k >= 0; k--) {
                BigDecimal shot = shots.get(start.plusDays(k));
                if (shot != null) {
                    values[k] = shot;
                } else {
                    values[k] = values[k + 1].subtract(moved.getOrDefault(start.plusDays(k + 1), BigDecimal.ZERO));
                    guessed[k] = true;
                }
            }
            // In avanti: una fotografia, o il giorno prima più i movimenti del giorno.
            for (int k = last + 1; k < days; k++) {
                BigDecimal shot = shots.get(start.plusDays(k));
                if (shot != null) {
                    values[k] = shot;
                } else {
                    values[k] = values[k - 1].add(moved.getOrDefault(start.plusDays(k), BigDecimal.ZERO));
                    guessed[k] = true;
                }
            }
            LocalDate trustedFrom = anchor.trustedFrom();
            for (int k = 0; k < days; k++) {
                totals[k] = totals[k].add(values[k]);
                boolean guess = trustedFrom != null ? start.plusDays(k).isBefore(trustedFrom) : guessed[k];
                estimated[k] = estimated[k] || guess;
            }
        });
        List<Point> points = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            points.add(new Point(start.plusDays(i), totals[i], estimated[i]));
        }
        return Collections.unmodifiableList(points);
    }
}
