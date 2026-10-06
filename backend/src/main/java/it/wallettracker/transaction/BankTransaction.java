package it.wallettracker.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import it.wallettracker.account.Account;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Un movimento bancario, salvato nella tabella {@code bank_transaction} (migrazione V3).
 *
 * <p>È il nostro modello, non quello di Enable Banking: l'importo ha già il segno, le date sono
 * già scelte, controparte e causale sono già testo leggibile. Il JSON originale resta in
 * {@code rawJson} per poterlo rianalizzare quando miglioreremo le regole.
 */
@Entity
@Table(name = "bank_transaction")
public class BankTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(name = "dedup_key", nullable = false)
    private String dedupKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TransactionStatus status;

    @Column(name = "booking_date", nullable = false)
    private LocalDate bookingDate;

    @Column(name = "value_date")
    private LocalDate valueDate;

    /** BigDecimal ↔ NUMERIC(19,2): decimali esatti. precision/scale devono corrispondere alla colonna. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "counterparty")
    private String counterparty;

    @Column(name = "description")
    private String description;

    /** Nel database è JSONB: PostgreSQL lo salva come JSON vero e permette di interrogarlo con SQL. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_json", nullable = false)
    private String rawJson;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    protected BankTransaction() {
    }

    public BankTransaction(Account account, String dedupKey, TransactionStatus status, LocalDate bookingDate,
            LocalDate valueDate, BigDecimal amount, String currency, String counterparty, String description,
            String rawJson) {
        this.account = account;
        this.dedupKey = dedupKey;
        this.status = status;
        this.bookingDate = bookingDate;
        this.valueDate = valueDate;
        this.amount = amount;
        this.currency = currency;
        this.counterparty = counterparty;
        this.description = description;
        this.rawJson = rawJson;
        this.importedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Account getAccount() {
        return account;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public LocalDate getBookingDate() {
        return bookingDate;
    }

    public LocalDate getValueDate() {
        return valueDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getCounterparty() {
        return counterparty;
    }

    public String getDescription() {
        return description;
    }

    public String getRawJson() {
        return rawJson;
    }

    public Instant getImportedAt() {
        return importedAt;
    }
}
