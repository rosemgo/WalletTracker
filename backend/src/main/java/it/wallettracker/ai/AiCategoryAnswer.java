package it.wallettracker.ai;

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

/**
 * La risposta dell'IA per un esercente (V20): la categoria suggerita, o null se il modello ha detto "non so".
 * Salvata perché ogni risposta costa qualche secondo: un esercente si chiede una volta sola.
 */
@Entity
@Table(name = "ai_category_answer")
public class AiCategoryAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_key", nullable = false)
    private String merchantKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category; // null = "non so"

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AiCategoryAnswer() {
    }

    public AiCategoryAnswer(String merchantKey, Category category, String model) {
        this.merchantKey = merchantKey;
        this.category = category;
        this.model = model.length() > 100 ? model.substring(0, 100) : model; // la colonna è VARCHAR(100)
        this.createdAt = Instant.now();
    }

    public String getMerchantKey() {
        return merchantKey;
    }

    public Category getCategory() {
        return category;
    }

    public String getModel() {
        return model;
    }
}
