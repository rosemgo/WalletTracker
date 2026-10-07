package it.wallettracker.transaction;

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
     * {@code GET /api/transactions?accountId=3&type=TO_REVIEW&page=0&size=50}: una pagina di movimenti.
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
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return transactionService.search(accountId, type, page, size);
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
}
