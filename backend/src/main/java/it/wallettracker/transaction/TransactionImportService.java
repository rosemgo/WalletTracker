package it.wallettracker.transaction;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import it.wallettracker.account.Account;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Party;
import it.wallettracker.bank.enablebanking.EnableBankingApi.RawTransaction;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Transaction;
import it.wallettracker.bank.enablebanking.EnableBankingClient;
import it.wallettracker.bank.enablebanking.PsuHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;

/**
 * Importa i movimenti di un conto da Enable Banking e li salva nel database <b>senza doppioni</b>.
 *
 * <p>Ogni importazione scarica di nuovo anche qualche giorno già letto (per sicurezza), quindi lo
 * stesso movimento arriva più volte. Per riconoscerlo, a ogni movimento diamo un'<b>impronta</b>
 * ({@code dedupKey}) che resta uguale tra un'importazione e l'altra. Se l'impronta c'è già nel
 * database, il movimento non viene salvato di nuovo.
 *
 * <p>Le regole (vedi anche docs/03-modello-e-regole.md):
 * <ol>
 *   <li><b>copie identiche</b> nella stessa risposta (stesso JSON, es. le pagine ripetute di
 *       Trade Republic): ne teniamo una sola;</li>
 *   <li><b>impronta</b>: l'identificativo della banca ({@code entry_reference}) se c'è, altrimenti un
 *       hash di data, importo, valuta, controparte e causale;</li>
 *   <li><b>movimenti uguali ma distinti</b> (es. due caffè da 1,20 € lo stesso giorno, con JSON
 *       diversi): all'impronta aggiungiamo un numero progressivo (#1, #2...);</li>
 *   <li><b>movimenti in attesa</b>: possono cambiare o sparire, quindi a ogni importazione cancelliamo
 *       quelli salvati e li sostituiamo con quelli attuali.</li>
 * </ol>
 */
@Service
public class TransactionImportService {

    /** Alla prima importazione di un conto chiediamo un anno di storico (la banca può darne meno). */
    static final int FIRST_IMPORT_DAYS = 365;

    /** Le importazioni successive ripartono dall'ultimo movimento salvato, meno qualche giorno di margine. */
    static final int OVERLAP_DAYS = 10;

    /**
     * Regola PSD2: senza un'autenticazione forte (SCA) recente, la banca può concedere solo gli ultimi
     * 90 giorni. Lo storico più lungo è disponibile di solito solo subito dopo il login sulla banca.
     * Usiamo 89 giorni per stare sicuri dentro il limite.
     */
    static final int DAYS_WITHOUT_RECENT_SCA = 89;

    /**
     * Alcune banche (ING, carta di credito) restituiscono al massimo 100 movimenti per richiesta, senza
     * una continuation_key per le pagine successive. Se riceviamo 100 movimenti o più, sospettiamo che
     * la risposta sia troncata e dividiamo il periodo a metà (vedi {@link #fetchInWindows}).
     */
    static final int SUSPECTED_RESPONSE_LIMIT = 100;

    private final EnableBankingClient client;
    private final BankTransactionRepository repository;

    public TransactionImportService(EnableBankingClient client, BankTransactionRepository repository) {
        this.client = client;
        this.repository = repository;
    }

    /**
     * Il risultato di un'importazione, da mostrare all'utente.
     *
     * @param from       la data di inizio usata
     * @param fromReason perché è stata scelta quella data (prima importazione, ultimo movimento salvato...)
     * @param requests   quante richieste sono state fatte alla banca
     */
    public record ImportResult(LocalDate from, String fromReason, int requests, int received, int repeatedCopies,
            int inserted, int alreadyPresent, int pending) {
    }

    /** I movimenti scaricati e il numero di richieste fatte per ottenerli. */
    private static final class Download {
        final List<RawTransaction> transactions = new ArrayList<>();
        int requests;
    }

