package it.wallettracker.classification;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import it.wallettracker.category.CategoryService;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.BankTransactionRepository.RuleUsage;
import it.wallettracker.transaction.TransactionDto;
import it.wallettracker.transaction.TransactionType;
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
    private final CategoryService categoryService;

    public RuleService(ClassificationRuleRepository ruleRepository, BankTransactionRepository transactionRepository,
            ClassificationService classificationService, CategoryService categoryService) {
        this.ruleRepository = ruleRepository;
        this.transactionRepository = transactionRepository;
        this.classificationService = classificationService;
        this.categoryService = categoryService;
    }

    /**
     * Il risultato della prova di una regola.
     *
     * @param matches  a quanti movimenti corrisponde il testo
     * @param applies  quanti di questi la regola classificherebbe davvero
     * @param blockers per gli altri, chi vince al suo posto (docs/investimenti.md, parte H)
     */
    public record RulePreview(int matches, int applies, List<Blocker> blockers, List<TransactionDto> examples) {
    }

    /** Perché una regola non vale per alcuni movimenti. */
    public enum BlockReason {
        /** Una regola che viene prima (priorità più bassa). */
        RULE,
        /** Il tipo scelto a mano dall'utente. */
        MANUAL,
        /** Un trasferimento tra i tuoi conti (abbinato, o con l'IBAN di un tuo conto), o un conto escluso. */
        TRANSFER
    }

    /**
     * Chi vince su quanti movimenti.
     *
     * @param rule     il nome della regola che viene prima (solo per RULE)
     * @param priority la sua priorità (solo per RULE)
     */
    public record Blocker(BlockReason reason, String rule, Integer priority, int movements) {
    }

    /** I tipi che la classificazione dà ai trasferimenti tra i tuoi conti PRIMA delle regole (passi 4 e 5). */
    private static final Set<TransactionType> TRANSFER_TYPES = EnumSet.of(TransactionType.INTERNAL_TRANSFER,
            TransactionType.CARD_SETTLEMENT, TransactionType.INVESTMENT_DEPOSIT, TransactionType.INVESTMENT_WITHDRAWAL,
            TransactionType.IGNORED);

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
        checkPattern(request);
        ClassificationRule rule = new ClassificationRule(request.name().trim(), request.priority(),
                request.matchMode(), request.pattern().trim(), request.amountSign(), request.resultType(),
                categoryService.require(request.categoryId())); // la categoria deve essere nell'elenco
        rule.setEnabled(request.enabled());
        ruleRepository.save(rule);
        return reclassifyAndDescribe(rule);
    }

    @Transactional
    public RuleDto update(Long id, RuleRequest request) {
        checkPattern(request);
        ClassificationRule rule = find(id);
        rule.update(request.name().trim(), request.priority(), request.enabled(), request.matchMode(),
                request.pattern().trim(), request.amountSign(), request.resultType(),
                categoryService.require(request.categoryId()));
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
     * Prova una regola SENZA salvarla: a quali movimenti corrisponde il suo testo, e a quanti si applicherebbe
     * davvero.
     *
     * <p>Corrispondere non basta. Nella classificazione vera vengono prima le correzioni manuali, i trasferimenti
     * tra i tuoi conti e le regole con una priorità più bassa (ClassificationService): per quei movimenti vince
     * qualcun altro. La prova lo dice, con il nome della regola che vince e la sua priorità, così si capisce che cosa
     * cambiare. Successe con una regola per i bonifici verso un PAC: corrispondeva a 4 movimenti, ma la regola di
     * partenza "Trasferimento a un proprio conto" (priorità 40) li prendeva tutti prima.
     *
     * <p>Chi vince si legge dalla classificazione attuale di ogni movimento, che è il risultato di quell'ordine.
     *
     * @param editingId la regola che si sta modificando (null per una regola nuova): non blocca sé stessa
     */
    @Transactional(readOnly = true)
    public RulePreview preview(RuleRequest request, Long editingId) {
        checkPattern(request);
        ClassificationRule rule = new ClassificationRule(request.name(), request.priority(), request.matchMode(),
                request.pattern().trim(), request.amountSign(), request.resultType(), null); // la categoria non conta
        List<BankTransaction> matching = transactionRepository.findAll().stream()
                .filter(t -> rule.matches(ClassificationService.textOf(t), t.getAmount()))
                .sorted((a, b) -> b.getBookingDate().compareTo(a.getBookingDate()))
                .toList();
        Map<Long, ClassificationRule> rules = ruleRepository.findAll().stream()
                .collect(Collectors.toMap(ClassificationRule::getId, r -> r));

        // Per ogni movimento: chi vince al posto della regola provata (null = vince lei).
        Map<String, Blocker> blockers = new LinkedHashMap<>();
        int applies = 0;
        for (BankTransaction t : matching) {
            Blocker blocker = blockerOf(t, request.priority(), editingId, rules);
            if (blocker == null) {
                applies++;
            } else {
                String key = blocker.reason() + ":" + blocker.rule();
                blockers.merge(key, blocker, (a, b) -> new Blocker(a.reason(), a.rule(), a.priority(),
                        a.movements() + 1));
            }
        }
        List<Blocker> sorted = blockers.values().stream()
                .sorted(Comparator.comparingInt(Blocker::movements).reversed())
                .toList();
        List<TransactionDto> examples = matching.stream().limit(PREVIEW_EXAMPLES).map(TransactionDto::from).toList();
        return new RulePreview(matching.size(), applies, sorted, examples);
    }

    /** Chi vince su questo movimento al posto di una regola con questa priorità; null se vincerebbe la regola. */
    static Blocker blockerOf(BankTransaction t, int priority, Long editingId, Map<Long, ClassificationRule> rules) {
        if (t.getManualType() != null) {
            return new Blocker(BlockReason.MANUAL, null, null, 1);
        }
        ClassificationRule current = t.getRuleId() == null ? null : rules.get(t.getRuleId());
        if (current == null) {
            // Nessuna regola: o un passo prima delle regole (un trasferimento), o nessuna regola corrispondeva.
            return t.getType() != null && TRANSFER_TYPES.contains(t.getType())
                    ? new Blocker(BlockReason.TRANSFER, null, null, 1)
                    : null;
        }
        if (current.getId().equals(editingId)) {
            return null; // è già lei
        }
        // Le regole si provano per priorità crescente; a parità, nell'ordine in cui sono state create (id). Una
        // regola nuova non ha ancora un id: a parità di priorità arriva dopo tutte.
        boolean before = current.getPriority() < priority
                || (current.getPriority() == priority && (editingId == null || current.getId() < editingId));
        return before ? new Blocker(BlockReason.RULE, current.getName(), current.getPriority(), 1) : null;
    }

    /** Con "tutte le parole" il testo deve avere almeno una parola: "***" non ne ha, e non troverebbe niente. */
    private static void checkPattern(RuleRequest request) {
        if (request.matchMode() == ClassificationRule.MatchMode.ALL_WORDS && ClassificationRule.words(request.pattern()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Con \"contiene tutte le parole\" scrivi almeno una parola (lettere o cifre).");
        }
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
