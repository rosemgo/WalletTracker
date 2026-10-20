package it.wallettracker.ai;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaApi.ProgressResponse;
import org.springframework.ai.ollama.api.OllamaApi.PullModelRequest;
import org.springframework.stereotype.Component;

/**
 * Lo scaricamento di un modello in Ollama, che dura minuti (un modello piccolo pesa un paio di GB).
 *
 * <p>Una richiesta HTTP non può aspettare minuti: {@link #start} fa partire lo scaricamento <b>in background</b>
 * e risponde subito; la dashboard chiede ogni tanto a che punto è ({@link #state}).
 *
 * <p>Spring AI ci dà l'avanzamento come un {@code Flux}: uno "stream" di messaggi che arrivano uno alla volta
 * ("pulling manifest", "downloading 35%", ..., "success"). Con {@code subscribe} diciamo che cosa fare a ogni
 * messaggio, alla fine e in caso di errore; il lavoro avviene su un altro thread. Lo stato lo teniamo in un
 * {@link AtomicReference}: il thread dello scaricamento lo scrive, quello delle richieste HTTP lo legge, e
 * un AtomicReference garantisce che ognuno veda sempre un valore completo.
 *
 * <p>Lo stato sta in memoria: se il backend si riavvia durante lo scaricamento, Ollama riprende da dove era
 * arrivato alla prossima richiesta.
 *
 * <p><b>Attenzione, scoperto provando dal vivo:</b> se lo scaricamento fallisce (nome sbagliato, internet non
 * raggiungibile), Ollama non risponde con un errore HTTP ma con un messaggio normale nello stream,
 * {@code {"error": "pull model manifest: ..."}}, e poi chiude. Il record di Spring AI per l'avanzamento
 * ({@link ProgressResponse}) non ha il campo {@code error}: il messaggio arriva vuoto e lo stream finisce
 * "regolarmente". Per questo, alla fine, non ci fidiamo: chiediamo a Ollama se il modello adesso c'è.
 */
@Component
public class ModelDownload {

    private static final Logger log = LoggerFactory.getLogger(ModelDownload.class);

    /**
     * A che punto è lo scaricamento.
     *
     * @param model     il modello
     * @param running   se sta ancora scaricando
     * @param status    l'ultimo messaggio di Ollama (es. "pulling manifest", "success")
     * @param completed i byte scaricati della parte in corso (null se Ollama non lo dice)
     * @param total     i byte totali della parte in corso
     * @param error     il motivo, se è fallito
     */
    public record State(String model, boolean running, String status, Long completed, Long total, String error) {
    }

    private final OllamaApi ollamaApi;
    private final AtomicReference<State> state = new AtomicReference<>();

    public ModelDownload(OllamaApi ollamaApi) {
        this.ollamaApi = ollamaApi;
    }

    /** Lo stato dell'ultimo scaricamento, o null se non ne è mai partito uno. */
    public State state() {
        return state.get();
    }

    /** Fa partire lo scaricamento, se non ce n'è già uno in corso. Restituisce subito. */
    public synchronized void start(String model) {
        State current = state.get();
        if (current != null && current.running()) {
            return; // ce n'è già uno: non ne facciamo partire un secondo
        }
        state.set(new State(model, true, "avvio", null, null, null));
        ollamaApi.pullModel(new PullModelRequest(model)).subscribe(
                progress -> state.set(progressOf(model, progress)),
                error -> {
                    log.warn("Scaricamento di {} non riuscito: {}", model, error.getMessage());
                    state.set(new State(model, false, "errore", null, null, error.getMessage()));
                },
                () -> state.set(finished(model)));
    }

    /** Lo stream è finito: il modello adesso c'è davvero? */
    private State finished(String model) {
        try {
            List<String> installed = ollamaApi.listModels().models().stream().map(OllamaApi.Model::name).toList();
            if (AiService.isInstalled(installed, model)) {
                return new State(model, false, "success", null, null, null);
            }
        } catch (RuntimeException e) {
            log.warn("Dopo lo scaricamento di {} Ollama non risponde: {}", model, e.getMessage());
        }
        log.warn("Ollama ha finito senza scaricare {}", model);
        return new State(model, false, "errore", null, null, "Ollama ha finito senza scaricare il modello. Di solito "
                + "il nome è sbagliato, oppure Ollama non riesce a raggiungere internet (registry.ollama.ai)");
    }

    private static State progressOf(String model, ProgressResponse progress) {
        return new State(model, true, progress.status(), progress.completed(), progress.total(), null);
    }
}
