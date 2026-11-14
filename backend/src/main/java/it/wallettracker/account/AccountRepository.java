package it.wallettracker.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import it.wallettracker.connection.BankConnection;
import it.wallettracker.message.Message;
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
     *
     * <p>Un {@code JOIN FETCH} dice a Hibernate: "Fai una sola grande query SQL con una JOIN interna. Prendi tutti i dati degli Account e, nello stesso identico momento, riempi anche i dati delle Connection associate".
     */
    @Query("SELECT a FROM Account a LEFT JOIN FETCH a.connection c ORDER BY COALESCE(c.aspspName, a.bankName), a.name")
    // LEFT: anche i conti manuali (V14), che non hanno un collegamento; COALESCE ordina per banca o istituto.
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
    void recordSync(@Param("id") Long id, @Param("at") Instant at, @Param("error") Message error);

    /**
     * Salva il saldo dato dalla banca (V34), come {@link #recordSync}: solo queste colonne, in una transazione sua.
     * Con {@code balance} null il saldo si toglie (la banca non ne dà più uno).
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE Account a SET a.balance = :balance, a.balanceDate = :date, a.balanceSource = :source,
                a.balanceUpdatedAt = :at
            WHERE a.id = :id
            """)
    void recordBalance(@Param("id") Long id, @Param("balance") BigDecimal balance, @Param("date") LocalDate date,
            @Param("source") BalanceSource source, @Param("at") Instant at);

    /** Salva il plafond disponibile di una carta (V35), come {@link #recordBalance}; null lo toglie. */
    @Modifying
    @Transactional
    @Query("UPDATE Account a SET a.availableCredit = :amount, a.availableCreditDate = :date WHERE a.id = :id")
    void recordAvailableCredit(@Param("id") Long id, @Param("amount") BigDecimal amount,
            @Param("date") LocalDate date);
}
