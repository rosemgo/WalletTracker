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
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import it.wallettracker.account.Account;
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
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Il completamento di un conto <b>collegato</b> con il file di Trade Republic (docs/31). Trade Republic, con Enable
 * Banking, manda solo data e importo, e uno storico corto; il suo file di esportazione ha controparte e causale, e può
 * partire da molto prima. Lo usa la pagina Importa (CsvImportService), quando riconosce quel file in un conto
 * collegato (docs/importa-file.md, parte J: prima c'era una pagina a parte, "Completa i movimenti"). Qui:
 * <ul>
 *   <li>le righe che corrispondono a un movimento già arrivato gli danno <b>controparte e causale</b>;</li>
 *   <li>le righe <b>più vecchie</b> del primo movimento arrivato dalla banca diventano <b>movimenti nuovi</b>: quei
 *       mesi la banca non li ha mai dati. Quelle più recenti no: le porta la sincronizzazione, e aggiungerle
 *       creerebbe doppioni. Quelle nel mezzo senza movimento si mostrano soltanto: sono un'anomalia da guardare.</li>
 * </ul>
 * I movimenti aggiunti hanno l'impronta {@code trcsv:<transaction_id di Trade Republic>}: ricaricando lo stesso
 * file non si duplicano, e la sincronizzazione li riconosce (TransactionImportService.fileMovementFor).
 *
 * <p>In due tempi, come l'importazione: {@link #plan} confronta il file con il conto, {@link #preview} dice che cosa
 * succederebbe (anomalie comprese), {@link #complete} lo fa.
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

    private final BankTransactionRepository transactionRepository;
    private final ClassificationService classificationService;
    private final JsonMapper jsonMapper;

    public CompletionService(BankTransactionRepository transactionRepository,
            ClassificationService classificationService, JsonMapper jsonMapper) {
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
    record Plan(Account account, TradeRepublicCsv.Reading reading, LocalDate from, LocalDate to,
            LocalDate bankFrom, Map<Long, BankTransaction> movements, Matching matching, int alreadyAdded,
            Map<TradeRepublicCsv.Row, String> toAdd, List<TradeRepublicCsv.Row> withoutMovement) {
    }

    /** L'anteprima di un piano: che cosa si completa, che cosa si aggiunge, le anomalie. Non cambia niente. */
    CompletionPreview preview(Plan plan) {
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
     * Esegue un piano, dentro la transazione dell'importazione (CsvImportService.importFile): completa i movimenti
     * abbinati, aggiunge le righe più vecchie della banca, poi riclassifica (con il testo regole e suggerimenti
     * possono lavorare).
     */
    CompletionResult complete(Plan plan) {
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

    /**
     * Il piano per un conto collegato e il file di Trade Republic già letto (CsvImportService lo riconosce dalle colonne,
     * anche in Excel). Il file deve avere almeno una riga di denaro.
     */
    Plan plan(Account account, TradeRepublicCsv.Reading reading) {
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

        // Le righe senza movimento: si aggiungono quelle più vecchie del primo giorno della banca. Niente margine: una
        // riga di 2 giorni prima che fosse lo stesso movimento della banca con la data spostata l'ha già abbinata
        // match(), che cerca fino a DAYS_TOLERANCE giorni anche a cavallo del primo giorno. Una riga che arriva qui
        // non ha nessun movimento della banca con quell'importo vicino: è un movimento che la banca non ha mai dato, e
        // scartarla lo perderebbe (docs/31, parte M).
        LocalDate bankFrom = transactionRepository.findFirstBankDate(account, TransactionStatus.BOOKED,
                FILE_KEY_PREFIX + "%");
        Map<TradeRepublicCsv.Row, String> toAdd = new LinkedHashMap<>();
        List<TradeRepublicCsv.Row> withoutMovement = new ArrayList<>();
        for (TradeRepublicCsv.Row row : matching.unmatchedRows()) {
            if (bankFrom != null && row.date().isBefore(bankFrom)) {
                toAdd.put(row, keys.get(row));
            } else {
                withoutMovement.add(row);
            }
        }
        return new Plan(account, reading, from, to, bankFrom, movements, matching,
                reading.rows().size() - fresh.size(), toAdd, withoutMovement);
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
