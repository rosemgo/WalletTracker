package it.wallettracker.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

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
 * Due saldi di un conto, uno dopo l'altro, che non tornano con i movimenti arrivati in mezzo (docs/saldi.md, parte N).
 * Si salva solo quando non tornano; il controllo si rifà a ogni lettura degli avvisi ({@link BalanceCheckService}).
 */
@Entity
@Table(name = "balance_check")
public class BalanceCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id")
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BalanceSource source;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "from_at")
    private Instant fromAt;

    @Column(name = "from_amount", nullable = false)
    private BigDecimal fromAmount;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(name = "to_at", nullable = false)
    private Instant toAt;

    @Column(name = "to_amount", nullable = false)
    private BigDecimal toAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BalanceCheck() {
        // per JPA
    }

    BalanceCheck(Account account, BalanceSource source, LocalDate fromDate, Instant fromAt, BigDecimal fromAmount,
            LocalDate toDate, Instant toAt, BigDecimal toAmount, Instant createdAt) {
        this.account = account;
        this.source = source;
        this.fromDate = fromDate;
        this.fromAt = fromAt;
        this.fromAmount = fromAmount;
        this.toDate = toDate;
        this.toAt = toAt;
        this.toAmount = toAmount;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Account getAccount() {
        return account;
    }

    public BalanceSource getSource() {
        return source;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public Instant getFromAt() {
        return fromAt;
    }

    public BigDecimal getFromAmount() {
        return fromAmount;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public Instant getToAt() {
        return toAt;
    }

    public BigDecimal getToAmount() {
        return toAmount;
    }
}
