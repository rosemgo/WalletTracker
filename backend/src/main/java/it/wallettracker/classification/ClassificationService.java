package it.wallettracker.classification;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.account.AccountRole;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.TransactionStatus;
import it.wallettracker.transaction.TransactionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Il motore di classificazione: decide il tipo di ogni movimento (spesa, entrata, trasferimento...).
 *
 * <p>Il motore è <b>uguale per tutti</b>: non contiene nomi di banche né abitudini di una persona.
 * Quello che cambia da utente a utente (i suoi conti, le regole, le correzioni manuali) sta nel database.
 *
 * <p>Per ogni movimento si prova, in quest'ordine, e la prima risposta vince:
 * <ol>
 *   <li><b>conto escluso</b> → IGNORED;</li>
 *   <li><b>correzione manuale</b> dell'utente → il tipo scelto;</li>
 *   <li><b>importo zero</b> (es. verifiche della carta) → IGNORED;</li>
 *   <li><b>trasferimento abbinato</b>: su un altro tuo conto c'è un movimento con lo stesso importo e
 *       segno opposto, a pochi giorni di distanza → trasferimento;</li>
 *   <li><b>IBAN di un tuo conto</b> nel movimento (es. "IBAN beneficiario IT...") → trasferimento;</li>
 *   <li><b>regole</b> della tabella classification_rule, in ordine di priorità → tipo e categoria della regola;</li>
 *   <li><b>nessun testo su un conto di investimento</b> (es. Trade Republic, che non manda descrizioni):
 *       l'unica informazione è lo scopo del conto, quindi uscita → SECURITIES_BUY, entrata → INVESTMENT_INCOME;</li>
 *   <li><b>ultima risorsa</b>: uscita → EXPENSE, entrata → INCOME.</li>
 * </ol>
 *
 * <p>Il ruolo del conto conta solo in pochi casi: EXCLUDED (conto ignorato), e INVESTMENT, che serve
 * a dare il nome giusto ai trasferimenti (versamento o prelievo) e a interpretare i movimenti che non
 * hanno nessun testo. Una spesa con una descrizione, fatta da un conto di investimento, resta una spesa.
 */
@Service
public class ClassificationService {

    /** Due movimenti di un trasferimento possono essere registrati a qualche giorno di distanza. */
    static final int TRANSFER_MAX_DAYS_APART = 3;

    private final BankTransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final ClassificationRuleRepository ruleRepository;

    public ClassificationService(BankTransactionRepository transactionRepository, AccountRepository accountRepository,
            ClassificationRuleRepository ruleRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.ruleRepository = ruleRepository;
    }

    /** Il risultato della classificazione di un movimento. */
    record Result(TransactionType type, String category, Long ruleId, Long peerId) {
    }

    /**
     * Classifica di nuovo tutti i movimenti e restituisce quanti ce ne sono per tipo.
     *
     * <p>Ricalcoliamo tutto ogni volta: è semplice, il risultato non dipende dalla storia, e una regola
     * nuova o modificata si applica subito anche ai movimenti vecchi. Per un uso personale (qualche
     * migliaio di movimenti) è questione di un attimo.
     */
    @Transactional
    public Map<TransactionType, Integer> classifyAll() {
        List<BankTransaction> transactions = transactionRepository.findAll();
        List<ClassificationRule> rules = ruleRepository.findByEnabledTrueOrderByPriorityAscIdAsc();
        Map<String, Account> accountsByIban = ownAccountsByIban();
        Map<Long, BankTransaction> peers = pairTransfers(transactions);

        Map<TransactionType, Integer> counts = new EnumMap<>(TransactionType.class);
        for (BankTransaction transaction : transactions) {
            Result result = classify(transaction, peers.get(transaction.getId()), accountsByIban, rules);
            transaction.classify(result.type(), result.category(), result.ruleId(), result.peerId());
            counts.merge(result.type(), 1, Integer::sum);
        }
        return counts;
    }

    /** La classificazione di un singolo movimento, seguendo l'ordine descritto sopra. */
    Result classify(BankTransaction transaction, BankTransaction peer, Map<String, Account> accountsByIban,
            List<ClassificationRule> rules) {
        Account account = transaction.getAccount();
        BigDecimal amount = transaction.getAmount();

        // 1. Conto escluso.
        if (account.getRole() == AccountRole.EXCLUDED) {
            return new Result(TransactionType.IGNORED, null, null, null);
        }
        // 2. Correzione manuale.
        if (transaction.getManualType() != null) {
            return new Result(transaction.getManualType(), null, null, null);
        }
        // 3. Importo zero.
        if (amount.signum() == 0) {
            return new Result(TransactionType.IGNORED, null, null, null);
        }
        // 4. Trasferimento abbinato a un movimento su un altro tuo conto.
        if (peer != null) {
            return new Result(transferType(transaction, peer.getAccount()), null, null, peer.getId());
        }
        // 5. Il movimento cita l'IBAN di un altro tuo conto.
        Account mentioned = mentionedOwnAccount(transaction, accountsByIban);
        if (mentioned != null) {
            return new Result(transferType(transaction, mentioned), null, null, null);
        }
        // 6. Regole.
        String text = textOf(transaction);
        for (ClassificationRule rule : rules) {
            if (rule.matches(text, amount)) {
                return new Result(rule.getResultType(), rule.getCategory(), rule.getId(), null);
            }
        }
        // 7. Nessun testo su un conto di investimento: l'unica informazione è lo scopo del conto.
        if (text.isBlank() && account.getRole() == AccountRole.INVESTMENT) {
            return new Result(amount.signum() < 0 ? TransactionType.SECURITIES_BUY : TransactionType.INVESTMENT_INCOME,
                    null, null, null);
        }
        // 8. Ultima risorsa.
        return new Result(amount.signum() < 0 ? TransactionType.EXPENSE : TransactionType.INCOME, null, null, null);
    }

