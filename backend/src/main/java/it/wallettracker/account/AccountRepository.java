package it.wallettracker.account;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import it.wallettracker.connection.BankConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Accesso alla tabella {@code account}. Le query sono ricavate dai nomi dei metodi (vedi BankConnectionRepository). */
public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByExternalKey(String externalKey);

    List<Account> findByConnectionOrderByName(BankConnection connection);

    boolean existsByConnection(BankConnection connection);

    /**
     * Tutti i conti insieme al loro collegamento, con <b>una sola</b> query.
     *
     * <p>{@code JOIN FETCH} dice a Hibernate di caricare subito anche il collegamento (che altrimenti è LAZY).
     * Senza, leggendo la banca di ogni conto Hibernate farebbe una query in più per ogni collegamento:
     * è il famoso problema "N+1 query".
     */
    @Query("SELECT a FROM Account a JOIN FETCH a.connection c ORDER BY c.aspspName, a.name")
    List<Account> findAllWithConnection();

    /**
     * Salva l'esito dell'ultima importazione di un conto.
     *
     * <p>Qui il nome del metodo non basta: scriviamo noi la query, in JPQL (simile a SQL, ma usa i nomi
     * delle classi e dei campi Java invece di tabelle e colonne). Aggiorna <b>solo</b> queste due colonne:
     * se nel frattempo qualcuno ha cambiato il ruolo del conto (dalla dashboard), non lo sovrascriviamo.
     *
     * <p>{@code @Modifying} dice a Spring Data che la query modifica i dati (non è una SELECT);
     * {@code @Transactional} serve perché ogni modifica deve avvenire dentro una transazione.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Account a SET a.lastSyncAt = :at, a.lastSyncError = :error WHERE a.id = :id")
    void recordSync(@Param("id") Long id, @Param("at") Instant at, @Param("error") String error);
}
