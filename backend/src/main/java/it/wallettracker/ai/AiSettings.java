package it.wallettracker.ai;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Le impostazioni dell'IA (V18): una sola riga, id = 1. */
@Entity
@Table(name = "ai_settings")
public class AiSettings {

    /** L'unica riga della tabella. */
    static final int ID = 1;

    @Id
    private Integer id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private AiProvider provider;

    @Column(name = "ollama_model", nullable = false)
    private String ollamaModel;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiSettings() {
    }

    public void change(AiProvider newProvider, String newOllamaModel) {
        this.provider = newProvider;
        this.ollamaModel = newOllamaModel;
        this.updatedAt = Instant.now();
    }

    public AiProvider getProvider() {
        return provider;
    }

    public String getOllamaModel() {
        return ollamaModel;
    }
}
