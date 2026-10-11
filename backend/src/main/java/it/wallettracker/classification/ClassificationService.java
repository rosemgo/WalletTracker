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
import java.util.stream.Collectors;

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
 *       segno opposto, a pochi giorni di distanza, ed entrambi hanno l'aspetto di un trasferimento
 *       (vedi {@link #looksLikeOwnTransfer}) → trasferimento;</li>
 *   <li><b>saldo della carta di credito</b>: un'uscita il cui importo è esattamente il totale di un
 *       periodo di movimenti di un conto CREDIT_CARD (vedi {@link #findCardSettlements}) → CARD_SETTLEMENT;</li>
 *   <li><b>IBAN di un tuo conto</b> nel movimento (es. "IBAN beneficiario IT...") → trasferimento;</li>
 *   <li><b>regole</b> della tabella classification_rule, in ordine di priorità → tipo e categoria della regola;</li>
 *   <li><b>nessun testo</b> (né causale né controparte, es. Trade Republic) e nessun abbinamento: non
 *       sappiamo cos'è, quindi → TO_REVIEW, da classificare a mano;</li>
 *   <li><b>ultima risorsa</b>: uscita → EXPENSE, entrata → INCOME.</li>
 * </ol>
 *
 * <p>Il ruolo del conto conta solo in pochi casi: EXCLUDED (conto ignorato), INVESTMENT e CREDIT_CARD,
 * che servono a dare il nome giusto ai trasferimenti (versamento, prelievo, saldo della carta) e a
 * riconoscere il saldo della carta. Non decide mai il tipo di un movimento "normale": una spesa fatta
 * da un conto di investimento resta una spesa.
 */
@Service
public class ClassificationService {

    /** Due movimenti di un trasferimento possono essere registrati a qualche giorno di distanza. */
    static final int TRANSFER_MAX_DAYS_APART = 3;

    /** Il saldo della carta viene addebitato al massimo 45 giorni dopo l'ultimo movimento del periodo. */
    static final int SETTLEMENT_MAX_DAYS_AFTER = 45;

    /** Un periodo della carta (da un estratto conto al successivo) dura al massimo 45 giorni. */
    static final int CARD_PERIOD_MAX_DAYS = 45;

    /**
     * Almeno 2 movimenti della carta: altrimenti un singolo acquisto da 20 € e un'uscita da 20 € sul conto
     * corrente sembrerebbero un saldo per pura coincidenza.
     */
    static final int CARD_PERIOD_MIN_TRANSACTIONS = 2;

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
        List<Account> accounts = accountRepository.findAll();
        Map<String, Account> accountsByIban = ownAccountsByIban(accounts);
        List<Set<String>> ownerNames = ownerNames(accounts);
        Map<Long, BankTransaction> peers = pairTransfers(transactions, ownerNames, accountsByIban.keySet());
        Set<Long> cardSettlements = findCardSettlements(transactions);

        Map<TransactionType, Integer> counts = new EnumMap<>(TransactionType.class);
        for (BankTransaction transaction : transactions) {
            Result result = classify(transaction, peers.get(transaction.getId()),
                    cardSettlements.contains(transaction.getId()), accountsByIban, rules);
            transaction.classify(result.type(), result.category(), result.ruleId(), result.peerId());
            counts.merge(result.type(), 1, Integer::sum);
        }
        return counts;
    }

    /** La classificazione di un singolo movimento, seguendo l'ordine descritto sopra. */
    Result classify(BankTransaction transaction, BankTransaction peer, boolean cardSettlement,
            Map<String, Account> accountsByIban, List<ClassificationRule> rules) {
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
        // 4b. Saldo della carta di credito, riconosciuto dal totale dei movimenti della carta.
        if (cardSettlement) {
            return new Result(TransactionType.CARD_SETTLEMENT, null, null, null);
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
        // 7. Nessun testo: non abbiamo informazioni per decidere. Meglio chiedere all'utente che indovinare.
        if (text.isBlank()) {
            return new Result(TransactionType.TO_REVIEW, null, null, null);
        }
        // 8. Ultima risorsa.
        return new Result(amount.signum() < 0 ? TransactionType.EXPENSE : TransactionType.INCOME, null, null, null);
    }

    /**
     * Che tipo di trasferimento è, guardando da dove partono e dove arrivano i soldi:
     * da o verso una carta di credito = saldo della carta, verso un conto di investimento = versamento,
     * da un conto di investimento = prelievo, altrimenti interno.
     */
    static TransactionType transferType(BankTransaction transaction, Account otherAccount) {
        boolean outgoing = transaction.getAmount().signum() < 0;
        Account from = outgoing ? transaction.getAccount() : otherAccount;
        Account to = outgoing ? otherAccount : transaction.getAccount();

        if (from.getRole() == AccountRole.CREDIT_CARD || to.getRole() == AccountRole.CREDIT_CARD) {
            return TransactionType.CARD_SETTLEMENT;
        }

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
     * <p>Per evitare abbinamenti per coincidenza (un acquisto da 20 € e un'entrata da 20 € nello stesso
     * periodo), si abbinano solo movimenti che hanno l'aspetto di un trasferimento tra conti propri.
     *
     * <p>Eccezione: un'entrata su una <b>carta di credito</b> è sempre un candidato, qualunque sia il suo
     * testo. Su una carta le entrate sono solo pagamenti del saldo o rimborsi; quindi un'uscita dello
     * stesso importo da un altro tuo conto, pochi giorni prima o dopo, è il pagamento del saldo. (Se per
     * coincidenza fosse un rimborso, i totali non cambierebbero: acquisto e rimborso si annullano.)
     *
     * @return per ogni movimento abbinato (per id), il movimento dall'altra parte
     */
    static Map<Long, BankTransaction> pairTransfers(List<BankTransaction> transactions, List<Set<String>> ownerNames,
            Set<String> ownIbans) {
        // Le entrate candidate, raggruppate per importo: così per ogni uscita guardiamo solo quelle dello stesso importo.
        Map<String, List<BankTransaction>> incomingByAmount = new HashMap<>();
        List<BankTransaction> outgoing = new ArrayList<>();
        Set<Long> transferLike = new HashSet<>();
        for (BankTransaction transaction : transactions) {
            if (!canBePaired(transaction)) {
                continue;
            }
            boolean looksLikeTransfer = looksLikeOwnTransfer(transaction, ownerNames, ownIbans);
            if (looksLikeTransfer) {
                transferLike.add(transaction.getId());
            }
            if (transaction.getAmount().signum() > 0) {
                if (looksLikeTransfer || isOnCreditCard(transaction)) {
                    incomingByAmount.computeIfAbsent(amountKey(transaction), key -> new ArrayList<>()).add(transaction);
                }
            } else {
                // Tutte le uscite sono candidate: quelle senza l'aspetto di un trasferimento possono essere
                // abbinate solo a un'entrata su una carta di credito (vedi sotto).
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
                // Due movimenti "normali" si abbinano solo se entrambi hanno l'aspetto di un trasferimento;
                // il pagamento del saldo di una carta basta che arrivi sulla carta.
                boolean bothLikeTransfer = transferLike.contains(out.getId()) && transferLike.contains(in.getId());
                if (!bothLikeTransfer && !isOnCreditCard(in)) {
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

    private static boolean isOnCreditCard(BankTransaction transaction) {
        return transaction.getAccount().getRole() == AccountRole.CREDIT_CARD;
    }

    /**
     * Riconosce il pagamento del saldo di una carta di credito quando la carta <b>non</b> mostra il pagamento
     * (succede, ad esempio, con le carte di credito ING: sulla carta ci sono solo gli acquisti).
     *
     * <p>L'idea: il saldo addebitato sul conto corrente è la somma dei movimenti della carta di un periodo
     * (da un estratto conto al successivo). Quindi un'uscita da un altro tuo conto è un saldo se il suo
     * importo è <b>esattamente</b> uguale, al centesimo, alla somma di un gruppo di movimenti consecutivi
     * della carta che:
     * <ul>
     *   <li>sono almeno {@link #CARD_PERIOD_MIN_TRANSACTIONS};</li>
     *   <li>coprono al massimo {@link #CARD_PERIOD_MAX_DAYS} giorni;</li>
     *   <li>finiscono prima dell'uscita, al massimo {@link #SETTLEMENT_MAX_DAYS_AFTER} giorni prima.</li>
     * </ul>
     * I rimborsi sulla carta sono positivi e riducono la somma, proprio come riducono l'estratto conto.
     *
     * <p>Come si cerca il gruppo: per ogni possibile "ultimo movimento" del periodo, si torna indietro un
     * movimento alla volta sommando gli importi, finché si superano i 45 giorni. È un doppio ciclo, ma con
     * qualche centinaio di movimenti per carta è immediato.
     *
     * @return gli id delle uscite riconosciute come saldo della carta
     */
    static Set<Long> findCardSettlements(List<BankTransaction> transactions) {
        // I movimenti contabilizzati di ogni carta di credito, raggruppati per conto e ordinati per data.
        Map<Long, List<BankTransaction>> cardTransactions = transactions.stream()
                .filter(t -> isOnCreditCard(t) && t.getStatus() == TransactionStatus.BOOKED
                        && t.getAmount().signum() != 0)
                .sorted(Comparator.comparing(BankTransaction::getBookingDate).thenComparing(BankTransaction::getId))
                .collect(Collectors.groupingBy(t -> t.getAccount().getId()));

        Set<Long> settlements = new HashSet<>();
        if (cardTransactions.isEmpty()) {
            return settlements;
        }
        for (BankTransaction candidate : transactions) {
            boolean possiblePayment = candidate.getAmount().signum() < 0
                    && candidate.getStatus() == TransactionStatus.BOOKED
                    && candidate.getManualType() == null
                    && candidate.getAccount().getRole() != AccountRole.EXCLUDED
                    && !isOnCreditCard(candidate);
            if (!possiblePayment) {
                continue;
            }
            for (List<BankTransaction> card : cardTransactions.values()) {
                if (matchesACardPeriod(candidate, card)) {
                    settlements.add(candidate.getId());
                    break;
                }
            }
        }
        return settlements;
    }

    /** True se l'importo dell'uscita è la somma esatta di un periodo di movimenti della carta (vedi sopra). */
    private static boolean matchesACardPeriod(BankTransaction payment, List<BankTransaction> card) {
        for (int last = 0; last < card.size(); last++) {
            BankTransaction lastOfPeriod = card.get(last);
            long daysBeforePayment = ChronoUnit.DAYS.between(lastOfPeriod.getBookingDate(), payment.getBookingDate());
            if (daysBeforePayment < 0 || daysBeforePayment > SETTLEMENT_MAX_DAYS_AFTER
                    || !lastOfPeriod.getCurrency().equals(payment.getCurrency())) {
                continue;
            }
            // Torniamo indietro dal movimento "last", sommando: sum = card[first] + ... + card[last].
            BigDecimal sum = BigDecimal.ZERO;
            for (int first = last; first >= 0; first--) {
                BankTransaction firstOfPeriod = card.get(first);
                if (ChronoUnit.DAYS.between(firstOfPeriod.getBookingDate(), lastOfPeriod.getBookingDate())
                        > CARD_PERIOD_MAX_DAYS) {
                    break;
                }
                sum = sum.add(firstOfPeriod.getAmount());
                int count = last - first + 1;
                // compareTo e non equals: per BigDecimal, equals considera diversi 10.5 e 10.50.
                if (count >= CARD_PERIOD_MIN_TRANSACTIONS && sum.compareTo(payment.getAmount()) == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Si abbinano solo movimenti contabilizzati, non nulli, non corretti a mano, di conti non esclusi. */
    private static boolean canBePaired(BankTransaction transaction) {
        return transaction.getStatus() == TransactionStatus.BOOKED
                && transaction.getAmount().signum() != 0
                && transaction.getManualType() == null
                && transaction.getAccount().getRole() != AccountRole.EXCLUDED;
    }

    /**
     * Un movimento "ha l'aspetto" di un trasferimento tra conti propri se il suo testo:
     * <ul>
     *   <li>è vuoto (alcune banche, come Trade Republic, non mandano descrizioni), oppure</li>
     *   <li>contiene il nome dell'intestatario di uno dei tuoi conti (es. "A favore di Mario Rossi",
     *       "Payment from Mario Rossi": in un bonifico tra conti propri ordinante e beneficiario sei tu), oppure</li>
     *   <li>contiene l'IBAN di uno dei tuoi conti.</li>
     * </ul>
     * Un acquisto ("CONAD ROMA") non soddisfa nessuna delle tre condizioni.
     */
    static boolean looksLikeOwnTransfer(BankTransaction transaction, List<Set<String>> ownerNames,
            Set<String> ownIbans) {
        String text = textOf(transaction);
        if (text.isBlank()) {
            return true;
        }
        Set<String> words = words(text);
        for (Set<String> name : ownerNames) {
            if (words.containsAll(name)) {
                return true;
            }
        }
        String compact = normalize(text);
        for (String iban : ownIbans) {
            if (compact.contains(iban)) {
                return true;
            }
        }
        return false;
    }

    /**
     * I nomi degli intestatari dei tuoi conti, come insiemi di parole: "ROSSI MARIO" e "Mario Rossi"
     * diventano lo stesso insieme {MARIO, ROSSI}, così l'ordine non conta. Si usano solo nomi di almeno
     * due parole, per non confondere un nome generico (es. "Conto") con un intestatario.
     */
    static List<Set<String>> ownerNames(List<Account> accounts) {
        List<Set<String>> names = new ArrayList<>();
        for (Account account : accounts) {
            if (account.getName() == null) {
                continue;
            }
            Set<String> name = words(account.getName());
            if (name.size() >= 2 && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /** Le parole di un testo, in maiuscolo, senza punteggiatura. */
    private static Set<String> words(String text) {
        Set<String> words = new HashSet<>();
        for (String word : text.toUpperCase().split("[^\\p{L}\\p{N}]+")) {
            if (!word.isBlank()) {
                words.add(word);
            }
        }
        return words;
    }

    /** Chiave per confrontare gli importi: valore assoluto + valuta (es. "2000.00 EUR"). */
    private static String amountKey(BankTransaction transaction) {
        return transaction.getAmount().abs().toPlainString() + " " + transaction.getCurrency();
    }

    /** I tuoi conti, indicizzati per IBAN (senza spazi, maiuscolo). */
    private static Map<String, Account> ownAccountsByIban(List<Account> accounts) {
        Map<String, Account> byIban = new HashMap<>();
        for (Account account : accounts) {
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
