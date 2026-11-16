package it.wallettracker.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Le fotografie dei saldi (docs/saldi.md, parte L). */
public interface BalanceSnapshotRepository extends JpaRepository<BalanceSnapshot, BalanceSnapshot.Key> {

    /** Salva il saldo di un conto in un giorno; se quel giorno c'era già, lo sostituisce (l'ultimo vince). */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO balance_snapshot (account_id, balance_date, amount) VALUES (:accountId, :date, :amount)
            ON CONFLICT (account_id, balance_date) DO UPDATE SET amount = EXCLUDED.amount
            """, nativeQuery = true)
    void upsert(@Param("accountId") Long accountId, @Param("date") LocalDate date,
            @Param("amount") BigDecimal amount);

    /** Le fotografie dei conti {@code ids} dal giorno {@code from} al giorno {@code to}, compresi. */
    @Query("""
            SELECT s FROM BalanceSnapshot s
            WHERE s.key.accountId IN :ids AND s.key.balanceDate >= :from AND s.key.balanceDate <= :to
            """)
    List<BalanceSnapshot> findBetween(@Param("ids") Collection<Long> ids, @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
