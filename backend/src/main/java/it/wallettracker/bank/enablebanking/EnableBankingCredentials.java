package it.wallettracker.bank.enablebanking;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Da dove prendere Application ID e chiave privata di Enable Banking.
 *
 * <p>Due possibili fonti, in quest'ordine:
 * <ol>
 *   <li><b>la dashboard</b> (pagina Impostazioni): le credenziali salvate nel database;</li>
 *   <li><b>il file {@code .env}</b> ({@code ENABLE_BANKING_APP_ID} e {@code ENABLE_BANKING_PRIVATE_KEY_PATH}),
 *       come si faceva fino al passo 4.</li>
 * </ol>
 * Se nessuna delle due è disponibile, l'applicazione parte lo stesso: la dashboard mostrerà la procedura
 * guidata, e le chiamate a Enable Banking falliranno con un messaggio chiaro.
 *
 * <p>La chiave letta resta in memoria ("cache"): leggerla e decodificarla a ogni richiesta sarebbe inutile.
 * Quando si salvano credenziali nuove, la cache viene svuotata.
 */
@Component
public class EnableBankingCredentials {

    private static final Logger log = LoggerFactory.getLogger(EnableBankingCredentials.class);

    /** Da dove arrivano le credenziali in uso. */
    public enum Source { DASHBOARD, ENV }

    /** Le credenziali pronte per firmare i JWT. */
    public record Credentials(String applicationId, PrivateKey privateKey, Source source) {
    }

    private final EnableBankingProperties properties;
    private final EnableBankingSettingsRepository repository; // null nei test senza database

    private Optional<Credentials> cached;

    @Autowired
    public EnableBankingCredentials(EnableBankingProperties properties, EnableBankingSettingsRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    /** Solo per i test senza database: usa soltanto le impostazioni del file .env. */
    public static EnableBankingCredentials fromPropertiesOnly(EnableBankingProperties properties) {
        return new EnableBankingCredentials(properties, null);
    }

    /**
     * Le credenziali in uso, se ce ne sono.
     *
     * <p>{@code synchronized}: più thread (richieste HTTP, sincronizzazione automatica) possono chiamarlo
     * insieme; così uno solo alla volta legge o svuota la cache.
     */
    public synchronized Optional<Credentials> current() {
        if (cached == null) {
            cached = load();
        }
        return cached;
    }

    /** Salva credenziali nuove (già verificate) dalla dashboard: da ora in poi si usano queste. */
    public synchronized void save(String applicationId, String privateKeyPem) {
        repository.save(new EnableBankingSettings(applicationId, privateKeyPem));
        cached = null; // al prossimo current() verranno rilette
    }

    /** Dimentica le credenziali in memoria: al prossimo current() verranno rilette (usato nei test). */
    public synchronized void clearCache() {
        cached = null;
    }

    private Optional<Credentials> load() {
        // 1. Dalla dashboard.
        if (repository != null) {
            Optional<EnableBankingSettings> saved = repository.findById(EnableBankingSettings.SINGLE_ROW_ID);
            if (saved.isPresent()) {
                PrivateKey key = EnableBankingJwtFactory.parsePrivateKey(saved.get().getPrivateKeyPem(), "salvata");
                return Optional.of(new Credentials(saved.get().getApplicationId(), key, Source.DASHBOARD));
            }
        }
        // 2. Dal file .env.
        String applicationId = properties.applicationId();
        String keyPath = properties.privateKeyPath();
        if (applicationId == null || applicationId.isBlank() || keyPath == null || keyPath.isBlank()) {
            return Optional.empty();
        }
        if (!Files.exists(Path.of(keyPath))) {
            log.warn("Enable Banking: la chiave privata indicata nel .env non esiste ({}). "
                    + "Configura le credenziali dalla dashboard (Impostazioni).", Path.of(keyPath).toAbsolutePath());
            return Optional.empty();
        }
        PrivateKey key = EnableBankingJwtFactory.loadPrivateKey(Path.of(keyPath));
        return Optional.of(new Credentials(applicationId, key, Source.ENV));
    }
}
