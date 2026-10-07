package it.wallettracker.account;

import java.util.List;

import it.wallettracker.classification.ClassificationService;
import it.wallettracker.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Le operazioni sui conti richieste dalla dashboard. */
@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final ClassificationService classificationService;

    public AccountService(AccountRepository accountRepository, ClassificationService classificationService) {
        this.accountRepository = accountRepository;
        this.classificationService = classificationService;
    }

    /** Tutti i conti, ordinati per banca e nome. */
    @Transactional(readOnly = true)
    public List<AccountDto> allAccounts() {
        return accountRepository.findAllWithConnection().stream()
                .map(AccountDto::from)
                .toList();
    }

    /**
     * Cambia il ruolo di un conto e riclassifica tutti i movimenti: il ruolo cambia il risultato
     * (es. un conto EXCLUDED ha tutti i movimenti IGNORED, un INVESTMENT trasforma i trasferimenti in versamenti).
     */
    @Transactional
    public AccountDto changeRole(Long id, AccountRole role) {
        Account account = accountRepository.findById(id).orElseThrow(() -> new NotFoundException("Conto", id));
        account.changeRole(role);
        // Siamo nella stessa transazione: classifyAll vede già il nuovo ruolo, anche se non è ancora
        // scritto nel database (Hibernate lo scrive prima di eseguire le query che ne hanno bisogno).
        classificationService.classifyAll();
        return AccountDto.from(account);
    }
}
