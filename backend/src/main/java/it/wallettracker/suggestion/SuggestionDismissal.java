package it.wallettracker.suggestion;

import java.time.Instant;

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

/** Un suggerimento rifiutato: per questo esercente, questa categoria non va più proposta (V15). */
@Entity
@Table(name = "category_suggestion_dismissal")
public class SuggestionDismissal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_key", nullable = false)
    private String merchantKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SuggestionDismissal() {
    }

    public SuggestionDismissal(String merchantKey, Category category) {
        this.merchantKey = merchantKey;
        this.category = category;
        this.createdAt = Instant.now();
    }

    public String getMerchantKey() {
        return merchantKey;
    }

    public Category getCategory() {
        return category;
    }
}
