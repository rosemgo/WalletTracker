package it.wallettracker.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import it.wallettracker.transaction.BankTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Il controllo dei saldi (docs/saldi.md, parte N): la riconciliazione che si fa a mano con gli estratti conto, fatta
 * dall'app a ogni saldo nuovo.
 *
 * <p>Il saldo nuovo deve essere quello di prima più i movimenti arrivati in mezzo. Se torna, nell'app ci sono tutti i
 * movimenti di quel periodo: è una verifica, non un'ipotesi. Se non torna, manca un movimento o ce n'è uno in più, e
 * l'utente lo deve sapere: la coppia di saldi si salva, e finché non torna c'è un avviso con il conto, il periodo e la
 * differenza. Il controllo si rifà a ogni lettura degli avvisi: se poi il movimento mancante arriva (dalla
 * sincronizzazione o da un file), l'avviso sparisce da solo.
 *
 * <p>Si confrontano solo due saldi dello stesso tipo: due della banca, o due scritti dall'utente. Il primo saldo di un
 * conto non ha niente con cui confrontarsi.
 */
@Service
public class BalanceCheckService {

    private final BalanceCheckRepository checkRepository;
    private final BankTransactionRepository transactionRepository;

    public BalanceCheckService(BalanceCheckRepository checkRepository,
            BankTransactionRepository transactionRepository) {
        this.checkRepository = checkRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Due saldi che non tornano, adesso.
     *
     * @param difference il saldo nuovo meno (quello di prima più i movimenti in mezzo): positivo, la banca ha più soldi
     *                   di quanti ne spieghino i movimenti nell'app (manca un'entrata, o c'è un'uscita in più)
     */
    public record Mismatch(Account account, BalanceSource source, LocalDate from, LocalDate to, BigDecimal difference) {
    }

    /**
     * Confronta il saldo nuovo di un conto con quello che ha adesso, prima che il nuovo lo sostituisca. Da chiamare
     * dopo aver importato i movimenti e prima di salvare il saldo nuovo.
     *
     * @param newSource  da dove viene il saldo nuovo: si confronta solo con uno dello stesso tipo
     * @param newPending i movimenti in attesa compresi nel saldo nuovo (parte O), null se è contabile: si confrontano i
     *                   saldi contabili, perché i movimenti in mezzo sono quelli contabilizzati
     * @param newLastEntry l'ultimo movimento compreso nel saldo nuovo, se la banca lo indica (parte Q), altrimenti null
     * @param newAt      quando è stato letto (o scritto) il saldo nuovo
     */
    @Transactional
    public void compare(Account account, BalanceSource newSource, BigDecimal newAmount, BigDecimal newPending,
            String newLastEntry, LocalDate newDate, Instant newAt) {
        if (account.getBalance() == null || account.getBalanceDate() == null || newAmount == null || newDate == null
                || account.getBalanceSource() != newSource || newDate.isBefore(account.getBalanceDate())) {
            return;
        }
        if (!account.isPendingKnown()) {
            // Il saldo di prima è stato salvato prima della V46: non si sa se comprendeva i movimenti in attesa, e il
            // confronto potrebbe segnalare una differenza che non c'è (parte P). Si controlla dal prossimo.
            return;
        }
        BigDecimal newBooked = newPending == null ? newAmount : newAmount.subtract(newPending);
        BalanceCheck check = new BalanceCheck(account, newSource, account.getBalanceDate(),
                account.getBalanceUpdatedAt(), account.getBookedBalance(), account.getBalanceLastEntry(), newDate,
                newAt, newBooked, newLastEntry, newAt);
        if (difference(check).signum() != 0) {
            checkRepository.save(check);
        }
    }

    /** I saldi che non tornano adesso, dal più vecchio. I conti esclusi non interessano. */
    @Transactional(readOnly = true)
    public List<Mismatch> mismatches() {
        return checkRepository.findAllWithAccount().stream()
                .filter(check -> check.getAccount().getRole() != AccountRole.EXCLUDED)
                .map(check -> new Mismatch(check.getAccount(), check.getSource(), check.getFromDate(),
                        check.getToDate(), difference(check)))
                .filter(mismatch -> mismatch.difference().signum() != 0)
                .toList();
    }

    /**
     * Quanti movimenti in ritardo si provano a spiegare (parte Q): oltre, si controlla solo se li spiegano tutti insieme.
     * 2^12 combinazioni sono poche; di solito i movimenti in ritardo sono uno o due.
     */
    static final int MAX_LATE = 12;

    /**
     * Il saldo nuovo meno (quello di prima più i movimenti arrivati in mezzo); zero se la differenza si spiega con dei
     * movimenti arrivati in ritardo che la banca aveva già contato (parte Q).
     */
    private BigDecimal difference(BalanceCheck check) {
        Long accountId = check.getAccount().getId();
        String fromKey = Account.insideKey(check.getFromLastEntry());
        String toKey = Account.insideKey(check.getToLastEntry());
        BigDecimal moved = transactionRepository.sumBetween(accountId, check.getFromDate(), check.getFromAt(), fromKey,
                check.getToDate(), check.getToAt(), toKey);
        BigDecimal difference = check.getToAmount().subtract(check.getFromAmount())
                .subtract(Objects.requireNonNull(moved));
        if (difference.signum() == 0) {
            return difference;
        }
        // Un movimento del giorno del primo saldo arrivato dopo la sua lettura si è contato in mezzo: se la banca lo aveva
        // già nel primo saldo, la differenza è il suo importo con il segno cambiato. Uno del giorno del secondo saldo
        // arrivato dopo la sua lettura non si è contato: se la banca lo aveva già nel secondo, la differenza è il suo
        // importo. Se una combinazione di questi spiega tutta la differenza, non manca niente.
        List<BigDecimal> candidates = new ArrayList<>();
        transactionRepository.lateOnDay(accountId, check.getFromDate(), check.getFromAt(), fromKey)
                .forEach(amount -> candidates.add(amount.negate()));
        candidates.addAll(transactionRepository.lateOnDay(accountId, check.getToDate(), check.getToAt(), toKey));
        return explains(candidates, difference) ? BigDecimal.ZERO : difference;
    }

    /** true se la somma di alcuni dei {@code candidates} (almeno uno) fa esattamente {@code difference}. */
    static boolean explains(List<BigDecimal> candidates, BigDecimal difference) {
        if (candidates.isEmpty()) {
            return false;
        }
        if (candidates.size() > MAX_LATE) {
            return candidates.stream().reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(difference) == 0;
        }
        int n = candidates.size();
        for (int mask = 1; mask < (1 << n); mask++) {
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    sum = sum.add(candidates.get(i));
                }
            }
            if (sum.compareTo(difference) == 0) {
                return true;
            }
        }
        return false;
    }
}
