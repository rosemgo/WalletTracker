package it.wallettracker.csvimport;

import java.io.IOException;

import it.wallettracker.account.AccountRepository;
import it.wallettracker.csvimport.ImportDtos.ImportResult;
import it.wallettracker.csvimport.ImportDtos.Preview;
import it.wallettracker.web.NotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Le API dell'importazione CSV.
 * <ul>
 *   <li>{@code POST /api/imports/preview} (file, mapping facoltativa, accountId): l'anteprima, senza salvare;</li>
 *   <li>{@code POST /api/imports} (file, mapping, accountId): l'importazione.</li>
 * </ul>
 *
 * <p>Le richieste sono "multipart": come un modulo HTML con un file allegato. Ogni pezzo ha un nome:
 * {@code file} è il CSV ({@link MultipartFile}), {@code mapping} è un JSON ({@code @RequestPart} lo
 * trasforma in un {@link ImportMapping} e {@code @Valid} lo controlla). La dimensione massima del file è
 * nella configurazione ({@code spring.servlet.multipart}).
 */
@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final CsvImportService importService;
    private final AccountRepository accountRepository;

    public ImportController(CsvImportService importService, AccountRepository accountRepository) {
        this.importService = importService;
        this.accountRepository = accountRepository;
    }

    @PostMapping("/preview")
    @Transactional(readOnly = true)
    public Preview preview(@RequestPart("file") MultipartFile file,
            @Valid @RequestPart(value = "mapping", required = false) ImportMapping mapping,
            @RequestParam Long accountId) throws IOException {
        String currency = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Conto", accountId)).getCurrency();
        return importService.preview(file.getBytes(), mapping, currency);
    }

    @PostMapping
    public ImportResult importFile(@RequestPart("file") MultipartFile file,
            @Valid @RequestPart("mapping") ImportMapping mapping, @RequestParam Long accountId) throws IOException {
        return importService.importFile(file.getBytes(), accountId, mapping);
    }

    /**
     * Un file con righe sbagliate: 400 con l'elenco degli errori in più ({@code "errors": [...]}). Un
     * {@link ProblemDetail} può avere campi aggiuntivi, oltre a status e detail.
     */
    @ExceptionHandler(ImportRejectedException.class)
    public ProblemDetail rejected(ImportRejectedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setProperty("errors", e.getErrors());
        return problem;
    }
}
