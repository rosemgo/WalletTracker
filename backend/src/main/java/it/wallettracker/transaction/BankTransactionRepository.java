package it.wallettracker.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import it.wallettracker.account.Account;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accesso alla tabella {@code bank_transaction}. */
public interface BankTransactionRepository extends JpaRepository<BankTransaction, Long> {

    boolean existsByAccountAndDedupKey(Account account, String dedupKey);

    /** Le impronte di tutti i movimenti di un conto, in una query (per l'importazione CSV). */
    @Query("SELECT t.dedupKey FROM BankTransaction t WHERE t.account = :account")
    Set<String> findDedupKeysByAccount(@Param("account") Account account);

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
     * Cancella tutti i movimenti di un conto (quando si elimina un conto manuale); restituisce quanti.
     * È una query sola ({@code @Modifying}): un metodo derivato "deleteBy..." caricherebbe prima ogni
     * movimento e poi lo cancellerebbe uno alla volta, migliaia di query per un file grande.
     */
    @Modifying
    @Query("DELETE FROM BankTransaction t WHERE t.account = :account")
    int deleteByAccount(@Param("account") Account account);

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
     *
     * <p>{@code LEFT JOIN FETCH}: carica le categorie insieme ai movimenti, nella stessa query, perché la
     * dashboard ne mostra il nome. Senza, Hibernate farebbe una query in più per ogni categoria (il problema
     * N+1, docs/09). La query di conteggio delle pagine ({@code countQuery}) Spring Data saprebbe ricavarla
     * da sola, ma la scriviamo noi: per contare i movimenti le categorie non servono, quindi niente join.
     */
    @Query(value = """
            SELECT t FROM BankTransaction t
            LEFT JOIN FETCH t.category
            LEFT JOIN FETCH t.manualCategory
            WHERE (:accountId IS NULL OR t.account.id = :accountId)
              AND (:type IS NULL OR t.type = :type)
            ORDER BY t.bookingDate DESC, t.id DESC
            """,
            countQuery = """
            SELECT COUNT(t) FROM BankTransaction t
            WHERE (:accountId IS NULL OR t.account.id = :accountId)
              AND (:type IS NULL OR t.type = :type)
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
    @Query("SELECT t.category.id AS categoryId, COUNT(t) AS uses FROM BankTransaction t WHERE t.category IS NOT NULL GROUP BY t.category.id")
    List<CategoryUsage> countUsesByCategory();

    /** Una riga di {@link #countUsesByCategory()}. */
    interface CategoryUsage {
        Long getCategoryId();

        long getUses();
    }

    /**
     * Le spese di un periodo per valuta e categoria, già sommate (vedi {@link #sumByMonthAndType}).
     * Solo i movimenti di tipo {@code EXPENSE}: un rimborso segnato come spesa (importo positivo) riduce
     * il totale della sua categoria. Id e nome della categoria sono null per le spese senza categoria.
     *
     * <p>{@code LEFT JOIN t.category c}: per avere il nome serve la tabella {@code category}. "LEFT" vuol dire
     * "anche i movimenti senza categoria" (con un JOIN normale sparirebbero dal risultato).
     */
    @Query("""
            SELECT t.currency AS currency, c.id AS categoryId, c.name AS category,
                   SUM(t.amount) AS total, COUNT(t) AS movements
            FROM BankTransaction t LEFT JOIN t.category c
            WHERE t.type = it.wallettracker.transaction.TransactionType.EXPENSE
              AND t.bookingDate >= :from AND t.bookingDate < :to
            GROUP BY t.currency, c.id, c.name
            """)
    List<CategoryTotal> sumExpensesByCategory(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Una riga di {@link #sumExpensesByCategory}. */
    interface CategoryTotal {
        String getCurrency();

        Long getCategoryId();

        String getCategory();

        BigDecimal getTotal();

        long getMovements();
    }
}
