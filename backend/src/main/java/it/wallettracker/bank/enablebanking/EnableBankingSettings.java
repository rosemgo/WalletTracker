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
 *
 * <p>La chiave privata è salvata <b>cifrata</b> (vedi SecretCipher): questa entità contiene solo il testo
 * cifrato, mai la chiave in chiaro.
 */
@Entity
@Table(name = "enable_banking_settings")
public class EnableBankingSettings {

    static final int SINGLE_ROW_ID = 1;

    @Id
    private Integer id;

    @Column(name = "application_id", nullable = false)
    private String applicationId;

    @Column(name = "private_key_encrypted", nullable = false)
    private String privateKeyEncrypted;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EnableBankingSettings() {
    }

    public EnableBankingSettings(String applicationId, String privateKeyEncrypted) {
        this.id = SINGLE_ROW_ID;
        this.applicationId = applicationId;
        this.privateKeyEncrypted = privateKeyEncrypted;
        this.updatedAt = Instant.now();
    }

    public String getApplicationId() {
        return applicationId;
    }

    public String getPrivateKeyEncrypted() {
        return privateKeyEncrypted;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