    /**
     * Importa i movimenti di un conto.
     *
     * @param psu gli header dell'utente presente, oppure {@code null} per un'importazione in background
     */
    @Transactional
    public ImportResult importAccount(Account account, PsuHeaders psu) {
        // Da quale data scaricare (tre casi, vedi docs/06):
        //  1. prima importazione (nessun movimento salvato): oggi - FIRST_IMPORT_DAYS;
        //  2. importazioni successive: ultimo movimento salvato - OVERLAP_DAYS;
        //  3. se la banca rifiuta il periodo: oggi - DAYS_WITHOUT_RECENT_SCA (più sotto, nel catch).
        LocalDate to = LocalDate.now();
        LocalDate from;
        String fromReason;
        Optional<BankTransaction> lastBooked =
                repository.findFirstByAccountAndStatusOrderByBookingDateDesc(account, TransactionStatus.BOOKED);
        if (lastBooked.isPresent()) {
            from = lastBooked.get().getBookingDate().minusDays(OVERLAP_DAYS);
            fromReason = "ultimo movimento salvato (" + lastBooked.get().getBookingDate() + ") meno "
                    + OVERLAP_DAYS + " giorni";
        } else {
            from = to.minusDays(FIRST_IMPORT_DAYS);
            fromReason = "prima importazione: ultimi " + FIRST_IMPORT_DAYS + " giorni";
        }

        Download download = new Download();
        try {
            fetchInWindows(account.getProviderUid(), from, to, psu, download);
        } catch (RestClientResponseException e) {
            // La banca rifiuta il periodo (succede con Fineco quando il login non è recente):
            // riproviamo una volta con gli ultimi 89 giorni. Qualsiasi altro errore lo rilanciamo.
            LocalDate shorterFrom = to.minusDays(DAYS_WITHOUT_RECENT_SCA);
            if (!isWrongPeriod(e) || !from.isBefore(shorterFrom)) {
                throw e;
            }
            from = shorterFrom;
            fromReason = "la banca ha rifiutato il periodo richiesto: ultimi " + DAYS_WITHOUT_RECENT_SCA + " giorni";
            fetchInWindows(account.getProviderUid(), from, to, psu, download);
        }
        List<RawTransaction> received = download.transactions;

        // Regola 4: i movimenti in attesa vengono sostituiti ogni volta.
        repository.deleteByAccountAndStatus(account, TransactionStatus.PENDING);
        // flush() esegue subito le cancellazioni sul database. Senza, Hibernate le farebbe alla fine,
        // dopo gli inserimenti, e un nuovo movimento in attesa con la stessa impronta violerebbe il vincolo UNIQUE.
        repository.flush();

        // Regola 1: via le copie identiche.
        List<RawTransaction> unique = removeExactCopies(received);

        int inserted = 0;
        int alreadyPresent = 0;
        int pending = 0;
        Map<String, Integer> occurrences = new HashMap<>();

        for (RawTransaction raw : unique) {
            Transaction transaction = raw.transaction();
            TransactionStatus status = statusOf(transaction.status());
            if (status == null) {
                continue; // annullato, rifiutato o altro: non è un movimento vero
            }

            String dedupKey = dedupKeyOf(transaction, status, occurrences);
            if (repository.existsByAccountAndDedupKey(account, dedupKey)) {
                alreadyPresent++;
                continue;
            }

            repository.save(toEntity(account, dedupKey, status, raw));
            if (status == TransactionStatus.PENDING) {
                pending++;
            } else {
                inserted++;
            }
        }

        return new ImportResult(from, fromReason, download.requests, received.size(), received.size() - unique.size(),
                inserted, alreadyPresent, pending);
    }

    /**
     * Scarica i movimenti tra due date. Se la banca ne restituisce 100 o più, la risposta potrebbe essere
     * troncata (succede con la carta di credito ING): allora dividiamo il periodo in due metà e le chiediamo
     * separatamente. Il metodo chiama sé stesso (ricorsione) finché ogni periodo dà meno di 100 movimenti.
     *
     * <p>Dividiamo solo se la banca rispetta le date richieste. Trade Republic, ad esempio, le ignora: in
     * quel caso dividere non servirebbe e moltiplicherebbe soltanto le richieste.
     */
    private void fetchInWindows(String accountUid, LocalDate from, LocalDate to, PsuHeaders psu, Download download) {
        List<RawTransaction> response = client.getTransactions(accountUid, from, to, psu);
        download.requests++;

        boolean maybeTruncated = response.size() >= SUSPECTED_RESPONSE_LIMIT;
        boolean canSplit = from.isBefore(to) && allWithin(response, from, to);
        if (maybeTruncated && canSplit) {
            LocalDate middle = from.plusDays(ChronoUnit.DAYS.between(from, to) / 2);
            fetchInWindows(accountUid, from, middle, psu, download);
            fetchInWindows(accountUid, middle.plusDays(1), to, psu, download);
        } else {
            download.transactions.addAll(response);
        }
    }

