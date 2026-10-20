package it.wallettracker.account;

import java.time.Instant;
import java.util.UUID;

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
    /** Il collegamento con la banca; null per un conto manuale (V14). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connection_id")
    private BankConnection connection;

    @Column(name = "external_key", nullable = false, unique = true)
    private String externalKey;

    @Column(name = "provider_uid")
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

    /** Quando è stata tentata l'ultima importazione (riuscita o no); null se mai. */
    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    /** Il motivo dell'ultimo errore di importazione; null se l'ultima è andata bene. */
    @Column(name = "last_sync_error")
    private String lastSyncError;

    /** Da dove arrivano i movimenti: Enable Banking o file CSV (V14). */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private AccountSource source;

    /** Il nome dell'istituto, solo per i conti manuali (es. "Trade Republic"). */
    @Column(name = "bank_name")
    private String bankName;

    /** Il nome scelto dall'utente (V17); null = nessuno, si usa {@link #label()}. */
    @Column(name = "display_name")
    private String displayName;

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
        this.source = AccountSource.ENABLE_BANKING;
    }

    /**
     * Un conto manuale: i suoi movimenti arrivano da file CSV. La chiave stabile è casuale (non c'è niente
     * della banca da cui ricavarla).
     */
    public static Account manual(String bankName, String name, String iban, String currency, AccountRole role) {
        Account account = new Account();
        account.externalKey = "manual:" + UUID.randomUUID();
        account.bankName = bankName;
        account.name = name;
        account.iban = iban;
        account.currency = currency;
        account.role = role;
        account.createdAt = Instant.now();
        account.source = AccountSource.MANUAL;
        return account;
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

    /** Il nome scelto dall'utente; vuoto o null lo toglie (torna l'etichetta automatica). */
    public void rename(String newDisplayName) {
        this.displayName = newDisplayName == null || newDisplayName.isBlank() ? null : newDisplayName.trim();
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Come mostrare il conto nella dashboard, in quest'ordine:
     * <ol>
     *   <li>il nome scelto dall'utente ("Conto spese");</li>
     *   <li>per un conto manuale, banca e nome (il nome l'ha scelto l'utente creandolo: "Trade Republic · Conto titoli");</li>
     *   <li>banca e ultime 4 cifre dell'IBAN ("ING ··4521"): il nome che manda la banca spesso è il titolare,
     *       uguale per tutti i suoi conti;</li>
     *   <li>senza IBAN (es. una carta), banca e nome della banca.</li>
     * </ol>
     */
    public String label() {
        if (displayName != null) {
            return displayName;
        }
        String bank = getBankName();
        if (!isManual() && iban != null && iban.length() >= 4) {
            return bank + " ··" + iban.substring(iban.length() - 4);
        }
        return name != null && !name.isBlank() ? bank + " · " + name : bank;
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

    public Instant getLastSyncAt() {
        return lastSyncAt;
    }

    public String getLastSyncError() {
        return lastSyncError;
    }

    public AccountSource getSource() {
        return source;
    }

    public boolean isManual() {
        return source == AccountSource.MANUAL;
    }

    /** Il nome della banca o dell'istituto, per entrambi i tipi di conto. Va letto dentro una transazione. */
    public String getBankName() {
        return connection != null ? connection.getAspspName() : bankName;
    }
}
