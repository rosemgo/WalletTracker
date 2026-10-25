package it.wallettracker.csvimport;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.csvimport.ImportDtos.Completion;
import it.wallettracker.csvimport.ImportDtos.CompletionPreview;
import it.wallettracker.csvimport.ImportDtos.CompletionResult;
import it.wallettracker.csvimport.ImportDtos.FileRow;
import it.wallettracker.csvimport.ImportDtos.Movement;
import it.wallettracker.csvimport.ImportDtos.NewMovement;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.TransactionStatus;
import it.wallettracker.web.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Completa un conto Trade Republic <b>collegato</b> con il file della banca (docs/31). Solo Trade Republic: è l'unica
 * banca che, con Enable Banking, manda i movimenti senza causale, e ogni banca ha un file diverso. Trade Republic
 * manda solo data e importo, e uno storico corto; il suo file di esportazione ha controparte e causale, e può partire
 * da molto prima. Qui:
 * <ul>
 *   <li>le righe che corrispondono a un movimento già arrivato gli danno <b>controparte e causale</b>;</li>
 *   <li>le righe <b>più vecchie</b> del primo movimento arrivato dalla banca diventano <b>movimenti nuovi</b>: quei
 *       mesi la banca non li ha mai dati. Quelle più recenti no: le porta la sincronizzazione, e aggiungerle
 *       creerebbe doppioni. Quelle nel mezzo senza movimento si mostrano soltanto: sono un'anomalia da guardare.</li>
 * </ul>
 * I movimenti aggiunti hanno l'impronta {@code trcsv:<transaction_id di Trade Republic>}: ricaricando lo stesso
 * file non si duplicano, e la sincronizzazione li riconosce (TransactionImportService.fileMovementFor).
 *
 * <p>Come per l'importazione, in due tempi: {@link #preview} mostra che cosa succederebbe, {@link #complete} lo fa,
 * tutto o niente. Il file non viene conservato.
 */
@Service
public class CompletionService {

    /**
     * Quanti giorni di differenza accettiamo tra la data del file e quella del movimento. Le due date non sono
     * sempre la stessa: un bonifico fatto alle 23:40 del 18 settembre (ora UTC) nel file è del 19, e la banca può
     * usare la data dell'operazione o quella di contabilizzazione.
     */
    static final int DAYS_TOLERANCE = 3;

    /** Il prefisso dell'impronta dei movimenti aggiunti dal file di Trade Republic. */
    static final String FILE_KEY_PREFIX = BankTransaction.FILE_KEY_PREFIX;

    /** Un movimento candidato all'abbinamento: solo quello che serve per abbinarlo. */
    record Candidate(long id, LocalDate date, BigDecimal amount, String currency) {
    }

    /**
     * Il risultato dell'abbinamento.
     *
     * @param pairs        riga del file → id del movimento
     * @param unmatchedRows le righe senza movimento
     * @param unusedIds     i movimenti senza riga
     */
    record Matching(Map<TradeRepublicCsv.Row, Long> pairs, List<TradeRepublicCsv.Row> unmatchedRows,
            List<Long> unusedIds) {
    }

    private final AccountRepository accountRepository;
    private final BankTransactionRepository transactionRepository;
    private final ClassificationService classificationService;
    private final JsonMapper jsonMapper;

