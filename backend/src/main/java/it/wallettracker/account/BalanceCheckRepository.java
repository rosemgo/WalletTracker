package it.wallettracker.account;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** I controlli dei saldi che non tornavano (docs/saldi.md, parte N). */
public interface BalanceCheckRepository extends JpaRepository<BalanceCheck, Long> {

    /** Tutti, con il loro conto e il suo collegamento (per l'avviso), dal più vecchio. */
    @Query("SELECT c FROM BalanceCheck c JOIN FETCH c.account a LEFT JOIN FETCH a.connection ORDER BY c.toDate, c.id")
    List<BalanceCheck> findAllWithAccount();
}
