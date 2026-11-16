package it.wallettracker.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
     * @param newSource da dove viene il saldo nuovo: si confronta solo con uno dello stesso tipo
     * @param newAt     quando è stato letto (o scritto) il saldo nuovo
     */
    @Transactional
    public void compare(Account account, BalanceSource newSource, BigDecimal newAmount, LocalDate newDate,
            Instant newAt) {
        if (account.getBalance() == null || account.getBalanceDate() == null || newAmount == null || newDate == null
                || account.getBalanceSource() != newSource || newDate.isBefore(account.getBalanceDate())) {
            return;
        }
        BalanceCheck check = new BalanceCheck(account, newSource, account.getBalanceDate(),
                account.getBalanceUpdatedAt(), account.getBalance(), newDate, newAt, newAmount, newAt);
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

    /** Il saldo nuovo meno (quello di prima più i movimenti arrivati in mezzo). */
    private BigDecimal difference(BalanceCheck check) {
        BigDecimal moved = transactionRepository.sumBetween(check.getAccount().getId(), check.getFromDate(),
                check.getFromAt(), check.getToDate(), check.getToAt());
        return check.getToAmount().subtract(check.getFromAmount()).subtract(Objects.requireNonNull(moved));
    }
}
