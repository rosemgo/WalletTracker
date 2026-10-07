package it.wallettracker.transaction;

import it.wallettracker.classification.ClassificationService;
import it.wallettracker.web.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Le operazioni sui movimenti richieste dalla dashboard: elenco e correzione manuale. */
@Service
public class TransactionService {

    /** Il massimo di movimenti per pagina: oltre, una richiesta potrebbe diventare pesante. */
    static final int MAX_PAGE_SIZE = 200;

    private final BankTransactionRepository repository;
    private final ClassificationService classificationService;

    public TransactionService(BankTransactionRepository repository, ClassificationService classificationService) {
        this.repository = repository;
        this.classificationService = classificationService;
    }

    /**
     * Una pagina di movimenti, dal più recente.
     *
     * @param accountId solo i movimenti di questo conto; null = tutti i conti
     * @param type      solo i movimenti di questo tipo (es. TO_REVIEW); null = tutti i tipi
     */
    @Transactional(readOnly = true)
    public TransactionPageDto search(Long accountId, TransactionType type, int page, int size) {
        // PageRequest dice a Spring Data quale pagina vogliamo; Spring aggiunge da solo LIMIT e OFFSET
        // alla query, e fa una seconda query (COUNT) per sapere quanti movimenti ci sono in tutto.
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        Page<BankTransaction> result = repository.search(accountId, type, pageRequest);
        return new TransactionPageDto(
                result.getContent().stream().map(TransactionDto::from).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    /**
     * Corregge a mano il tipo di un movimento ({@code null} toglie la correzione) e riclassifica tutto.
     *
     * <p>Perché riclassificare tutto e non solo questo movimento? Perché un movimento può influenzare
     * gli altri: ad esempio, se lo segni come IGNORED, il suo "gemello" di un trasferimento resta senza
     * coppia e va ricalcolato.
     */
    @Transactional
    public TransactionDto correctType(Long id, TransactionType manualType) {
        BankTransaction transaction = repository.findById(id).orElseThrow(() -> new NotFoundException("Movimento", id));
        transaction.setManualType(manualType);
        classificationService.classifyAll();
        return TransactionDto.from(transaction);
    }
}
