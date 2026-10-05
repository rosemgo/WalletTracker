package it.wallettracker.account;

import java.util.List;
import java.util.Optional;

import it.wallettracker.connection.BankConnection;
import org.springframework.data.jpa.repository.JpaRepository;

/** Accesso alla tabella {@code account}. Le query sono ricavate dai nomi dei metodi (vedi BankConnectionRepository). */
public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByExternalKey(String externalKey);

    List<Account> findByConnectionOrderByName(BankConnection connection);

    boolean existsByConnection(BankConnection connection);
}
