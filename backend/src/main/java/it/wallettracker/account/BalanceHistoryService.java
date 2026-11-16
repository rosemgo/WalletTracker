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

    /** La fotografia di oggi: il saldo di ogni conto che conta e ne ha uno. Restituisce quanti conti ha salvato. */
    @Transactional
    public int recordToday(LocalDate today) {
        int saved = 0;
        for (Account account : accountRepository.findAll()) {
            if (!counts(account)) {
                continue;
            }
            CurrentBalance balance = accountService.currentBalance(account);
            if (balance != null) {
                snapshotRepository.upsert(account.getId(), today, balance.amount());
                saved++;
            }
        }
        return saved;
    }

    /** La liquidità in {@code currency}, giorno per giorno, dall'inizio della storia (al massimo un anno) a oggi. */
    @Transactional(readOnly = true)
    public BalanceHistory history(String currency, LocalDate today) {
        String chosen = currency.toUpperCase(Locale.ROOT);
        Map<Long, BigDecimal> current = new LinkedHashMap<>();
        for (Account account : accountRepository.findAll()) {
            if (counts(account) && chosen.equals(account.getCurrency())) {
                CurrentBalance balance = accountService.currentBalance(account);
                if (balance != null) {
                    current.put(account.getId(), balance.amount());
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
        if (start.isBefore(earliest)) {
            start = earliest;
        }

        Map<Long, Map<LocalDate, BigDecimal>> photos = new HashMap<>();
        for (BalanceSnapshot s : snapshots) {
            photos.computeIfAbsent(s.getAccountId(), id -> new HashMap<>()).put(s.getDate(), s.getAmount());
        }
        Map<Long, Map<LocalDate, BigDecimal>> movements = new HashMap<>();
        for (AccountDayTotal row : transactionRepository.sumByAccountAndDay(current.keySet(), start, today)) {
            movements.computeIfAbsent(row.getAccountId(), id -> new HashMap<>()).put(row.getDay(), row.getTotal());
        }
        return new BalanceHistory(chosen, current.size(), reconstruct(start, today, current, photos, movements));
    }

    /**
     * Il calcolo, senza database (per i test). Per ogni conto si cammina all'indietro da oggi: il valore di un giorno è
     * la sua fotografia, se c'è, altrimenti il valore del giorno dopo meno i movimenti del giorno dopo. Il saldo di oggi
     * vale come fotografia di oggi (ed è più recente di quella dell'ultima ora). Prima del primo movimento di un conto il
     * suo saldo resta quindi fermo: non sappiamo niente di diverso, ed è una stima come le altre.
     *
     * @param current   il saldo di oggi di ogni conto
     * @param photos    le fotografie, per conto e per giorno
     * @param movements la somma dei movimenti, per conto e per giorno
     */
    static List<Point> reconstruct(LocalDate start, LocalDate today, Map<Long, BigDecimal> current,
            Map<Long, Map<LocalDate, BigDecimal>> photos, Map<Long, Map<LocalDate, BigDecimal>> movements) {
        int days = (int) (today.toEpochDay() - start.toEpochDay()) + 1;
        BigDecimal[] totals = new BigDecimal[days];
        boolean[] estimated = new boolean[days];
        Arrays.fill(totals, BigDecimal.ZERO);
        current.forEach((accountId, balance) -> {
            Map<LocalDate, BigDecimal> shots = photos.getOrDefault(accountId, Map.of());
            Map<LocalDate, BigDecimal> moved = movements.getOrDefault(accountId, Map.of());
            BigDecimal value = balance;
            for (int i = days - 1; i >= 0; i--) {
                LocalDate day = start.plusDays(i);
                BigDecimal shot = i == days - 1 ? balance : shots.get(day);
                if (shot != null) {
                    value = shot;
                } else {
                    estimated[i] = true;
                }
                totals[i] = totals[i].add(value);
                value = value.subtract(moved.getOrDefault(day, BigDecimal.ZERO)); // la fine del giorno prima
            }
        });
        List<Point> points = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            points.add(new Point(start.plusDays(i), totals[i], estimated[i]));
        }
        return Collections.unmodifiableList(points);
    }
}