    /**
     * Che tipo di trasferimento è, guardando da dove partono e dove arrivano i soldi:
     * verso un conto di investimento = versamento, da un conto di investimento = prelievo, altrimenti interno.
     */
    static TransactionType transferType(BankTransaction transaction, Account otherAccount) {
        boolean outgoing = transaction.getAmount().signum() < 0;
        Account from = outgoing ? transaction.getAccount() : otherAccount;
        Account to = outgoing ? otherAccount : transaction.getAccount();

        boolean fromInvestment = from.getRole() == AccountRole.INVESTMENT;
        boolean toInvestment = to.getRole() == AccountRole.INVESTMENT;
        if (toInvestment && !fromInvestment) {
            return TransactionType.INVESTMENT_DEPOSIT;
        }
        if (fromInvestment && !toInvestment) {
            return TransactionType.INVESTMENT_WITHDRAWAL;
        }
        return TransactionType.INTERNAL_TRANSFER;
    }

    /**
     * Abbina le due metà dei trasferimenti tra i tuoi conti: per ogni uscita cerca un'entrata con lo
     * stesso importo (in valore assoluto) e la stessa valuta, su un altro conto, al massimo a
     * {@link #TRANSFER_MAX_DAYS_APART} giorni di distanza. Se ce ne sono più di una, prende la più vicina.
     *
     * @return per ogni movimento abbinato (per id), il movimento dall'altra parte
     */
    static Map<Long, BankTransaction> pairTransfers(List<BankTransaction> transactions) {
        // Le entrate candidate, raggruppate per importo: così per ogni uscita guardiamo solo quelle dello stesso importo.
        Map<String, List<BankTransaction>> incomingByAmount = new HashMap<>();
        List<BankTransaction> outgoing = new ArrayList<>();
        for (BankTransaction transaction : transactions) {
            if (!canBePaired(transaction)) {
                continue;
            }
            if (transaction.getAmount().signum() > 0) {
                incomingByAmount.computeIfAbsent(amountKey(transaction), key -> new ArrayList<>()).add(transaction);
            } else {
                outgoing.add(transaction);
            }
        }
        // Ordine fisso (data, poi id): lo stesso input dà sempre gli stessi abbinamenti.
        outgoing.sort(Comparator.comparing(BankTransaction::getBookingDate).thenComparing(BankTransaction::getId));

        Map<Long, BankTransaction> peers = new HashMap<>();
        Set<Long> used = new HashSet<>();
        for (BankTransaction out : outgoing) {
            BankTransaction best = null;
            long bestDistance = Long.MAX_VALUE;
            for (BankTransaction in : incomingByAmount.getOrDefault(amountKey(out), List.of())) {
                if (used.contains(in.getId()) || in.getAccount().getId().equals(out.getAccount().getId())) {
                    continue;
                }
                long distance = Math.abs(ChronoUnit.DAYS.between(out.getBookingDate(), in.getBookingDate()));
                if (distance <= TRANSFER_MAX_DAYS_APART && distance < bestDistance) {
                    best = in;
                    bestDistance = distance;
                }
            }
            if (best != null) {
                used.add(best.getId());
                peers.put(out.getId(), best);
                peers.put(best.getId(), out);
            }
        }
        return peers;
    }

    /** Si abbinano solo movimenti contabilizzati, non nulli, non corretti a mano, di conti non esclusi. */
    private static boolean canBePaired(BankTransaction transaction) {
        return transaction.getStatus() == TransactionStatus.BOOKED
                && transaction.getAmount().signum() != 0
                && transaction.getManualType() == null
                && transaction.getAccount().getRole() != AccountRole.EXCLUDED;
    }

    /** Chiave per confrontare gli importi: valore assoluto + valuta (es. "2000.00 EUR"). */
    private static String amountKey(BankTransaction transaction) {
        return transaction.getAmount().abs().toPlainString() + " " + transaction.getCurrency();
    }

    /** I tuoi conti, indicizzati per IBAN (senza spazi, maiuscolo). */
    private Map<String, Account> ownAccountsByIban() {
        Map<String, Account> byIban = new HashMap<>();
        for (Account account : accountRepository.findAll()) {
            if (account.getIban() != null && !account.getIban().isBlank()) {
                byIban.putIfAbsent(normalize(account.getIban()), account);
            }
        }
        return byIban;
    }

    /**
     * Se il movimento (causale, controparte o JSON originale) cita l'IBAN di un altro tuo conto,
     * restituisce quel conto. L'IBAN del conto stesso non conta: molte banche lo ripetono nella causale.
     */
    static Account mentionedOwnAccount(BankTransaction transaction, Map<String, Account> accountsByIban) {
        String ownIban = transaction.getAccount().getIban() != null ? normalize(transaction.getAccount().getIban()) : "";
        String content = normalize(textOf(transaction) + " " + transaction.getRawJson());
        for (Map.Entry<String, Account> entry : accountsByIban.entrySet()) {
            if (!entry.getKey().equals(ownIban) && content.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Il testo su cui lavorano le regole: causale + controparte. */
    static String textOf(BankTransaction transaction) {
        String description = transaction.getDescription() != null ? transaction.getDescription() : "";
        String counterparty = transaction.getCounterparty() != null ? transaction.getCounterparty() : "";
        return (description + " " + counterparty).trim();
    }

    private static String normalize(String text) {
        return text.replace(" ", "").toUpperCase();
    }
}
