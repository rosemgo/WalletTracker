package it.wallettracker.ai;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Le impostazioni dell'IA (V18, V22, V23): una sola riga, id = 1.
 *
 * <p>Le chiavi dei servizi cloud sono salvate <b>cifrate</b> (SecretCipher): questa classe le tiene così come sono
 * nel database ("v1:..."), senza mai vederle in chiaro. Le cifra e decifra AiService.
 */
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

    @Column(name = "gemini_api_key")
    private String geminiApiKey; // cifrata

    @Column(name = "gemini_model", nullable = false)
    private String geminiModel;

    @Column(name = "gemini_consent_at")
    private Instant geminiConsentAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiSettings() {
    }

    /** Cambia solo il fornitore e il modello di Ollama (i test e la pagina di prima lo usano così). */
    public void change(AiProvider newProvider, String newOllamaModel) {
        this.provider = newProvider;
        this.ollamaModel = newOllamaModel;
        this.updatedAt = Instant.now();
    }

    /** Cambia i modelli dei due fornitori (ognuno resta salvato anche quando non è quello scelto). */
    public void changeModels(String newOllamaModel, String newGeminiModel) {
        this.ollamaModel = newOllamaModel;
        this.geminiModel = newGeminiModel;
        this.updatedAt = Instant.now();
    }

    /** Sceglie il fornitore. I controlli (chiave, consenso) li fa AiService prima; il database li ripete (V22). */
    public void choose(AiProvider newProvider) {
        this.provider = newProvider;
        this.updatedAt = Instant.now();
    }

    /** Ricorda il consenso all'invio dei dati a un servizio cloud (la prima volta: la data resta quella). */
    public void consent(AiProvider cloud, Instant when) {
        if (cloud == AiProvider.GEMINI && geminiConsentAt == null) {
            geminiConsentAt = when;
        }
    }

    /**
     * Salva (cifrata) o toglie (null) la chiave di un servizio cloud. Togliendo la chiave del servizio scelto, l'IA
     * si spegne: senza chiave non funzionerebbe.
     */
    public void apiKey(AiProvider cloud, String encrypted) {
        if (cloud == AiProvider.GEMINI) {
            geminiApiKey = encrypted;
        } else {
            throw new IllegalArgumentException("Solo i servizi cloud hanno una chiave: " + cloud);
        }
        if (encrypted == null && provider == cloud) {
            provider = AiProvider.NONE;
        }
        this.updatedAt = Instant.now();
    }

    /** La chiave cifrata di un servizio cloud, o null. */
    public String encryptedApiKey(AiProvider cloud) {
        return switch (cloud) {
            case GEMINI -> geminiApiKey;
            default -> null;
        };
    }

    public boolean hasConsent(AiProvider cloud) {
        return switch (cloud) {
            case GEMINI -> geminiConsentAt != null;
            default -> true; // Ollama: i dati non escono dal computer
        };
    }

    /** Il modello scelto per un fornitore. */
    public String model(AiProvider target) {
        return switch (target) {
            case GEMINI -> geminiModel;
            default -> ollamaModel;
        };
    }

    /** Il fornitore e il modello in uso adesso. */
    public AiTarget target() {
        return new AiTarget(provider, model(provider));
    }

    public AiProvider getProvider() {
        return provider;
    }

    public String getOllamaModel() {
        return ollamaModel;
    }
}
