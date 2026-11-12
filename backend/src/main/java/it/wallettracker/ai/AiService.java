package it.wallettracker.ai;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import it.wallettracker.account.AccountRepository;
import it.wallettracker.category.CategoryRepository;
import it.wallettracker.category.CategoryKind;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.security.SecretCipher;
import it.wallettracker.transaction.BankTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Le impostazioni dell'IA e lo stato di Ollama e dei servizi cloud (passo 7c: Google Gemini, docs/30).
 *
 * <p>{@link OllamaApi} è il "client" di Ollama che Spring AI crea da solo (configurazione automatica), con
 * l'indirizzo scritto in {@code spring.ai.ollama.base-url}. Qui lo usiamo solo per chiedere quali modelli
 * sono installati: se risponde, Ollama è acceso.
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    /**
     * Le impostazioni e lo stato, per la pagina Impostazioni.
     *
     * @param provider        la scelta dell'utente: spento, Ollama o Gemini
     * @param ollamaModel     il modello scelto per Ollama
     * @param ollamaReachable se Ollama risponde
     * @param installedModels i modelli già scaricati in Ollama
     * @param modelInstalled  se il modello scelto è tra quelli scaricati
     * @param download        lo scaricamento in corso o l'ultimo fatto, o null
     * @param evaluation      la misura della precisione in corso o l'ultima fatta, o null
     * @param gemini          Google Gemini: modello, chiave, consenso
     */
    public record Status(AiProvider provider, String ollamaModel, boolean ollamaReachable, List<String> installedModels,
            boolean modelInstalled, ModelDownload.State download, ModelEvaluation.State evaluation,
            CloudStatus gemini) {
    }

    /**
     * Lo stato di un servizio cloud. La chiave <b>non</b> c'è: la pagina sa solo se è salvata e ne vede le ultime 4
     * lettere, per riconoscerla. Una chiave non torna mai indietro, nemmeno a chi l'ha inserita.
     *
     * @param keySet  se la chiave è salvata
     * @param keyHint le ultime 4 lettere ("…a1b2"), o null
     * @param consent se l'utente ha accettato l'invio dei dati a questo servizio
     */
    public record CloudStatus(String model, boolean keySet, String keyHint, boolean consent) {
    }

    /**
     * Il risultato di "Prova la connessione": che cosa ha risposto, in quanto tempo, o perché no.
     *
     * @param model          il modello che ha risposto davvero (con un alias, quello scelto dal fornitore), o null
     * @param thinkingTokens i token di "ragionamento" usati (solo Gemini), o null
     */
    public record ConnectionTest(boolean ok, String answer, double seconds, String error, String model,
            Integer thinkingTokens) {
    }

    private final AiSettingsRepository settingsRepository;
    private final OllamaApi ollamaApi;
    private final ModelDownload modelDownload;
    private final ModelEvaluation modelEvaluation;
    private final BankTransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;
    private final SecretCipher cipher;
    private final CategoryModel categoryModel;

    public AiService(AiSettingsRepository settingsRepository, OllamaApi ollamaApi, ModelDownload modelDownload,
            ModelEvaluation modelEvaluation, BankTransactionRepository transactionRepository,
            CategoryRepository categoryRepository, AccountRepository accountRepository, SecretCipher cipher,
            CategoryModel categoryModel) {
        this.settingsRepository = settingsRepository;
        this.ollamaApi = ollamaApi;
        this.modelDownload = modelDownload;
        this.modelEvaluation = modelEvaluation;
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.accountRepository = accountRepository;
        this.cipher = cipher;
        this.categoryModel = categoryModel;
    }

    @Transactional(readOnly = true)
    public Status status() {
        AiSettings settings = settings(); //legge l'unica riga (ID=1) dalla tabella ai_settings del database
        List<String> installed = installedModels();
        boolean reachable = installed != null;
        List<String> models = reachable ? installed : List.of();
        return new Status(settings.getProvider(), settings.getOllamaModel(), reachable, models,
                isInstalled(models, settings.getOllamaModel()), modelDownload.state(), modelEvaluation.state(),
                cloudStatus(settings, AiProvider.GEMINI));
    }

    private CloudStatus cloudStatus(AiSettings settings, AiProvider cloud) {
        String encrypted = settings.encryptedApiKey(cloud);
        String hint = null;
        if (encrypted != null) {
            try {
                String key = cipher.decrypt(encrypted, AiChatClients.keyPurpose(cloud));
                hint = "…" + key.substring(Math.max(0, key.length() - 4));
            } catch (IllegalStateException e) {
                hint = "illeggibile: inseriscila di nuovo"; // la chiave di cifratura è cambiata (SecretCipher)
            }
        }
        return new CloudStatus(settings.model(cloud), encrypted != null, hint, settings.hasConsent(cloud));
    }

    /**
     * Cambia la scelta e i modelli. Per scegliere un servizio cloud servono la chiave (salvata prima, con
     * {@link #saveApiKey}) e il consenso all'invio dei dati: la prima volta arriva con {@code consent = true}, poi
     * resta salvato. Un modello null vuol dire "lascia quello di prima".
     */
    @Transactional
    public Status change(AiProvider provider, String ollamaModel, String geminiModel, boolean consent) {
        AiSettings settings = settings();
        settings.changeModels(ollamaModel.trim(),
                geminiModel != null ? geminiModel.trim() : settings.model(AiProvider.GEMINI));
        if (provider.cloud()) {
            if (settings.encryptedApiKey(provider) == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Inserisci prima la chiave di " + provider.label + ".");
            }
            if (!settings.hasConsent(provider) && !consent) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Per usare " + provider.label
                        + " serve il tuo consenso all'invio dei testi degli esercenti.");
            }
            settings.consent(provider, Instant.now());
        }
        settings.choose(provider);
        return status();
    }

    /** Salva la chiave di un servizio cloud, cifrata. La chiave in chiaro non viene mai salvata né scritta nei log. */
    @Transactional
    public Status saveApiKey(AiProvider cloud, String apiKey) {
        requireCloud(cloud);
        settings().apiKey(cloud, cipher.encrypt(apiKey.trim(), AiChatClients.keyPurpose(cloud)));
        return status();
    }

    /** Toglie la chiave. Se quel servizio era quello scelto, l'IA si spegne. */
    @Transactional
    public Status deleteApiKey(AiProvider cloud) {
        requireCloud(cloud);
        settings().apiKey(cloud, null);
        return status();
    }

    private static void requireCloud(AiProvider provider) {
        if (!provider.cloud()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solo i servizi cloud hanno una chiave.");
        }
    }

    /**
     * Si può fare una domanda a questo fornitore? Se no, 409 con il motivo e che cosa fare. Lo usano la misura,
     * "Chiedi all'IA" e la prova della connessione.
     */
    @Transactional(readOnly = true)
    public void check(AiTarget target) {
        AiSettings settings = settings();
        switch (target.provider()) {
            case NONE -> throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "I suggerimenti con l'IA sono spenti: accendili in Impostazioni.");
            case OLLAMA -> {
                List<String> installed = installedModels();
                if (installed == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Ollama non risponde: è avviato?");
                }
                if (!isInstalled(installed, target.model())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Il modello " + target.model() + " non è scaricato: scaricalo in Impostazioni.");
                }
            }
            case GEMINI -> {
                if (settings.encryptedApiKey(target.provider()) == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Manca la chiave di " + target.provider().label + ": inseriscila in Impostazioni.");
                }
                if (!settings.hasConsent(target.provider())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Per usare " + target.provider().label
                            + " serve il tuo consenso: lo dai in Impostazioni, scegliendo il servizio.");
                }
            }
        }
    }

    /**
     * "Prova la connessione": una domanda vera e piccola (di che categoria è ESSELUNGA?), per sapere subito se la
     * chiave e il nome del modello vanno bene, e quanto tempo ci vuole. Non è @Transactional: la domanda può durare
     * secondi, e non serve tenere aperta una transazione del database mentre si aspetta.
     */
    public ConnectionTest testConnection(AiProvider provider) {
        AiTarget target = new AiTarget(provider, settings().model(provider));
        check(target);
        CategoryQuestion question = new CategoryQuestion("ESSELUNGA", true,
                EvaluationCases.options(categoryRepository.findAll(), CategoryKind.EXPENSE), List.of());
        long start = System.nanoTime();
        try {
            CategoryModel.Reply reply = categoryModel.ask(question, target);
            String name = reply.categoryId()
                    .flatMap(id -> question.categories().stream().filter(o -> o.id().equals(id)).findFirst())
                    .map(CategoryQuestion.Option::name).orElse("non so");
            double seconds = seconds(start);
            log.info("Prova della connessione a {}: {} in {} s (modello {}, token di ragionamento {})", provider.label,
                    name, String.format("%.1f", seconds), reply.model(), reply.thinkingTokens());
            return new ConnectionTest(true, name, seconds, null, reply.model(), reply.thinkingTokens());
        } catch (RuntimeException e) {
            log.warn("Prova della connessione a {} non riuscita: {}", provider.label, e.getMessage());
            return new ConnectionTest(false, null, seconds(start), AiErrors.describe(e, provider), null, null);
        }
    }

    private static double seconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1e9;
    }

    /** Fa partire lo scaricamento del modello scelto (in background: la risposta arriva subito). */
    @Transactional(readOnly = true)
    public Status downloadModel() {
        modelDownload.start(settings().getOllamaModel());
        return status();
    }

    /**
     * Fa partire la misura della precisione di un modello sulle tue scelte (in background, come lo
     * scaricamento). Funziona anche con l'IA spenta: serve proprio a decidere se accenderla, e con quale modello.
     *
     * @param target  il fornitore e il modello: un modello scaricato in Ollama, o un servizio cloud pronto
     * @param samples quanti esercenti provare
     */
    @Transactional(readOnly = true)
    public Status evaluate(AiTarget target, int samples) {
        check(target);
        List<Set<String>> owners = ClassificationService.ownerNames(accountRepository.findAll());
        List<ModelEvaluation.Case> cases = EvaluationCases.build(transactionRepository.findAllWithManualCategory(),
                categoryRepository.findAll(), owners, samples);
        if (cases.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Non c'è niente da misurare: la misura confronta il modello con le categorie che hai scelto a mano"
                            + " per spese ed entrate, e non ne hai ancora scelta nessuna.");
        }
        modelEvaluation.start(target, cases);
        return status();
    }

    /** L'IA è accesa? Vuol dire: l'utente ha scelto Ollama o un servizio cloud. */
    @Transactional(readOnly = true)
    public boolean enabled() {
        return settings().getProvider() != AiProvider.NONE;
    }

    @Transactional(readOnly = true)
    public AiSettings settings() {
        return settingsRepository.findById(AiSettings.ID).orElseThrow(); // la riga la crea V18
    }

    /** I modelli scaricati, o null se Ollama non risponde (spento, o indirizzo sbagliato). */
    private List<String> installedModels() {
        try {
            return ollamaApi.listModels().models().stream().map(OllamaApi.Model::name).toList();
        } catch (RuntimeException e) {
            log.debug("Ollama non risponde: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Il modello scelto è installato? Ollama chiama "qwen2.5:3b" un modello scaricato come "qwen2.5:3b", e
     * "llama3.2" uno scaricato come "llama3.2:latest" (":latest" è l'etichetta predefinita).
     */
    static boolean isInstalled(List<String> installed, String model) {
        String wanted = model.contains(":") ? model : model + ":latest";
        return installed.contains(wanted);
    }
}
