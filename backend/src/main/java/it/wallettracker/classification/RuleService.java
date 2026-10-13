package it.wallettracker.classification;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.BankTransactionRepository.RuleUsage;
import it.wallettracker.transaction.TransactionDto;
import it.wallettracker.web.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * La gestione delle regole dalla dashboard: elenco, creazione, modifica, cancellazione e prova.
 *
 * <p>Dopo ogni modifica tutti i movimenti vengono riclassificati: una regola nuova o cambiata vale subito
 * anche per il passato (vedi ClassificationService).
 */
@Service
public class RuleService {

    /** Quanti esempi mostrare nella prova di una regola. */
    static final int PREVIEW_EXAMPLES = 5;

    private final ClassificationRuleRepository ruleRepository;
    private final BankTransactionRepository transactionRepository;
    private final ClassificationService classificationService;

    public RuleService(ClassificationRuleRepository ruleRepository, BankTransactionRepository transactionRepository,
            ClassificationService classificationService) {
        this.ruleRepository = ruleRepository;
        this.transactionRepository = transactionRepository;
        this.classificationService = classificationService;
    }

    /** Il risultato della prova di una regola. */
    public record RulePreview(int matches, List<TransactionDto> examples) {
    }

    /** Tutte le regole, nell'ordine in cui vengono provate, con quante volte è stata usata ognuna. */
    @Transactional(readOnly = true)
    public List<RuleDto> rules() {
        // Collectors.toMap: da una lista di righe (id regola, utilizzi) a una mappa id -> utilizzi.
        Map<Long, Long> uses = transactionRepository.countUsesByRule().stream()
                .collect(Collectors.toMap(RuleUsage::getRuleId, RuleUsage::getUses));
        return ruleRepository.findAllByOrderByPriorityAscIdAsc().stream()
                .map(rule -> RuleDto.from(rule, uses.getOrDefault(rule.getId(), 0L)))
                .toList();
    }

    @Transactional
    public RuleDto create(RuleRequest request) {
        ClassificationRule rule = new ClassificationRule(request.name().trim(), request.priority(),
                request.matchMode(), request.pattern().trim(), request.amountSign(), request.resultType(),
                request.categoryOrNull());
        rule.setEnabled(request.enabled());
        ruleRepository.save(rule);
        return reclassifyAndDescribe(rule);
    }

    @Transactional
    public RuleDto update(Long id, RuleRequest request) {
        ClassificationRule rule = find(id);
        rule.update(request.name().trim(), request.priority(), request.enabled(), request.matchMode(),
                request.pattern().trim(), request.amountSign(), request.resultType(), request.categoryOrNull());
        return reclassifyAndDescribe(rule);
    }

    /** Cancella una regola creata dall'utente. Le regole di partenza non si cancellano: si disattivano. */
    @Transactional
    public void delete(Long id) {
        ClassificationRule rule = find(id);
        if (rule.isBuiltin()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le regole di partenza non si cancellano: puoi disattivarle.");
        }
        ruleRepository.delete(rule);
        // flush(): la cancellazione va scritta nel database PRIMA che classifyAll rilegga le regole attive.
        ruleRepository.flush();
        classificationService.classifyAll();
    }

    /**
     * Prova una regola SENZA salvarla: a quali movimenti si applicherebbe il suo testo?
     *
     * <p>Attenzione: è una prova del solo testo e del segno dell'importo. Nella classificazione vera vengono
     * prima altri controlli (correzioni manuali, trasferimenti abbinati...) e le regole con priorità più
     * alta, quindi alcuni di questi movimenti potrebbero restare classificati in un altro modo.
     */
    @Transactional(readOnly = true)
    public RulePreview preview(RuleRequest request) {
        ClassificationRule rule = new ClassificationRule(request.name(), request.priority(), request.matchMode(),
                request.pattern().trim(), request.amountSign(), request.resultType(), request.categoryOrNull());
        List<BankTransaction> matching = transactionRepository.findAll().stream()
                .filter(t -> rule.matches(ClassificationService.textOf(t), t.getAmount()))
                .sorted((a, b) -> b.getBookingDate().compareTo(a.getBookingDate()))
                .toList();
        List<TransactionDto> examples = matching.stream().limit(PREVIEW_EXAMPLES).map(TransactionDto::from).toList();
        return new RulePreview(matching.size(), examples);
    }

    private ClassificationRule find(Long id) {
        return ruleRepository.findById(id).orElseThrow(() -> new NotFoundException("Regola", id));
    }

    /** Riclassifica tutto e restituisce la regola con il nuovo numero di utilizzi. */
    private RuleDto reclassifyAndDescribe(ClassificationRule rule) {
        ruleRepository.flush();
        classificationService.classifyAll();
        transactionRepository.flush();
        long uses = transactionRepository.countUsesByRule().stream()
                .filter(u -> u.getRuleId().equals(rule.getId()))
                .mapToLong(RuleUsage::getUses)
                .sum();
        return RuleDto.from(rule, uses);
    }
}
