package it.wallettracker.connection;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Accesso alla tabella {@code bank_connection}.
 *
 * <p>È solo un'interfaccia: l'implementazione la genera Spring Data JPA all'avvio.
 * {@link JpaRepository} fornisce già {@code save}, {@code findById}, {@code findAll}, {@code delete}...
 * Per le query in più basta dichiarare un metodo con un nome che segue le convenzioni:
 * da {@code findByValidUntilAfterOrderByAspspName} Spring ricava da solo la query
 * {@code SELECT ... WHERE valid_until > ? ORDER BY aspsp_name}.
 */
public interface BankConnectionRepository extends JpaRepository<BankConnection, Long> {

    List<BankConnection> findByValidUntilAfterOrderByAspspName(Instant instant);

    List<BankConnection> findByAspspNameAndAspspCountry(String aspspName, String aspspCountry);
}
