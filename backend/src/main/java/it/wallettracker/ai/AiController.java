package it.wallettracker.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
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
 *   <li>{@code PUT  /api/ai}                cambia la scelta (spento / Ollama) e il modello;</li>
 *   <li>{@code POST /api/ai/model/download} fa partire lo scaricamento del modello (202: è in background).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    @GetMapping
    public AiService.Status status() {
        return aiService.status();
    }

    @PutMapping
    public AiService.Status change(@Valid @RequestBody SettingsChange change) {
        return aiService.change(change.provider(), change.ollamaModel());
    }

    @PostMapping("/model/download")
    @ResponseStatus(HttpStatus.ACCEPTED) // 202: "ricevuto, ci sto lavorando"
    public AiService.Status download() {
        return aiService.downloadModel();
    }

    /**
     * Il corpo di {@code PUT /api/ai}. Il nome del modello ha la forma di Ollama ("qwen2.5:3b",
     * "hf.co/utente/modello:Q4_K_M"): lettere, cifre e . _ : / - soltanto.
     */
    public record SettingsChange(
            @NotNull AiProvider provider,
            @NotNull @Pattern(regexp = "[A-Za-z0-9._:/-]{1,100}") String ollamaModel) {
    }
}
