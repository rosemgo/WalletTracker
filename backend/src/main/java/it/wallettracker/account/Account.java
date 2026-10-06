package it.wallettracker.account;

import java.time.Instant;

import it.wallettracker.connection.BankConnection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Un conto bancario, salvato nella tabella {@code account}.
 *
 * <p>Ha due identificativi diversi, ed è importante capire perché:
 * <ul>
 *   <li>{@code externalKey} è <b>stabile</b>: identifica il conto per sempre, anche quando rinnovi
 *       il consenso. Lo usiamo per non creare due volte lo stesso conto;</li>
 *   <li>{@code providerUid} è l'uid di Enable Banking per la <b>sessione corrente</b>: serve per
 *       chiamare le API, e cambia a ogni nuovo consenso.</li>
 * </ul>
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Relazione "molti a uno": molti conti appartengono a un collegamento.
     * Nella tabella è la colonna {@code connection_id}, che punta a {@code bank_connection.id}.
     * LAZY = il collegamento viene caricato dal database solo se lo si usa davvero.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connection_id")
    private BankConnection connection;

    @Column(name = "external_key", nullable = false, unique = true)
    private String externalKey;

    @Column(name = "provider_uid", nullable = false)
    private String providerUid;

    @Column(name = "iban")
    private String iban;

    @Column(name = "name")
    private String name;

    @Column(name = "currency", nullable = false)
    private String currency;

    /** Salvato come testo ("MAIN", "SPENDING"...) e non come numero: più leggibile e robusto. */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private AccountRole role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Account() {
    }

    public Account(BankConnection connection, String externalKey, String providerUid, String iban, String name,
            String currency) {
        this.connection = connection;
        this.externalKey = externalKey;
        this.providerUid = providerUid;
        this.iban = iban;
        this.name = name;
        this.currency = currency;
        this.role = AccountRole.UNASSIGNED;
        this.createdAt = Instant.now();
    }

    /** Dopo un nuovo consenso lo stesso conto passa al nuovo collegamento e riceve un nuovo uid. */
    public void moveTo(BankConnection newConnection, String newProviderUid) {
        this.connection = newConnection;
        this.providerUid = newProviderUid;
    }

    /** Cambia il ruolo del conto (dalla dashboard, nella Fase 2). */
    public void changeRole(AccountRole newRole) {
        this.role = newRole;
    }

    public Long getId() {
        return id;
    }

    public BankConnection getConnection() {
        return connection;
    }

    public String getExternalKey() {
        return externalKey;
    }

    public String getProviderUid() {
        return providerUid;
    }

    public String getIban() {
        return iban;
    }

    public String getName() {
        return name;
    }

    public String getCurrency() {
        return currency;
    }

    public AccountRole getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
