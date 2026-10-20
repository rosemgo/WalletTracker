package it.wallettracker.account;

import java.util.List;

import it.wallettracker.classification.ClassificationService;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.web.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Le operazioni sui conti richieste dalla dashboard. */
@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final BankTransactionRepository transactionRepository;
    private final ClassificationService classificationService;

    public AccountService(AccountRepository accountRepository, BankTransactionRepository transactionRepository,
            ClassificationService classificationService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
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

    /** Dà al conto un nome scelto dall'utente; vuoto lo toglie (torna l'etichetta automatica). */
    @Transactional
    public AccountDto rename(Long id, String displayName) {
        Account account = accountRepository.findById(id).orElseThrow(() -> new NotFoundException("Conto", id));
        account.rename(displayName);
        return AccountDto.from(account);
    }

    /**
     * Crea un conto manuale, per i movimenti caricati da file CSV. L'IBAN è facoltativo, ma conviene
     * indicarlo: serve a riconoscere i trasferimenti verso questo conto dai tuoi conti collegati.
     */
    @Transactional
    public AccountDto createManual(String bankName, String name, String iban, String currency, AccountRole role) {
        String compactIban = iban == null || iban.isBlank() ? null : iban.replaceAll("\\s", "").toUpperCase();
        Account account = accountRepository.save(
                Account.manual(bankName.trim(), name.trim(), compactIban, currency, role));
        return AccountDto.from(account);
    }

    /**
     * Elimina un conto manuale con tutti i suoi movimenti, poi riclassifica (un movimento di un altro conto
     * poteva essere abbinato a uno di questi come trasferimento). I conti collegati a una banca non si
     * eliminano da qui: i loro movimenti tornerebbero alla prossima sincronizzazione.
     */
    @Transactional
    public void deleteManual(Long id) {
        Account account = accountRepository.findById(id).orElseThrow(() -> new NotFoundException("Conto", id));
        if (!account.isManual()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Si possono eliminare solo i conti manuali (caricati da file CSV).");
        }
        transactionRepository.deleteByAccount(account);
        accountRepository.delete(account);
        accountRepository.flush(); // la cancellazione va scritta prima che classifyAll rilegga i conti
        classificationService.classifyAll();
    }
}
