package it.wallettracker.logo;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Aspsp;
import it.wallettracker.bank.enablebanking.EnableBankingClient;
import it.wallettracker.message.ApiException;
import it.wallettracker.web.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * I loghi dei conti (docs/loghi.md): quello della banca per i conti collegati, scaricato una volta; quello caricato
 * dall'utente per i conti manuali.
 */
@Service
public class LogoService {

    private static final Logger log = LoggerFactory.getLogger(LogoService.class);

    /** Una banca senza logo si cerca di nuovo dopo una settimana (magari Enable Banking l'ha aggiunto). */
    static final Duration RETRY_WITHOUT_LOGO = Duration.ofDays(7);

    private final AccountRepository accountRepository;
    private final AccountLogoRepository logoRepository;
    private final EnableBankingClient client;
    private final LogoDownloader downloader;

    public LogoService(AccountRepository accountRepository, AccountLogoRepository logoRepository,
            EnableBankingClient client, LogoDownloader downloader) {
        this.accountRepository = accountRepository;
        this.logoRepository = logoRepository;
        this.client = client;
        this.downloader = downloader;
    }

    /**
     * Scarica il logo della banca per i conti collegati che non ce l'hanno ancora (dopo un collegamento e a ogni giro
     * di sincronizzazione: di solito non c'è niente da fare). L'elenco delle banche si chiede una volta per paese; non
     * sono dati dei conti, quindi non conta nel limite PSD2 delle letture. Un errore non ferma niente: si riprova la
     * volta dopo.
     */
    public void refreshBankLogos(Instant now) {
        Map<String, List<Aspsp>> banksByCountry = new HashMap<>();
        for (Account account : accountRepository.findAllWithConnection()) {
            if (account.getConnection() == null || !needsBankLogo(account.getId(), now)) {
                continue;
            }
            try {
                String country = account.getConnection().getAspspCountry();
                List<Aspsp> banks = banksByCountry.computeIfAbsent(country, client::listAspsps);
                String bankName = account.getConnection().getAspspName();
                Optional<String> url = banks.stream()
                        .filter(b -> b.name() != null && b.name().equalsIgnoreCase(bankName))
                        .map(Aspsp::logo)
                        .filter(u -> u != null && !u.isBlank())
                        .findFirst();
                Optional<byte[]> data = url.flatMap(downloader::download);
                Optional<String> type = data.flatMap(d -> LogoImage.typeOf(d, true));
                logoRepository.save(new AccountLogo(account.getId(), AccountLogo.Source.BANK, type.orElse(null),
                        type.isPresent() ? data.get() : null, now));
                log.info("{}: logo della banca {}", account.getName(), type.isPresent() ? "scaricato" : "non trovato");
            } catch (RuntimeException e) {
                log.warn("{}: logo della banca non scaricato: {}", account.getName(), e.getMessage());
            }
        }
    }

    private boolean needsBankLogo(Long accountId, Instant now) {
        Optional<AccountLogo> logo = logoRepository.findById(accountId);
        if (logo.isEmpty()) {
            return true;
        }
        AccountLogo l = logo.get();
        return l.getSource() == AccountLogo.Source.BANK && !l.hasImage()
                && l.getCheckedAt().isBefore(now.minus(RETRY_WITHOUT_LOGO));
    }

    /** Il logo di un conto, se c'è un'immagine. */
    @Transactional(readOnly = true)
    public Optional<AccountLogo> find(Long accountId) {
        return logoRepository.findById(accountId).filter(AccountLogo::hasImage);
    }

    /**
     * Carica il logo di un conto manuale (docs/loghi.md, parte C): PNG, JPEG o WebP, al massimo 256 KB, riconosciuto
     * dai primi byte. Un conto collegato ha il logo della banca.
     */
    @Transactional
    public AccountLogo upload(Long accountId, byte[] data, Instant now) {
        Account account = manual(accountId);
        // messaggio: Il logo deve essere un'immagine PNG, JPEG o WebP, al massimo di 256 KB.
        String type = LogoImage.typeOf(data, false).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                "logo.invalid-image"));
        return logoRepository.save(new AccountLogo(account.getId(), AccountLogo.Source.UPLOAD, type, data, now));
    }

    /** Toglie il logo caricato per un conto manuale. */
    @Transactional
    public void remove(Long accountId) {
        Account account = manual(accountId);
        logoRepository.deleteById(account.getId());
    }

    private Account manual(Long accountId) {
        Account account = accountRepository.findById(accountId)
                // messaggio: Conto {0} non trovato
                .orElseThrow(() -> new NotFoundException("not-found.account", accountId));
        if (!account.isManual()) {
            // messaggio: Il logo di un conto collegato è quello della banca: si carica solo per un conto manuale.
            throw new ApiException(HttpStatus.CONFLICT, "logo.linked-account");
        }
        return account;
    }
}
