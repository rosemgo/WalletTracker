package it.wallettracker.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Le API delle impostazioni dell'IA:
 * <ul>
 *   <li>{@code GET  /api/ai}                lo stato: scelta dell'utente, Ollama acceso?, modello scaricato?;</li>
 *   <li>{@code PUT  /api/ai}                cambia la scelta (spento / Ollama / Mistral / Gemini) e i modelli;</li>
 *   <li>{@code PUT  /api/ai/keys/{provider}} salva la chiave di un servizio cloud, cifrata (non torna mai indietro);</li>
 *   <li>{@code DELETE /api/ai/keys/{provider}} la toglie;</li>
 *   <li>{@code POST /api/ai/connection-test/{provider}} una domanda di prova: chiave e modello funzionano?</li>
 *   <li>{@code POST /api/ai/model/download} fa partire lo scaricamento del modello (202: è in background);</li>
 *   <li>{@code POST /api/ai/evaluation}     fa partire la misura della precisione sulle tue scelte (202);</li>
 *   <li>{@code GET  /api/ai/suggestions}    "Chiedi all'IA": quanti esercenti si possono chiedere, e a che punto è;</li>
 *   <li>{@code POST /api/ai/suggestions}    "Chiedi all'IA": fa partire le domande (202).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiService aiService;
    private final AiSuggestionService suggestionService;

    public AiController(AiService aiService, AiSuggestionService suggestionService) {
        this.aiService = aiService;
        this.suggestionService = suggestionService;
    }

    @GetMapping
    public AiService.Status status() {
        return aiService.status();
    }

    @PutMapping
    public AiService.Status change(@Valid @RequestBody SettingsChange change) {
        return aiService.change(change.provider(), change.ollamaModel(), change.mistralModel(), change.geminiModel(),
                Boolean.TRUE.equals(change.consent()));
    }

    @PutMapping("/keys/{provider}")
    public AiService.Status saveKey(@PathVariable AiProvider provider, @Valid @RequestBody KeyChange change) {
        return aiService.saveApiKey(provider, change.apiKey());
    }

    @DeleteMapping("/keys/{provider}")
    public AiService.Status deleteKey(@PathVariable AiProvider provider) {
        return aiService.deleteApiKey(provider);
    }

    @PostMapping("/connection-test/{provider}")
    public AiService.ConnectionTest testConnection(@PathVariable AiProvider provider) {
        return aiService.testConnection(provider);
    }

    @PostMapping("/model/download")
    @ResponseStatus(HttpStatus.ACCEPTED) // 202: "ricevuto, ci sto lavorando"
    public AiService.Status download() {
        return aiService.downloadModel();
    }

    @PostMapping("/evaluation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AiService.Status evaluate(@Valid @RequestBody EvaluationRequest request) {
        AiProvider provider = request.provider() != null ? request.provider() : AiProvider.OLLAMA;
        return aiService.evaluate(new AiTarget(provider, request.model()), request.samples());
    }

    @GetMapping("/suggestions")
    public AiSuggestionService.Status suggestions() {
        return suggestionService.status();
    }

    @PostMapping("/suggestions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AiSuggestionService.Status askForSuggestions() {
        return suggestionService.start();
    }

    /**
     * Il corpo di {@code POST /api/ai/evaluation}: quale fornitore (se manca: Ollama) e quale modello, su quanti
     * esercenti (al massimo 100: con un modello da qualche secondo a domanda sono già alcuni minuti).
     */
    public record EvaluationRequest(
            AiProvider provider,
            @NotNull @Pattern(regexp = MODEL_NAME) String model,
            @Min(1) @Max(100) int samples) {
    }

    /**
     * Il corpo di {@code PUT /api/ai/keys/{provider}}: la chiave. Le chiavi di Mistral e Google sono fatte di
     * lettere, cifre, "-" e "_": accettiamo solo quelle (niente spazi, niente caratteri strani).
     */
    public record KeyChange(@NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{10,300}") String apiKey) {
    }

    /** I nomi dei modelli di Ollama ("qwen2.5:3b", "hf.co/utente/modello:Q4_K_M"): lettere, cifre e . _ : / - soltanto. */
    static final String MODEL_NAME = "[A-Za-z0-9._:/-]{1,100}";

    /**
     * Il corpo di {@code PUT /api/ai}. Il nome del modello ha la forma di Ollama ("qwen2.5:3b",
     * "hf.co/utente/modello:Q4_K_M"): lettere, cifre e . _ : / - soltanto.
     */
    public record SettingsChange(
            @NotNull AiProvider provider,
            @NotNull @Pattern(regexp = MODEL_NAME) String ollamaModel,
            @Pattern(regexp = MODEL_NAME) String mistralModel,     // null = lascia quello di prima
            @Pattern(regexp = MODEL_NAME) String geminiModel,
            Boolean consent) {                                       // il consenso all'invio dei dati (cloud)
    }
}
