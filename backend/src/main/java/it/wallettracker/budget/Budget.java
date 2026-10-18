package it.wallettracker.budget;

import java.math.BigDecimal;

import it.wallettracker.category.Category;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Un budget mensile: quanto al massimo vuoi spendere in una categoria ogni mese (tabella {@code budget}, V10). */
@Entity
@Table(name = "budget")
public class Budget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** La categoria del budget: un riferimento alla tabella {@code category} (colonna category_id, V13). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "monthly_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal monthlyAmount;

    protected Budget() {
    }

    public Budget(Category category, String currency, BigDecimal monthlyAmount) {
        this.category = category;
        this.currency = currency;
        this.monthlyAmount = monthlyAmount;
    }

    public void update(Category category, String currency, BigDecimal monthlyAmount) {
        this.category = category;
        this.currency = currency;
        this.monthlyAmount = monthlyAmount;
    }

    public Long getId() {
        return id;
    }

    public Category getCategory() {
        return category;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getMonthlyAmount() {
        return monthlyAmount;
    }
}