    public CompletionService(AccountRepository accountRepository, BankTransactionRepository transactionRepository,
            ClassificationService classificationService, JsonMapper jsonMapper) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.classificationService = classificationService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Il file letto e confrontato con il conto.
     *
     * @param bankFrom       il primo giorno dei movimenti arrivati dalla banca (null se non ce n'è nessuno)
     * @param alreadyAdded   le righe già aggiunte da un file caricato prima (la loro impronta c'è già)
     * @param toAdd          le righe più vecchie dello storico della banca, da aggiungere, con la loro impronta
     * @param withoutMovement le righe senza movimento che non si aggiungono (recenti, o nel periodo della banca)
     */
    private record Plan(Account account, TradeRepublicCsv.Reading reading, LocalDate from, LocalDate to,
            LocalDate bankFrom, Map<Long, BankTransaction> movements, Matching matching, int alreadyAdded,
            Map<TradeRepublicCsv.Row, String> toAdd, List<TradeRepublicCsv.Row> withoutMovement) {
    }

    @Transactional(readOnly = true)
    public CompletionPreview preview(byte[] content, Long accountId) {
        Plan plan = plan(content, accountId);
        List<Completion> toComplete = new ArrayList<>();
        int alreadyComplete = plan.alreadyAdded();
        for (Map.Entry<TradeRepublicCsv.Row, Long> pair : plan.matching().pairs().entrySet()) {
            BankTransaction t = plan.movements().get(pair.getValue());
            TradeRepublicCsv.Row row = pair.getKey();
            if (missesDetails(t, row)) {
                toComplete.add(new Completion(t.getId(), t.getBookingDate(), t.getAmount(), t.getCurrency(),
                        t.getCounterparty() != null ? t.getCounterparty() : row.counterparty(),
                        t.getDescription() != null ? t.getDescription() : row.description()));
            } else {
                alreadyComplete++;
            }
        }
        toComplete.sort(Comparator.comparing(Completion::date).thenComparing(Completion::transactionId));
        List<NewMovement> toAdd = plan.toAdd().keySet().stream()
                .map(r -> new NewMovement(r.date(), r.amount(), r.currency(), r.counterparty(), r.description()))
                .toList();
        List<FileRow> rowsWithoutMovement = plan.withoutMovement().stream()
                .map(r -> new FileRow(r.line(), r.date(), r.amount(), r.currency(), r.description()))
                .toList();
        List<Movement> movementsWithoutRow = plan.matching().unusedIds().stream()
                .map(plan.movements()::get)
                .filter(t -> !t.getBookingDate().isBefore(plan.from()) && !t.getBookingDate().isAfter(plan.to()))
                .map(t -> new Movement(t.getId(), t.getBookingDate(), t.getAmount(), t.getCurrency()))
                .toList();
        return new CompletionPreview(plan.from(), plan.to(), plan.bankFrom(), plan.reading().rows().size(),
                plan.reading().skipped(), toComplete, toAdd, alreadyComplete, rowsWithoutMovement,
                movementsWithoutRow);
    }

    /**
     * Completa i movimenti abbinati e aggiunge quelli più vecchi dello storico della banca, poi riclassifica: con il
     * testo, regole e suggerimenti possono lavorare.
     */
    @Transactional
    public CompletionResult complete(byte[] content, Long accountId) {
        Plan plan = plan(content, accountId);
        int completed = 0;
        int alreadyComplete = plan.alreadyAdded();
        for (Map.Entry<TradeRepublicCsv.Row, Long> pair : plan.matching().pairs().entrySet()) {
            TradeRepublicCsv.Row row = pair.getKey();
            if (plan.movements().get(pair.getValue()).completeDetails(row.counterparty(), row.description())) {
                completed++;
            } else {
                alreadyComplete++;
            }
        }
        List<BankTransaction> added = plan.toAdd().entrySet().stream()
                .map(e -> new BankTransaction(plan.account(), e.getValue(), TransactionStatus.BOOKED, e.getKey().date(),
                        null, e.getKey().amount(), e.getKey().currency(), e.getKey().counterparty(),
                        e.getKey().description(), jsonMapper.writeValueAsString(e.getKey().cells())))
                .toList();
        transactionRepository.saveAll(added);
        transactionRepository.flush();
        if (completed > 0 || !added.isEmpty()) {
            classificationService.classifyAll();
        }
        return new CompletionResult(completed, added.size(), alreadyComplete);
    }

    private static boolean missesDetails(BankTransaction t, TradeRepublicCsv.Row row) {
        return (t.getCounterparty() == null && row.counterparty() != null)
                || (t.getDescription() == null && row.description() != null);
    }

    private Plan plan(byte[] content, Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Conto", accountId));
        if (account.isManual()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Questo è un conto manuale: i suoi movimenti si caricano con \"Importa CSV\".");
        }
        if (!isTradeRepublic(account.getBankName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Questa pagina è per i conti Trade Republic: "
                    + "è l'unica banca che manda i movimenti senza causale, e il file che legge è il suo.");
        }
        TradeRepublicCsv.Reading reading = read(content);
        LocalDate from = reading.rows().stream().map(TradeRepublicCsv.Row::date).min(LocalDate::compareTo).orElseThrow();
        LocalDate to = reading.rows().stream().map(TradeRepublicCsv.Row::date).max(LocalDate::compareTo).orElseThrow();

        // Le righe già aggiunte da un file precedente: la loro impronta c'è già nel conto.
        Set<String> existingKeys = transactionRepository.findDedupKeysByAccount(account);
        Map<TradeRepublicCsv.Row, String> keys = fileKeys(reading.rows());
        List<TradeRepublicCsv.Row> fresh = reading.rows().stream().filter(r -> !existingKeys.contains(keys.get(r)))
                .toList();

        // I movimenti arrivati dalla banca, solo contabilizzati: quelli in attesa vengono cancellati e riscaricati a
        // ogni aggiornamento, e il testo aggiunto andrebbe perso. Quelli aggiunti da un file non si abbinano: la loro
        // riga l'abbiamo già riconosciuta dall'impronta.
        Map<Long, BankTransaction> movements = transactionRepository
                .findByAccountAndStatusAndBookingDateBetweenOrderByBookingDateAscIdAsc(account,
                        TransactionStatus.BOOKED, from.minusDays(DAYS_TOLERANCE), to.plusDays(DAYS_TOLERANCE))
                .stream()
                .filter(t -> !t.getDedupKey().startsWith(FILE_KEY_PREFIX))
                .collect(Collectors.toMap(BankTransaction::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<Candidate> candidates = movements.values().stream()
                .map(t -> new Candidate(t.getId(), t.getBookingDate(), t.getAmount(), t.getCurrency()))
                .toList();
        Matching matching = match(fresh, candidates);

        // Le righe senza movimento: si aggiungono solo quelle più vecchie dello storico della banca (con il margine
        // della tolleranza: una riga di 2 giorni prima potrebbe essere il primo movimento, con la data spostata).
        LocalDate bankFrom = transactionRepository.findFirstBankDate(account, TransactionStatus.BOOKED,
                FILE_KEY_PREFIX + "%");
        Map<TradeRepublicCsv.Row, String> toAdd = new LinkedHashMap<>();
        List<TradeRepublicCsv.Row> withoutMovement = new ArrayList<>();
        for (TradeRepublicCsv.Row row : matching.unmatchedRows()) {
            if (bankFrom != null && row.date().isBefore(bankFrom.minusDays(DAYS_TOLERANCE))) {
                toAdd.put(row, keys.get(row));
            } else {
                withoutMovement.add(row);
            }
        }
        return new Plan(account, reading, from, to, bankFrom, movements, matching,
                reading.rows().size() - fresh.size(), toAdd, withoutMovement);
    }

    /**
     * Il conto è di Trade Republic? Il nome della banca viene da Enable Banking, e può essere scritto in modi diversi
     * ("Trade Republic", "Trade Republic Bank GmbH", "TradeRepublic"): confrontiamo solo le lettere, in minuscolo.
     * Lo stesso controllo è nella pagina (completion-page.ts).
     */
    static boolean isTradeRepublic(String bankName) {
        return bankName != null && bankName.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "")
                .contains("traderepublic");
    }

    /** Legge il file, con i controlli: è di Trade Republic, non è troppo grande, ogni riga si legge. */
    private static TradeRepublicCsv.Reading read(byte[] content) {
        String text = TextDecoder.decode(content);
        List<List<String>> all;
        try {
            all = CsvReader.read(text, CsvReader.detectDelimiter(text));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (all.isEmpty() || !TradeRepublicCsv.recognizes(all.getFirst())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Il file non è un'esportazione delle "
                    + "transazioni di Trade Republic (mancano le sue colonne, come \"date\", \"amount\", \"tax\"). "
                    + "Nell'app: Profilo → Documenti → Esportazione transazioni, formato CSV.");
        }
        if (all.size() - 1 > CsvImportService.MAX_ROWS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Il file ha troppe righe: al massimo " + CsvImportService.MAX_ROWS + ".");
        }
        TradeRepublicCsv.Reading reading = TradeRepublicCsv.read(all.getFirst(), all.subList(1, all.size()));
        if (!reading.errors().isEmpty()) {
            throw new ImportRejectedException(reading.errors().size() + " righe non si possono leggere: il file "
                    + "è stato modificato? Non è stato cambiato niente.",
                    reading.errors().subList(0, Math.min(reading.errors().size(), CsvImportService.MAX_ERRORS)));
        }
        if (reading.rows().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Il file non ha movimenti di denaro.");
        }
        return reading;
    }

    /**
     * L'impronta che avrebbe ogni riga, se diventasse un movimento: {@code trcsv:} e l'identificativo di Trade
     * Republic, unico per ogni operazione. Se mancasse, un hash dei dati della riga, con un numero progressivo per
     * le righe identiche (come CsvImportService.dedupKey).
     */
    static Map<TradeRepublicCsv.Row, String> fileKeys(List<TradeRepublicCsv.Row> rows) {
        Map<TradeRepublicCsv.Row, String> keys = new HashMap<>();
        Map<String, Integer> occurrences = new HashMap<>();
        for (TradeRepublicCsv.Row row : rows) {
            if (row.transactionId() != null) {
                keys.put(row, FILE_KEY_PREFIX + row.transactionId());
            } else {
                String base = row.date() + "|" + row.amount().toPlainString() + "|" + row.currency() + "|"
                        + row.description();
                int occurrence = occurrences.merge(base, 1, Integer::sum);
                keys.put(row, FILE_KEY_PREFIX + sha256(base + "|" + occurrence));
            }
        }
        return keys;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 c'è in ogni Java: non succede
        }
    }

    /**
     * Abbina ogni riga del file a un movimento: stesso importo (con il segno) e stessa valuta, data al massimo
     * {@link #DAYS_TOLERANCE} giorni lontana, e ogni movimento al massimo una volta. Tra più movimenti possibili
     * vince quello con la data più vicina; a parità, il primo arrivato (id più piccolo). Le righe vanno in ordine
     * di data, così due bonifici uguali in giorni vicini si abbinano nello stesso ordine.
     *
     * <p>Il segno conta: Trade Republic può annullare un dividendo (−148,00) e ripagarlo (+148,00) lo stesso
     * giorno; stessa data, stesso valore assoluto, e ogni riga trova il suo movimento.
     */
    static Matching match(List<TradeRepublicCsv.Row> rows, List<Candidate> candidates) {
        List<TradeRepublicCsv.Row> ordered = rows.stream()
                .sorted(Comparator.comparing(TradeRepublicCsv.Row::date).thenComparing(TradeRepublicCsv.Row::line))
                .toList();
        Map<TradeRepublicCsv.Row, Long> pairs = new LinkedHashMap<>();
        List<TradeRepublicCsv.Row> unmatched = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        for (TradeRepublicCsv.Row row : ordered) {
            Candidate best = null;
            long bestDistance = Long.MAX_VALUE;
            for (Candidate c : candidates) {
                if (used.contains(c.id()) || c.amount().compareTo(row.amount()) != 0
                        || !c.currency().equals(row.currency())) {
                    continue;
                }
                long distance = Math.abs(ChronoUnit.DAYS.between(row.date(), c.date()));
                if (distance <= DAYS_TOLERANCE && (distance < bestDistance
                        || (distance == bestDistance && c.id() < best.id()))) {
                    best = c;
                    bestDistance = distance;
                }
            }
            if (best != null) {
                pairs.put(row, best.id());
                used.add(best.id());
            } else {
                unmatched.add(row);
            }
        }
        List<Long> unused = candidates.stream().map(Candidate::id).filter(id -> !used.contains(id)).toList();
        return new Matching(pairs, unmatched, unused);
    }
}
