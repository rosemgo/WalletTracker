package it.wallettracker.ai;

import java.util.List;
import java.util.Set;

import it.wallettracker.account.AccountRepository;
import it.wallettracker.category.CategoryRepository;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.transaction.BankTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Le impostazioni dell'IA e lo stato di Ollama.
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
     * @param provider        la scelta dell'utente: spento o Ollama
     * @param ollamaModel     il modello scelto
     * @param ollamaReachable se Ollama risponde
     * @param installedModels i modelli già scaricati in Ollama
     * @param modelInstalled  se il modello scelto è tra quelli scaricati
     * @param download        lo scaricamento in corso o l'ultimo fatto, o null
     * @param evaluation      la misura della precisione in corso o l'ultima fatta, o null
     */
    public record Status(AiProvider provider, String ollamaModel, boolean ollamaReachable, List<String> installedModels,
            boolean modelInstalled, ModelDownload.State download, ModelEvaluation.State evaluation) {
    }

    private final AiSettingsRepository settingsRepository;
    private final OllamaApi ollamaApi;
    private final ModelDownload modelDownload;
    private final ModelEvaluation modelEvaluation;
    private final BankTransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;

    public AiService(AiSettingsRepository settingsRepository, OllamaApi ollamaApi, ModelDownload modelDownload,
            ModelEvaluation modelEvaluation, BankTransactionRepository transactionRepository,
            CategoryRepository categoryRepository, AccountRepository accountRepository) {
        this.settingsRepository = settingsRepository;
        this.ollamaApi = ollamaApi;
        this.modelDownload = modelDownload;
        this.modelEvaluation = modelEvaluation;
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public Status status() {
        AiSettings settings = settings();
        List<String> installed = installedModels();
        boolean reachable = installed != null;
        List<String> models = reachable ? installed : List.of();
        return new Status(settings.getProvider(), settings.getOllamaModel(), reachable, models,
                isInstalled(models, settings.getOllamaModel()), modelDownload.state(), modelEvaluation.state());
    }

    @Transactional
    public Status change(AiProvider provider, String ollamaModel) {
        settings().change(provider, ollamaModel.trim());
        return status();
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
     * @param model   un modello già scaricato in Ollama
     * @param samples quanti esercenti provare
     */
    @Transactional(readOnly = true)
    public Status evaluate(String model, int samples) {
        List<String> installed = installedModels();
        if (installed == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ollama non risponde: è avviato?");
        }
        if (!isInstalled(installed, model)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Il modello " + model + " non è scaricato.");
        }
        List<Set<String>> owners = ClassificationService.ownerNames(accountRepository.findAll());
        List<ModelEvaluation.Case> cases = EvaluationCases.build(transactionRepository.findAllWithManualCategory(),
                categoryRepository.findAll(), owners, samples);
        if (cases.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Non c'è niente da misurare: la misura confronta il modello con le categorie che hai scelto a mano"
                            + " per spese ed entrate, e non ne hai ancora scelta nessuna.");
        }
        modelEvaluation.start(model, cases);
        return status();
    }

    /** L'IA è accesa? Per ora vuol dire: l'utente ha scelto Ollama. */
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
