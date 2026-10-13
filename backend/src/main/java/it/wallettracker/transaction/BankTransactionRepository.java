package it.wallettracker.transaction;

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
}
