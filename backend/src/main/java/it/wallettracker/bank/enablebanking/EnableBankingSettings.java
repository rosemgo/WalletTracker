package it.wallettracker.bank.enablebanking;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Le credenziali di Enable Banking salvate dalla dashboard (tabella {@code enable_banking_settings}).
 *
 * <p>La tabella ha una sola riga, con id = 1: per questo l'id non è generato dal database ma è sempre
 * {@link #SINGLE_ROW_ID}.
 */
@Entity
@Table(name = "enable_banking_settings")
public class EnableBankingSettings {

    static final int SINGLE_ROW_ID = 1;

    @Id
    private Integer id;

    @Column(name = "application_id", nullable = false)
    private String applicationId;

    @Column(name = "private_key_pem", nullable = false)
    private String privateKeyPem;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EnableBankingSettings() {
    }

    public EnableBankingSettings(String applicationId, String privateKeyPem) {
        this.id = SINGLE_ROW_ID;
        this.applicationId = applicationId;
        this.privateKeyPem = privateKeyPem;
        this.updatedAt = Instant.now();
    }

    public String getApplicationId() {
        return applicationId;
    }

    public String getPrivateKeyPem() {
        return privateKeyPem;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
