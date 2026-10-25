package it.wallettracker.transaction;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

import it.wallettracker.category.CategoryService;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.web.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Le operazioni sui movimenti richieste dalla dashboard: elenco e correzione manuale. */
@Service
public class TransactionService {

    /** Il massimo di movimenti per pagina: oltre, una richiesta potrebbe diventare pesante. */
    static final int MAX_PAGE_SIZE = 200;

    /** Il testo da cercare più lungo che accettiamo: nessuna causale ne ha bisogno di più. */
    static final int MAX_SEARCH_LENGTH = 100;

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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La data di inizio è dopo quella di fine.");
        }
        // PageRequest dice a Spring Data quale pagina vogliamo; Spring aggiunge da solo LIMIT e OFFSET
        // alla query, e fa una seconda query (COUNT) per sapere quanti movimenti ci sono in tutto.
        PageRequest pageRequest = PageRequest.of( Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE) );
        String text = filter.text() == null ? "" : filter.text().strip();
        if (text.length() > MAX_SEARCH_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Il testo da cercare è troppo lungo: al massimo " + MAX_SEARCH_LENGTH + " caratteri.");
        }
        BigDecimal amount = amountIn(text);
        Page<BankTransaction> result = repository.search(filter.accountId(), filter.type(), filter.uncategorized(),
                filter.from(), filter.to(), !text.isEmpty(), likePattern(text), amount != null,
                amount != null ? amount : BigDecimal.ZERO, pageRequest);
        return new TransactionPageDto(
                result.getContent().stream().map(TransactionDto::from).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    /**
     * Il testo cercato come modello per LIKE: in minuscolo, tra due {@code %} ("contiene"). I caratteri che per LIKE
     * hanno un significato ({@code %} "qualsiasi testo", {@code _} "un carattere qualsiasi") e il carattere di
     * escape {@code \} si proteggono con un {@code \} davanti: cercando "100%" si trova "100%", non "100" seguito da
     * qualsiasi cosa. Il testo arriva come parametro, mai incollato nella query: niente SQL injection.
     */
    static String likePattern(String text) {
        String escaped = text.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /** Un importo scritto all'italiana o all'inglese: "12,99", "12.99", "1.234,56", "1234", "-45,50 €". */
    private static final Pattern AMOUNT = Pattern.compile(
            "-?(\\d{1,3}(\\.\\d{3})+(,\\d{1,2})?|\\d+([.,]\\d{1,2})?)");

    /**
     * Se il testo cercato è un importo, il suo valore (senza segno: la ricerca trova sia le spese sia le entrate);
     * altrimenti null. "1.234" è milleduecentotrentaquattro: un importo ha due decimali, non tre.
     */
    static BigDecimal amountIn(String text) {
        String cleaned = text.replace("€", "").replace(" ", "");
        if (!AMOUNT.matcher(cleaned).matches()) {
            return null;
        }
        String number = cleaned.contains(",") || cleaned.matches("-?\\d{1,3}(\\.\\d{3})+")
                ? cleaned.replace(".", "").replace(",", ".")
                : cleaned;
        return new BigDecimal(number).abs();
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

    /**
     * Sceglie a mano la categoria di un movimento, tra quelle dell'elenco ({@code null} o vuota la toglie:
     * torna a deciderla la regola, se c'è).
     */
    @Transactional
    public TransactionDto correctCategory(Long id, Long categoryId) {
        BankTransaction transaction = repository.findById(id).orElseThrow(() -> new NotFoundException("Movimento", id));
        // La categoria dell'elenco con quell'id (400 se non esiste); null toglie la scelta.
        transaction.setManualCategory(categoryService.require(categoryId));
        // Riclassifichiamo per ritrovare la categoria della regola quando la scelta manuale viene tolta.
        classificationService.classifyAll();
        return TransactionDto.from(transaction);
    }
}