    /** True se tutti i movimenti hanno una data compresa nel periodo richiesto. */
    private static boolean allWithin(List<RawTransaction> transactions, LocalDate from, LocalDate to) {
        for (RawTransaction raw : transactions) {
            LocalDate date = dateOf(raw.transaction());
            if (date != null && (date.isBefore(from) || date.isAfter(to))) {
                return false;
            }
        }
        return true;
    }

    /** Enable Banking risponde 422 con l'errore WRONG_TRANSACTIONS_PERIOD quando la banca rifiuta le date. */
    static boolean isWrongPeriod(RestClientResponseException e) {
        return e.getStatusCode().value() == 422 && e.getResponseBodyAsString().contains("WRONG_TRANSACTIONS_PERIOD");
    }

    /** Gli ultimi movimenti salvati di un conto, dal più recente. */
    @Transactional(readOnly = true)
    public List<BankTransaction> latestTransactions(Account account) {
        return repository.findTop15ByAccountOrderByBookingDateDescIdDesc(account);
    }

    /** Regola 1: tiene un solo movimento per ogni JSON identico (non considera i doppioni, se una banca dovesse restituire più volte lo stesso movimento), mantenendo l'ordine originale. */
    static List<RawTransaction> removeExactCopies(List<RawTransaction> transactions) {
        Map<String, RawTransaction> byJson = new LinkedHashMap<>();
        for (RawTransaction transaction : transactions) {
            byJson.putIfAbsent(transaction.json(), transaction);
        }
        return new ArrayList<>(byJson.values());
    }

    /** BOOK → contabilizzato, PDNG → in attesa; tutti gli altri stati (annullato, rifiutato...) → null. */
    static TransactionStatus statusOf(String bankStatus) {
        if ("BOOK".equals(bankStatus)) {
            return TransactionStatus.BOOKED;
        }
        if ("PDNG".equals(bankStatus)) {
            return TransactionStatus.PENDING;
        }
        return null;
    }

    /** Regole 2 e 3: l'impronta del movimento, con un numero progressivo se ce ne sono di uguali. */
    static String dedupKeyOf(Transaction transaction, TransactionStatus status, Map<String, Integer> occurrences) {
        String key;
        if (transaction.entryReference() != null && !transaction.entryReference().isBlank()) {
            key = "ref:" + transaction.entryReference();
        } else {
            key = "fp:" + sha256(dateOf(transaction) + "|" + transaction.signedAmount().toPlainString() + "|"
                    + transaction.transactionAmount().currency() + "|" + counterpartyOf(transaction) + "|"
                    + descriptionOf(transaction));
        }
        if (status == TransactionStatus.PENDING) {
            key = "pending:" + key;
        }

        // merge: se la chiave non c'è mette 1, altrimenti somma 1. Il primo resta senza numero.
        int occurrence = occurrences.merge(key, 1, Integer::sum);
        return occurrence == 1 ? key : key + "#" + occurrence;
    }

    private static BankTransaction toEntity(Account account, String dedupKey, TransactionStatus status,
            RawTransaction raw) {
        Transaction transaction = raw.transaction();
        return new BankTransaction(
                account,
                dedupKey,
                status,
                dateOf(transaction),
                transaction.valueDate(),
                // Alcune banche (Trade Republic) mandano 6 decimali: li riportiamo a 2.
                transaction.signedAmount().setScale(2, RoundingMode.HALF_UP),
                transaction.transactionAmount().currency(),
                counterpartyOf(transaction),
                descriptionOf(transaction),
                raw.json());
    }

    /** Non tutte le banche compilano tutte le date: contabile, poi valuta, poi operazione. */
    static LocalDate dateOf(Transaction transaction) {
        if (transaction.bookingDate() != null) {
            return transaction.bookingDate();
        }
        if (transaction.valueDate() != null) {
            return transaction.valueDate();
        }
        return transaction.transactionDate();
    }

    /** Per un'uscita la controparte è chi riceve i soldi (creditor), per un'entrata chi li manda (debtor). */
    static String counterpartyOf(Transaction transaction) {
        Party party = transaction.signedAmount().signum() < 0 ? transaction.creditor() : transaction.debtor();
        return party != null ? party.name() : null;
    }

    /** La causale: le banche la mandano come lista di righe, noi le uniamo. */
    static String descriptionOf(Transaction transaction) {
        if (transaction.remittanceInformation() == null || transaction.remittanceInformation().isEmpty()) {
            return null;
        }
        return String.join(" ", transaction.remittanceInformation());
    }

    private static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 è sempre disponibile in Java
        }
    }
}
