package it.wallettracker.account;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Le API REST dei conti.
 *
 * <ul>
 *   <li>{@code @RestController}: ogni metodo risponde a una richiesta HTTP, e quello che restituisce
 *       viene trasformato in JSON da Jackson;</li>
 *   <li>{@code @RequestMapping("/api/accounts")}: il prefisso comune a tutti gli indirizzi della classe;</li>
 *   <li>{@code @GetMapping}, {@code @PutMapping}: il metodo HTTP e il resto dell'indirizzo.</li>
 * </ul>
 * Il controller resta "sottile": legge la richiesta, chiama il servizio e restituisce il risultato.
 * La logica sta nel servizio.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** {@code GET /api/accounts}: tutti i conti. */
    @GetMapping
    public List<AccountDto> list() {
        return accountService.allAccounts();
    }

    /**
     * {@code PUT /api/accounts/{id}/role} con corpo {@code {"role": "INVESTMENT"}}: cambia il ruolo di un conto.
     *
     * <ul>
     *   <li>{@code @PathVariable}: il valore di {@code {id}} nell'indirizzo;</li>
     *   <li>{@code @RequestBody}: il corpo JSON della richiesta, trasformato in un {@link RoleChange};</li>
     *   <li>{@code @Valid}: controlla le annotazioni del record (qui {@code @NotNull}); se non sono
     *       rispettate, Spring risponde 400 Bad Request senza chiamare il metodo.</li>
     * </ul>
     */
    @PutMapping("/{id}/role")
    public AccountDto changeRole(@PathVariable Long id, @Valid @RequestBody RoleChange change) {
        return accountService.changeRole(id, change.role());
    }

    /** Il corpo della richiesta di cambio ruolo. */
    public record RoleChange(@NotNull AccountRole role) {
    }

    /**
     * {@code POST /api/accounts}: crea un conto manuale, i cui movimenti arrivano da file CSV (es. Trade Republic).
     * Risponde 201 Created con il conto.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountDto createManual(@Valid @RequestBody ManualAccountRequest request) {
        return accountService.createManual(request.bankName(), request.name(), request.iban(), request.currency(),
                request.role() != null ? request.role() : AccountRole.UNASSIGNED);
    }

    /** {@code DELETE /api/accounts/{id}}: elimina un conto manuale e i suoi movimenti (204). */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteManual(@PathVariable Long id) {
        accountService.deleteManual(id);
    }

    /**
     * Il corpo della creazione di un conto manuale. L'IBAN è facoltativo: se c'è, lettere e cifre (gli spazi
     * vengono tolti), da 15 a 34 caratteri come gli IBAN veri.
     */
    public record ManualAccountRequest(
            @NotBlank @Size(max = 100) String bankName,
            @NotBlank @Size(max = 200) String name,
            @Pattern(regexp = "^$|^[A-Za-z]{2}[0-9A-Za-z ]{13,40}$") String iban,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
            AccountRole role) {
    }
}
