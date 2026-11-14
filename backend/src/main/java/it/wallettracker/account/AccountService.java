package it.wallettracker.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import it.wallettracker.classification.ClassificationService;
import it.wallettracker.logo.AccountLogo;
import it.wallettracker.logo.AccountLogoRepository;
import it.wallettracker.message.ApiException;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.web.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Le operazioni sui conti richieste dalla dashboard. */
@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final BankTransactionRepository transactionRepository;
    private final ClassificationService classificationService;
    private final AccountLogoRepository logoRepository;

    public AccountService(AccountRepository accountRepository, BankTransactionRepository transactionRepository,
            ClassificationService classificationService, AccountLogoRepository logoRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.classificationService = classificationService;
        this.logoRepository = logoRepository;
    }

    /** Tutti i conti, ordinati per banca e nome. */
    @Transactional(readOnly = true)
    public List<AccountDto> allAccounts() {
        return accountRepository.findAllWithConnection().stream()
                .map(this::dtoOf)
                .toList();
    }

    /**
     * Cambia il ruolo di un conto e riclassifica tutti i movimenti: il ruolo cambia il risultato
     * (es. un conto EXCLUDED ha tutti i movimenti IGNORED, un INVESTMENT trasforma i trasferimenti in versamenti).
     */
    @Transactional
    public AccountDto changeRole(Long id, AccountRole role) {
        // messaggio: Conto {0} non trovato
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("not-found.account", id));
        account.changeRole(role);
        // Siamo nella stessa transazione: classifyAll vede già il nuovo ruolo, anche se non è ancora
        // scritto nel database (Hibernate lo scrive prima di eseguire le query che ne hanno bisogno).
        classificationService.classifyAll();
        return dtoOf(account);
    }

    /** Dà al conto un nome scelto dall'utente; vuoto lo toglie (torna l'etichetta automatica). */
    @Transactional
    public AccountDto rename(Long id, String displayName) {
        // messaggio: Conto {0} non trovato
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("not-found.account", id));
        account.rename(displayName);
        return dtoOf(account);
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
        return dtoOf(account);
    }

    /**
     * Elimina un conto manuale con tutti i suoi movimenti, poi riclassifica (un movimento di un altro conto
     * poteva essere abbinato a uno di questi come trasferimento). I conti collegati a una banca non si
     * eliminano da qui: i loro movimenti tornerebbero alla prossima sincronizzazione.
     */
    @Transactional
    public void deleteManual(Long id) {
        // messaggio: Conto {0} non trovato
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("not-found.account", id));
        if (!account.isManual()) {
            // messaggio: Si possono eliminare solo i conti manuali (caricati da un file).
            throw new ApiException(HttpStatus.BAD_REQUEST, "account.delete-only-manual");
        }
        transactionRepository.deleteByAccount(account);
        accountRepository.delete(account);
        accountRepository.flush(); // la cancellazione va scritta prima che classifyAll rilegga i conti
        classificationService.classifyAll();
    }

    /**
     * Scrive il saldo di un conto (docs/saldi.md, parti C ed E): quello che la banca mostra dopo l'ultimo movimento
     * nell'app. Il giorno di partenza è quello dell'ultimo movimento (oggi, se il conto non ne ha ancora); da lì in
     * avanti il saldo si aggiorna con i movimenti arrivati dopo, da un file o dalla sincronizzazione. Con
     * {@code balance} null il saldo si toglie.
     *
     * <p>Vale per i conti manuali e per i collegati di cui la banca non dà il saldo (Trade Republic, a volte Fineco).
     * Se la banca il saldo lo dà, vince lei: scriverlo a mano vorrebbe dire avere due saldi diversi.
     */
    @Transactional
    public AccountDto changeBalance(Long id, BigDecimal balance, Instant now) {
        // messaggio: Conto {0} non trovato
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("not-found.account", id));
        if (account.getRole() == AccountRole.CREDIT_CARD) {
            // messaggio: Per una carta di credito non c'è un saldo: si mostra il plafond disponibile.
            throw new ApiException(HttpStatus.CONFLICT, "account.balance-credit-card");
        }
        if (account.getBalanceSource() == BalanceSource.BANK) {
            // messaggio: Il saldo di questo conto lo dà la banca, a ogni aggiornamento.
            throw new ApiException(HttpStatus.CONFLICT, "account.balance-from-bank");
        }
        LocalDate last = transactionRepository.lastBookingDate(account.getId());
        LocalDate start = last != null ? last : LocalDate.ofInstant(now, ZoneId.systemDefault());
        account.changeBalance(balance, start, BalanceSource.MANUAL, now);
        return dtoOf(account);
    }

    /** Il conto per la dashboard, con il saldo di oggi. Dentro una transazione. */
    private AccountDto dtoOf(Account account) {
        CurrentBalance balance = null;
        if (account.getBalanceSource() == BalanceSource.MANUAL && account.getBalance() != null) {
            balance = CurrentBalance.of(account,
                    transactionRepository.sumAfter(account.getId(), account.getBalanceDate(),
                            account.getBalanceUpdatedAt()),
                    transactionRepository.lastBookingDate(account.getId()));
        } else {
            balance = CurrentBalance.of(account, BigDecimal.ZERO, null);
        }
        Long logoVersion = logoRepository.findById(account.getId())
                .filter(AccountLogo::hasImage)
                .map(l -> l.getCheckedAt().toEpochMilli())
                .orElse(null);
        return AccountDto.from(account, balance, logoVersion);
    }
}
