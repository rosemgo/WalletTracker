package it.wallettracker.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * La misura della precisione di un modello sulle scelte dell'utente: per ogni caso chiede al modello la
 * categoria (senza dirgli la risposta) e la confronta con quella scelta dall'utente.
 *
 * <p>Una domanda dura qualche secondo: 20 domande, un minuto o due. Come per lo scaricamento
 * ({@link ModelDownload}), il lavoro gira in background e la dashboard chiede lo stato. Qui il lavoro è
 * nostro (un ciclo di chiamate), non uno stream di Ollama: lo facciamo girare su un thread nostro, con un
 * {@link ExecutorService} da un thread solo (una misura alla volta).
 */
@Component
public class ModelEvaluation {

    private static final Logger log = LoggerFactory.getLogger(ModelEvaluation.class);

    /** Un caso: la domanda e la risposta giusta (la scelta dell'utente). */
    public record Case(CategoryQuestion question, Long expectedId, String expected) {
    }

    /** Un errore, da mostrare: l'esercente, la scelta dell'utente e quella del modello. */
    public record Mistake(String merchant, String expected, String answer) {
    }

    /**
     * A che punto è la misura, e i risultati.
     *
     * @param done    quante domande sono state fatte
     * @param right   risposte uguali alla scelta dell'utente
     * @param wrong   risposte diverse
     * @param unsure  "non so"
     * @param seconds il tempo medio per domanda
     */
    public record State(String model, boolean running, int done, int total, int right, int wrong,
            int unsure, double seconds, List<Mistake> mistakes, String error) {
    }

    private final CategoryModel categoryModel;
    private final AtomicReference<State> state = new AtomicReference<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public ModelEvaluation(CategoryModel categoryModel) {
        this.categoryModel = categoryModel;
    }

    public State state() {
        return state.get();
    }

    /** Fa partire la misura in background, se non ce n'è già una in corso. */
    public synchronized void start(String model, List<Case> cases) {
        State current = state.get();
        if (current != null && current.running()) {
            return;
        }
        state.set(new State(model, true, 0, cases.size(), 0, 0, 0, 0, List.of(), null));
        executor.submit(() -> run(model, cases));
    }

    private void run(String model, List<Case> cases) {
        int right = 0;
        int wrong = 0;
        int unsure = 0;
        List<Mistake> mistakes = new ArrayList<>();
        long start = System.nanoTime();
        try {
            for (int i = 0; i < cases.size(); i++) {
                Case c = cases.get(i);
                Optional<Long> answer = categoryModel.suggest(c.question(), model);
                if (answer.isEmpty()) {
                    unsure++;
                } else if (answer.get().equals(c.expectedId())) {
                    right++;
                } else {
                    wrong++;
                    mistakes.add(new Mistake(c.question().merchant(), c.expected(), nameOf(c.question(), answer.get())));
                }
                double seconds = (System.nanoTime() - start) / 1e9 / (i + 1);
                state.set(new State(model, i + 1 < cases.size(), i + 1, cases.size(), right, wrong, unsure,
                        seconds, List.copyOf(mistakes), null));
            }
        } catch (RuntimeException e) {
            log.warn("Misura di {} non riuscita: {}", model, e.getMessage());
            State last = state.get();
            state.set(new State(model, false, last.done(), last.total(), right, wrong, unsure, last.seconds(),
                    List.copyOf(mistakes), "Il modello non ha risposto: " + e.getMessage()));
        }
    }

    private static String nameOf(CategoryQuestion question, Long id) {
        return question.categories().stream().filter(o -> o.id().equals(id)).map(CategoryQuestion.Option::name)
                .findFirst().orElse("?");
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow(); // alla chiusura dell'app, il thread si ferma
    }
}
