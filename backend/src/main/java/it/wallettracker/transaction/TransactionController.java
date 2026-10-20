package it.wallettracker.transaction;

import java.time.LocalDate;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Le API REST dei movimenti (vedi AccountController per le annotazioni). */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /**
     * {@code GET /api/transactions?accountId=3&type=TO_REVIEW&uncategorized=true&from=2026-10-01&to=2026-10-31&page=0&size=50}:
     * una pagina di movimenti. Tutti i filtri sono facoltativi; {@code from} e {@code to} sono giorni inclusi,
     * nel formato ISO ({@code @DateTimeFormat} trasforma "2026-10-01" in un LocalDate).
     *
     * <p>{@code @RequestParam} legge i parametri dopo il "?". Sono tutti facoltativi:
     * {@code required = false} significa "se manca, null"; {@code defaultValue} dà un valore predefinito.
     * Spring converte da solo il testo "TO_REVIEW" nell'enum {@link TransactionType}; se il valore non
     * esiste risponde 400 Bad Request.
     */
    @GetMapping
    public TransactionPageDto search(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(defaultValue = "false") boolean uncategorized,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return transactionService.search(new TransactionFilter(accountId, type, uncategorized, from, to), page, size);
    }

    /**
     * {@code PUT /api/transactions/{id}/manual-type} con corpo {@code {"type": "EXPENSE"}}: corregge il tipo.
     * Con {@code {"type": null}} la correzione viene tolta e decide di nuovo il motore.
     */
    @PutMapping("/{id}/manual-type")
    public TransactionDto correctType(@PathVariable Long id, @RequestBody ManualTypeChange change) {
        return transactionService.correctType(id, change.type());
    }

    /** Il corpo della richiesta di correzione: {@code type} può essere null. */
    public record ManualTypeChange(TransactionType type) {
    }

    /**
     * {@code PUT /api/transactions/{id}/manual-category} con corpo {@code {"categoryId": 7}}: sceglie a mano la
     * categoria. Con {@code {"categoryId": null}} la scelta viene tolta.
     */
    @PutMapping("/{id}/manual-category")
    public TransactionDto correctCategory(@PathVariable Long id, @Valid @RequestBody ManualCategoryChange change) {
        return transactionService.correctCategory(id, change.categoryId());
    }

    /** Il corpo della richiesta: l'id di una categoria dell'elenco, oppure null. */
    public record ManualCategoryChange(Long categoryId) {
    }
}
