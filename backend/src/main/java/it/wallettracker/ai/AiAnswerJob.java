package it.wallettracker.ai;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import it.wallettracker.category.Category;
import it.wallettracker.category.CategoryRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * "Chiedi all'IA": fa le domande di {@link AiQuestions} una dopo l'altra, in background, e salva ogni risposta
 * (V20). Lo stesso schema della misura ({@link ModelEvaluation}): la richiesta HTTP fa partire il lavoro e
 * risponde subito, un thread nostro fa le domande, la pagina chiede lo stato ogni tanto.
 *
 * <p>Ogni risposta si salva appena arriva: se il lavoro si interrompe (Ollama spento a metà), le risposte già
 * avute restano, e il prossimo "Chiedi all'IA" riparte dagli esercenti mancanti.
 */
@Component
public class AiAnswerJob {

    private static final Logger log = LoggerFactory.getLogger(AiAnswerJob.class);

    /**
     * A che punto è.
     *
     * @param done  quanti esercenti sono stati chiesti
     * @param found quante risposte con una categoria (le altre sono "non so")
     */
    public record State(String model, boolean running, int done, int total, int found, String error) {
    }

    private final CategoryModel categoryModel;
    private final AiCategoryAnswerRepository answerRepository;
    private final CategoryRepository categoryRepository;
    private final AtomicReference<State> state = new AtomicReference<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public AiAnswerJob(CategoryModel categoryModel, AiCategoryAnswerRepository answerRepository,
            CategoryRepository categoryRepository) {
        this.categoryModel = categoryModel;
        this.answerRepository = answerRepository;
        this.categoryRepository = categoryRepository;
    }

    public State state() {
        return state.get();
    }

    /** Fa partire le domande in background, se non ce n'è già un giro in corso. */
    public synchronized void start(AiTarget target, List<AiQuestions.Ask> asks) {
        State current = state.get();
        if (current != null && current.running()) {
            return;
        }
        state.set(new State(target.label(), true, 0, asks.size(), 0, null));
        executor.submit(() -> run(target, asks));
    }

    private void run(AiTarget target, List<AiQuestions.Ask> asks) {
        String model = target.label();
        int found = 0;
        for (int i = 0; i < asks.size(); i++) {
            AiQuestions.Ask ask = asks.get(i);
            try {
                Optional<Long> answer = categoryModel.suggest(ask.question(), target);
                // L'id è già controllato (è una delle categorie della domanda): qui prendiamo l'entità da salvare.
                Category category = answer.flatMap(categoryRepository::findById).orElse(null);
                answerRepository.save(new AiCategoryAnswer(ask.merchantKey(), category, model));
                found += category != null ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn("Domanda all'IA non riuscita ({}): {}", ask.merchantKey(), e.getMessage());
                state.set(new State(model, false, i, asks.size(), found, AiErrors.describe(e, target.provider())));
                return;
            }
            state.set(new State(model, i + 1 < asks.size(), i + 1, asks.size(), found, null));
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
