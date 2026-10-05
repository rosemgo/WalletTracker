package it.wallettracker.connection;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Un collegamento a una banca: la sessione autorizzata su Enable Banking, valida fino a {@code validUntil}.
 *
 * <p>Questa è un'<b>entità JPA</b>: ogni oggetto corrisponde a una riga della tabella
 * {@code bank_connection} (creata da Flyway nel file {@code V1__connessioni_e_conti.sql}).
 * Hibernate legge e scrive le righe al posto nostro, così nel codice lavoriamo con oggetti Java.
 *
 * <p>A differenza dei record usati per i DTO, un'entità è una classe "normale": Hibernate ha
 * bisogno di un costruttore senza argomenti e di poter assegnare i campi dopo averla creata.
 */
@Entity
@Table(name = "bank_connection")
public class BankConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // l'id lo assegna il database (IDENTITY)
    private Long id;

    @Column(name = "aspsp_name", nullable = false)
    private String aspspName;

    @Column(name = "aspsp_country", nullable = false)
    private String aspspCountry;

    @Column(name = "session_id", nullable = false, unique = true)
    private String sessionId;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Richiesto da JPA: Hibernate crea l'oggetto vuoto e poi lo riempie con i dati della riga. */
    protected BankConnection() {
    }

    public BankConnection(String aspspName, String aspspCountry, String sessionId, Instant validUntil) {
        this.aspspName = aspspName;
        this.aspspCountry = aspspCountry;
        this.sessionId = sessionId;
        this.validUntil = validUntil;
        this.createdAt = Instant.now();
    }

    public boolean isValid() {
        return validUntil.isAfter(Instant.now());
    }

    public Long getId() {
        return id;
    }

    public String getAspspName() {
        return aspspName;
    }

    public String getAspspCountry() {
        return aspspCountry;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
