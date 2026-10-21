package it.wallettracker.ai;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import it.wallettracker.account.AccountRepository;
import it.wallettracker.category.CategoryRepository;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.TransactionType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Chiedi all'IA" (Fase 3, passo 7b.3): quali esercenti chiedere ({@link AiQuestions}) e il giro di domande in
 * background ({@link AiAnswerJob}). Le risposte diventano suggerimenti nella pagina Movimenti, insieme a quelli
 * "dalla tua storia" (vedi SuggestionService).
 */
@Service
public class AiSuggestionService {

    /**
     * Lo stato, per il riquadro dei suggerimenti.
     *
     * @param enabled se l'IA è accesa (altrimenti il riquadro non mostra "Chiedi all'IA")
     * @param model   il modello scelto
     * @param toAsk   quanti esercenti si potrebbero chiedere adesso
     * @param job     il giro di domande in corso o l'ultimo fatto, o null
     */
    public record Status(boolean enabled, String model, int toAsk, AiAnswerJob.State job) {
    }

    private final AiService aiService;
    private final AiAnswerJob answerJob;
    private final AiCategoryAnswerRepository answerRepository;
    private final BankTransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;

    public AiSuggestionService(AiService aiService, AiAnswerJob answerJob, AiCategoryAnswerRepository answerRepository,
            BankTransactionRepository transactionRepository, CategoryRepository categoryRepository,
            AccountRepository accountRepository) {
        this.aiService = aiService;
        this.answerJob = answerJob;
        this.answerRepository = answerRepository;
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public Status status() {
        boolean enabled = aiService.enabled();
        int toAsk = enabled ? asks(Integer.MAX_VALUE).size() : 0;
        return new Status(enabled, aiService.settings().target().label(), toAsk, answerJob.state());
    }

    /**
     * Fa partire le domande (al massimo {@link AiQuestions#MAX_PER_RUN} esercenti). Prima i controlli, ognuno
     * con un messaggio che dice che cosa fare (409: la richiesta è giusta, ma adesso non si può).
     */
    @Transactional(readOnly = true)
    public Status start() {
        AiTarget target = aiService.settings().target();
        aiService.check(target); // IA accesa? Ollama pronto, o servizio cloud con chiave e consenso?
        List<AiQuestions.Ask> asks = asks(AiQuestions.MAX_PER_RUN);
        if (asks.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Non ci sono esercenti da chiedere: quelli senza categoria sono già stati chiesti all'IA,"
                            + " o hanno già un suggerimento dalla tua storia.");
        }
        answerJob.start(target, asks);
        return status();
    }

    /** Le domande da fare adesso (cinque query). */
    private List<AiQuestions.Ask> asks(int limit) {
        List<Set<String>> owners = ClassificationService.ownerNames(accountRepository.findAll());
        return AiQuestions.build(
                transactionRepository.findUncategorized(EnumSet.of(TransactionType.EXPENSE, TransactionType.INCOME)),
                transactionRepository.findAllWithManualCategory(), categoryRepository.findAll(), owners,
                answerRepository.findAllMerchantKeys(), limit);
    }
}
