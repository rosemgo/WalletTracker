package it.wallettracker.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import it.wallettracker.account.Account;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Accesso alla tabella {@code bank_transaction}.
 *
 * <p>{@link JpaSpecificationExecutor} aggiunge {@code findAll(Specification, Pageable)}: l'elenco dei movimenti con i
 * filtri e la ricerca, costruiti in Java da {@link TransactionSearch} (docs/32, parte G).
 */
public interface BankTransactionRepository extends JpaRepository<BankTransaction, Long>,
        JpaSpecificationExecutor<BankTransaction> {

    boolean existsByAccountAndDedupKey(Account account, String dedupKey);

    /** Le impronte di tutti i movimenti di un conto, in una query (per l'importazione CSV). */
    @Query("SELECT t.dedupKey FROM BankTransaction t WHERE t.account = :account")
    Set<String> findDedupKeysByAccount(@Param("account") Account account);

    /**
     * I movimenti con una categoria scelta a mano, insieme alla categoria (JOIN FETCH: una query sola).
     * Sono la "storia" da cui nascono i suggerimenti (passo 7a). Il JOIN, senza LEFT, salta quelli senza.
     */
    @Query("SELECT t FROM BankTransaction t JOIN FETCH t.manualCategory")
    List<BankTransaction> findAllWithManualCategory();

    /** I movimenti con una categoria (scelta a mano o messa da una regola), insieme alla categoria. */
    @Query("SELECT t FROM BankTransaction t JOIN FETCH t.category")
    List<BankTransaction> findAllWithCategory();

    /** I movimenti senza categoria, di questi tipi: quelli a cui si può suggerire una categoria. */
    @Query("SELECT t FROM BankTransaction t WHERE t.category IS NULL AND t.type IN :types")
    List<BankTransaction> findUncategorized(@Param("types") Collection<TransactionType> types);

    /** Cancella i movimenti di un conto in un certo stato; restituisce quanti ne ha cancellati. */
    long deleteByAccountAndStatus(Account account, TransactionStatus status);

    /** L'ultimo movimento contabilizzato di un conto: da qui riparte la prossima importazione. */
    Optional<BankTransaction> findFirstByAccountAndStatusOrderByBookingDateDesc(Account account,
            TransactionStatus status);

    List<BankTransaction> findTop15ByAccountOrderByBookingDateDescIdDesc(Account account);

    /**
     * Il primo giorno dei movimenti di un conto arrivati dalla <b>banca</b>, cioè senza quelli aggiunti da un file
     * (impronta che inizia con {@code filePrefix}). Prima di quel giorno la banca non ha dato niente (docs/31).
     */
    @Query("SELECT MIN(t.bookingDate) FROM BankTransaction t WHERE t.account = :account AND t.status = :status"
            + " AND t.dedupKey NOT LIKE :filePrefix")
    LocalDate findFirstBankDate(@Param("account") Account account, @Param("status") TransactionStatus status,
            @Param("filePrefix") String filePrefix);

    /**
     * I movimenti aggiunti da un file (impronta che inizia con {@code filePrefix}) con questo importo e valuta, in un
     * periodo: per riconoscerli quando la banca li manda (TransactionImportService, docs/31).
     */
    @Query("SELECT t FROM BankTransaction t WHERE t.account = :account AND t.dedupKey LIKE :filePrefix"
            + " AND t.amount = :amount AND t.currency = :currency AND t.bookingDate BETWEEN :from AND :to"
            + " ORDER BY t.id")
    List<BankTransaction> findFileMovements(@Param("account") Account account, @Param("filePrefix") String filePrefix,
            @Param("amount") BigDecimal amount, @Param("currency") String currency, @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** I movimenti di un conto in uno stato e in un periodo, in ordine (per completarli da un file, docs/31). */
    List<BankTransaction> findByAccountAndStatusAndBookingDateBetweenOrderByBookingDateAscIdAsc(Account account,
            TransactionStatus status, LocalDate from, LocalDate to);

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

    /**
     * Le spese e le entrate di un periodo, giorno per giorno: una riga per giorno, valuta, tipo e categoria (es.
     * "5 ottobre 2026, EUR, EXPENSE, Spesa alimentare, -120.00, 2 movimenti"). Il Riepilogo (docs/36) ne ricava la
     * curva del mese e quella del "mese tipico". Anche qui somma il database: per sette mesi sono poche centinaia di
     * righe, qualunque sia il numero di movimenti. Il periodo va da {@code from} (incluso) a {@code to} (escluso).
     */
    @Query("""
            SELECT t.bookingDate AS day, t.currency AS currency, t.type AS type,
                   c.id AS categoryId, c.name AS category, SUM(t.amount) AS total, COUNT(t) AS movements
            FROM BankTransaction t LEFT JOIN t.category c
            WHERE t.type IN (it.wallettracker.transaction.TransactionType.EXPENSE,
                             it.wallettracker.transaction.TransactionType.INCOME)
              AND t.bookingDate >= :from AND t.bookingDate < :to
            GROUP BY t.bookingDate, t.currency, t.type, c.id, c.name
            """)
    List<DailyTotal> sumByDay(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Una riga di {@link #sumByDay}. */
    interface DailyTotal {
        LocalDate getDay();

        String getCurrency();

        TransactionType getType();

        Long getCategoryId(); // null per le spese senza categoria

        String getCategory();

        BigDecimal getTotal();

        long getMovements();
    }

    /** Il giorno del movimento più vecchio in una valuta: da lì comincia la storia che conosciamo. */
    @Query("SELECT MIN(t.bookingDate) FROM BankTransaction t WHERE t.currency = :currency")
    LocalDate findFirstDate(@Param("currency") String currency);

    /**
     * Quanti movimenti sono da verificare: quelli di tipo TO_REVIEW e quelli non ancora classificati (tipo null).
     * Spring Data ricava la query dal nome del metodo: "count", "by type", "or type is null".
     */
    long countByTypeOrTypeIsNull(TransactionType type);

    /**
     * I movimenti da un giorno in poi, con la categoria già caricata (un JOIN FETCH, niente query per ognuno): il
     * dettaglio di un movimento ci cerca gli altri movimenti dello stesso esercente (docs/37).
     */
    @Query("SELECT t FROM BankTransaction t LEFT JOIN FETCH t.category WHERE t.bookingDate >= :from")
    List<BankTransaction> findSinceWithCategory(@Param("from") LocalDate from);
}
