package it.wallettracker.ai;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaApi.ProgressResponse;
import org.springframework.ai.ollama.api.OllamaApi.PullModelRequest;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

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
 *
 * <p><b>E un secondo difetto, trovato usando l'app:</b> i messaggi dello stream arrivano su un thread di Reactor
 * Netty (la libreria di rete di Spring AI). È un "event loop": pochi thread che servono tutte le connessioni, e che
 * non devono mai fermarsi ad aspettare. La verifica finale (l'elenco dei modelli) è invece una chiamata che
 * aspetta la risposta: lì è vietata, e Reactor la rifiuta con un'eccezione. {@code publishOn} sposta i messaggi
 * su un thread dove aspettare si può ({@code boundedElastic}, fatto apposta per il lavoro bloccante). Lo prova
 * ModelDownloadTest, con la rete vera.
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
        //programmazione reattiva (durante lo scaricamento del modello ollama vengono inviati uno stream di dati (Flux) per l'avanzamento: Spring AI gestisce questo stream) -> OllamaApi.pullModel() di Spring AI restituisce un Flux<ProgressResponse>, non un metodo bloccante. Ollama manda l’avanzamento come stream, e Spring AI lo legge con WebClient (Netty).
        ollamaApi.pullModel(new PullModelRequest(model))
                .publishOn(Schedulers.boundedElastic()) // Esegue le lambda del subscribe in background (l'aggiornamento dello stato) su un thread di background per non impegnare il thread di rete (Netty) che riceve i dati.
                .subscribe(
                progress -> state.set(progressOf(model, progress)),
                error -> {
                    log.warn("Scaricamento di {} non riuscito: {}", model, error.getMessage());
                    state.set(new State(model, false, "errore", null, null, error.getMessage()));
                },
                () -> state.set(finished(model))); //il segnale di completamento è un evento "puro": avvisa che l'azione è finita, ma non sta passando un nuovo dato. Non essendoci parametri in ingresso, si usano le parentesi tonde vuote ().
    }

    /** Lo stream è finito: il modello adesso c'è davvero? */
    private State finished(String model) {
        try {
            List<String> installed = ollamaApi.listModels().models().stream().map(OllamaApi.Model::name).toList();
            if (AiService.isInstalled(installed, model)) {
                return new State(model, false, "success", null, null, null);
            }
        } catch (RuntimeException e) {
            // Un motivo diverso, e un messaggio diverso: prima finivano tutti in "Ollama ha finito senza scaricare".
            log.warn("Dopo lo scaricamento di {} non riesco a chiedere a Ollama i modelli: {}", model, e.getMessage());
            return new State(model, false, "errore", null, null,
                    "Lo scaricamento è finito, ma non sono riuscito a controllare se il modello c'è: " + e.getMessage());
        }
        log.warn("Ollama ha finito senza scaricare {}", model);
        //State è un record, quindi oggetto immutabile: ogni volta n creo uno nuovo che sostituisce il vecchio (non modifico il vecchio)
        return new State(model, false, "errore", null, null, "Ollama ha finito senza scaricare il modello. Di solito "
                + "il nome è sbagliato, oppure Ollama non riesce a raggiungere internet (registry.ollama.ai)");
    }

    private static State progressOf(String model, ProgressResponse progress) {
        //State è un record, quindi oggetto immutabile: ogni volta n creo uno nuovo che sostituisce il vecchio (non modifico il vecchio)
        return new State(model, true, progress.status(), progress.completed(), progress.total(), null);
    }
}
