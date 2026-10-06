package it.wallettracker.transaction;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import it.wallettracker.account.Account;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Party;
import it.wallettracker.bank.enablebanking.EnableBankingApi.RawTransaction;
import it.wallettracker.bank.enablebanking.EnableBankingApi.Transaction;
import it.wallettracker.bank.enablebanking.EnableBankingClient;
import it.wallettracker.bank.enablebanking.PsuHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    static final int FIRST_IMPORT_DAYS = 100;

    /** Le importazioni successive ripartono dall'ultimo movimento salvato, meno qualche giorno di margine. */
    static final int OVERLAP_DAYS = 10;

    private final EnableBankingClient client;
    private final BankTransactionRepository repository;

    public TransactionImportService(EnableBankingClient client, BankTransactionRepository repository) {
        this.client = client;
        this.repository = repository;
    }

    /** Il risultato di un'importazione, da mostrare all'utente. */
    public record ImportResult(LocalDate from, int received, int repeatedCopies, int inserted, int alreadyPresent,
            int pending) {
    }

    /**
     * Importa i movimenti di un conto.
     *
     * @param psu gli header dell'utente presente, oppure {@code null} per un'importazione in background
     */
    @Transactional
    public ImportResult importAccount(Account account, PsuHeaders psu) {
        // Da quale data scaricare: dall'ultimo movimento salvato (meno un margine) o, la prima volta, un anno fa.
        LocalDate to = LocalDate.now();
        LocalDate from = repository.findFirstByAccountAndStatusOrderByBookingDateDesc(account, TransactionStatus.BOOKED)
                .map(last -> {
                    return last.getBookingDate().minusDays(OVERLAP_DAYS);
                })
                .orElse(to.minusDays(FIRST_IMPORT_DAYS));

        List<RawTransaction> received = client.getTransactions(account.getProviderUid(), from, to, psu);

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

        return new ImportResult(from, received.size(), received.size() - unique.size(), inserted, alreadyPresent,
                pending);
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
