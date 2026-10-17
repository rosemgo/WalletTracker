package it.wallettracker.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import it.wallettracker.account.Account;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accesso alla tabella {@code bank_transaction}. */
public interface BankTransactionRepository extends JpaRepository<BankTransaction, Long> {

    boolean existsByAccountAndDedupKey(Account account, String dedupKey);

    /** Cancella i movimenti di un conto in un certo stato; restituisce quanti ne ha cancellati. */
    long deleteByAccountAndStatus(Account account, TransactionStatus status);

    /** L'ultimo movimento contabilizzato di un conto: da qui riparte la prossima importazione. */
    Optional<BankTransaction> findFirstByAccountAndStatusOrderByBookingDateDesc(Account account,
            TransactionStatus status);

    List<BankTransaction> findTop15ByAccountOrderByBookingDateDescIdDesc(Account account);

    /** I movimenti di un tipo da una certa data in poi (per cercare gli abbonamenti tra le spese). */
    List<BankTransaction> findByTypeAndBookingDateGreaterThanEqual(TransactionType type, LocalDate from);

    long countByAccount(Account account);

    /**
     * Quante volte è stata usata ogni regola: una riga per regola, con l'id della regola e il numero di movimenti.
     *
     * <p>Il risultato è una <b>proiezione</b>: invece di entità intere, Spring Data riempie l'interfaccia
     * {@link RuleUsage} con le colonne della query, abbinate per nome ({@code AS ruleId}, {@code AS uses}).
     */
    @Query("SELECT t.ruleId AS ruleId, COUNT(t) AS uses FROM BankTransaction t WHERE t.ruleId IS NOT NULL GROUP BY t.ruleId")
    List<RuleUsage> countUsesByRule();

    /** Una riga di {@link #countUsesByRule()}. */
    interface RuleUsage {
        Long getRuleId();

        long getUses();
    }

    /**
     * I movimenti dal più recente, con due filtri facoltativi.
     *
     * <p>Il trucco {@code (:accountId IS NULL OR ...)}: se il parametro è null la condizione è sempre vera,
     * cioè il filtro è "spento". Così una sola query copre tutte le combinazioni di filtri.
     *
     * <p>Il parametro {@link Pageable} fa aggiungere a Spring Data {@code LIMIT} e {@code OFFSET}, e la
     * query di conteggio per il totale. La {@code """...""" } è un "text block" di Java: una stringa su
     * più righe.
     */
    @Query("""
            SELECT t FROM BankTransaction t
            WHERE (:accountId IS NULL OR t.account.id = :accountId)
              AND (:type IS NULL OR t.type = :type)
            ORDER BY t.bookingDate DESC, t.id DESC
            """)
    Page<BankTransaction> search(@Param("accountId") Long accountId, @Param("type") TransactionType type,
            Pageable pageable);

    /**
     * I totali dei movimenti di un periodo, raggruppati per mese, valuta e tipo: una riga per ogni
     * combinazione (es. "ottobre 2026, EUR, EXPENSE, -1234.56, 42 movimenti").
     *
     * <p>La somma la fa il database ({@code SUM} con {@code GROUP BY}): invece di caricare in Java migliaia
     * di movimenti per sommarli, riceviamo poche decine di righe già pronte. {@code extract(year from ...)}
     * estrae l'anno da una data. Il periodo va da {@code from} (incluso) a {@code to} (escluso).
     */
    @Query("""
            SELECT extract(year from t.bookingDate) AS bookingYear,
                   extract(month from t.bookingDate) AS bookingMonth,
                   t.currency AS currency, t.type AS type, SUM(t.amount) AS total, COUNT(t) AS movements
            FROM BankTransaction t
            WHERE t.bookingDate >= :from AND t.bookingDate < :to
            GROUP BY extract(year from t.bookingDate), extract(month from t.bookingDate), t.currency, t.type
            """)
    List<MonthlyTotal> sumByMonthAndType(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Una riga di {@link #sumByMonthAndType}. */
    interface MonthlyTotal {
        int getBookingYear();

        int getBookingMonth();

        String getCurrency();

        TransactionType getType(); // null se il movimento non è ancora stato classificato

        BigDecimal getTotal();

        long getMovements();
    }

    /** Quanti movimenti ha ogni categoria (vedi {@link #countUsesByRule()} per le proiezioni). */
    @Query("SELECT t.category AS category, COUNT(t) AS uses FROM BankTransaction t WHERE t.category IS NOT NULL GROUP BY t.category")
    List<CategoryUsage> countUsesByCategory();

    /** Una riga di {@link #countUsesByCategory()}. */
    interface CategoryUsage {
        String getCategory();

        long getUses();
    }

    /**
     * Le spese di un periodo per valuta e categoria, già sommate (vedi {@link #sumByMonthAndType}).
     * Solo i movimenti di tipo {@code EXPENSE}: un rimborso segnato come spesa (importo positivo) riduce
     * il totale della sua categoria. La categoria è null per le spese senza categoria.
     */
    @Query("""
            SELECT t.currency AS currency, t.category AS category, SUM(t.amount) AS total, COUNT(t) AS movements
            FROM BankTransaction t
            WHERE t.type = it.wallettracker.transaction.TransactionType.EXPENSE
              AND t.bookingDate >= :from AND t.bookingDate < :to
            GROUP BY t.currency, t.category
            """)
    List<CategoryTotal> sumExpensesByCategory(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Una riga di {@link #sumExpensesByCategory}. */
    interface CategoryTotal {
        String getCurrency();

        String getCategory();

        BigDecimal getTotal();

        long getMovements();
    }
}
