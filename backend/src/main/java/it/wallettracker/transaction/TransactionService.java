package it.wallettracker.transaction;

import java.util.List;
import java.util.regex.Pattern;

import it.wallettracker.category.CategoryService;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.message.ApiException;
import it.wallettracker.web.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Le operazioni sui movimenti richieste dalla dashboard: elenco e correzione manuale. */
@Service
public class TransactionService {

    /** Il massimo di movimenti per pagina: oltre, una richiesta potrebbe diventare pesante. */
    static final int MAX_PAGE_SIZE = 200;

    /** Il testo da cercare più lungo che accettiamo: nessuna causale ne ha bisogno di più. */
    static final int MAX_SEARCH_LENGTH = 100;

    /** La nota più lunga: due righe sul telefono. Lo stesso limite è nel database (V27). */
    static final int MAX_NOTE_LENGTH = 300;

    /** I caratteri di controllo (tranne gli spazi, che diventano uno spazio solo): in una nota non hanno senso. */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\s]]");

    private final BankTransactionRepository repository;
    private final ClassificationService classificationService;
    private final CategoryService categoryService;

    public TransactionService(BankTransactionRepository repository, ClassificationService classificationService,
            CategoryService categoryService) {
        this.repository = repository;
        this.classificationService = classificationService;
        this.categoryService = categoryService;
    }

    /**
     * Una pagina di movimenti, dal più recente.
     *
     * @param accountId solo i movimenti di questo conto; null = tutti i conti
     * @param type      solo i movimenti di questo tipo (es. TO_REVIEW); null = tutti i tipi
     */
    @Transactional(readOnly = true)
    public TransactionPageDto search(TransactionFilter filter, int page, int size) {
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            // messaggio: La data di inizio è dopo quella di fine.
            throw new ApiException(HttpStatus.BAD_REQUEST, "search.dates-reversed");
        }
        String text = filter.text() == null ? "" : filter.text().strip();
        if (text.length() > MAX_SEARCH_LENGTH) {
            // messaggio: Il testo da cercare è troppo lungo: al massimo {0} caratteri.
            throw new ApiException(HttpStatus.BAD_REQUEST, "search.text-too-long", MAX_SEARCH_LENGTH);
        }
        List<String> words = TransactionSearch.words(text);
        if (words.size() > TransactionSearch.MAX_WORDS) {
            // messaggio: Troppe parole da cercare: al massimo {0}.
            throw new ApiException(HttpStatus.BAD_REQUEST, "search.too-many-words", TransactionSearch.MAX_WORDS);
        }
        // PageRequest dice a Spring Data quale pagina vogliamo e in che ordine (dal più recente; a parità di giorno,
        // dall'ultimo arrivato). Spring aggiunge da solo ORDER BY, LIMIT e OFFSET alla query, e fa una seconda query
        // (COUNT) per sapere quanti movimenti ci sono in tutto.
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("bookingDate"), Sort.Order.desc("id")));
        Page<BankTransaction> result = repository.findAll(TransactionSearch.of(filter, words), pageRequest);
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
        // messaggio: Movimento {0} non trovato
        BankTransaction transaction = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("not-found.transaction", id));
        transaction.setManualType(manualType);
        classificationService.classifyAll();
        return TransactionDto.from(transaction);
    }

    /**
     * Sceglie a mano la categoria di un movimento, tra quelle dell'elenco ({@code null} o vuota la toglie:
     * torna a deciderla la regola, se c'è).
     */
    @Transactional
    public TransactionDto correctCategory(Long id, Long categoryId) {
        // messaggio: Movimento {0} non trovato
        BankTransaction transaction = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("not-found.transaction", id));
        // La categoria dell'elenco con quell'id (400 se non esiste); null toglie la scelta.
        transaction.setManualCategory(categoryService.require(categoryId));
        // Riclassifichiamo per ritrovare la categoria della regola quando la scelta manuale viene tolta.
        classificationService.classifyAll();
        return TransactionDto.from(transaction);
    }

    /**
     * Scrive la nota di un movimento ({@code null} o solo spazi la tolgono). Gli spazi in più, compresi gli a capo,
     * diventano uno spazio solo: la nota è una riga. Oltre {@value #MAX_NOTE_LENGTH} caratteri, o con caratteri di
     * controllo, risponde 400.
     *
     * <p>La nota si mostra nella pagina come testo: Angular la "scappa" ({@code {{ }}} non interpreta mai l'HTML),
     * quindi una nota con dentro {@code <script>} resta una scritta innocua.
     */
    @Transactional
    public TransactionDto changeNote(Long id, String note) {
        // messaggio: Movimento {0} non trovato
        BankTransaction transaction = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("not-found.transaction", id));
        String cleaned = note == null ? "" : note.strip().replaceAll("\\s+", " ");
        if (CONTROL.matcher(cleaned).find()) {
            // messaggio: La nota contiene caratteri non validi.
            throw new ApiException(HttpStatus.BAD_REQUEST, "note.invalid-characters");
        }
        if (cleaned.length() > MAX_NOTE_LENGTH) {
            // messaggio: La nota è troppo lunga: al massimo {0} caratteri.
            throw new ApiException(HttpStatus.BAD_REQUEST, "note.too-long", MAX_NOTE_LENGTH);
        }
        transaction.setNote(cleaned.isEmpty() ? null : cleaned);
        return TransactionDto.from(transaction);
    }
}
