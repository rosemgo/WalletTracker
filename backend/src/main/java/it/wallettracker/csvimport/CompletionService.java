package it.wallettracker.csvimport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.TransactionStatus;
import it.wallettracker.web.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Completa i movimenti di un conto <b>collegato</b> con il file della banca (docs/31). Trade Republic, con Enable
 * Banking, manda solo data e importo: senza testo i movimenti restano "Da verificare". Il suo file di esportazione
 * ha controparte e causale; qui le copiamo nei movimenti già arrivati. Non aggiungiamo movimenti: sarebbero
 * doppioni di quelli che la sincronizzazione porta da sola.
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

    public CompletionService(AccountRepository accountRepository, BankTransactionRepository transactionRepository,
            ClassificationService classificationService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.classificationService = classificationService;
    }

    /** Il file letto e abbinato ai movimenti del conto. */
    private record Plan(TradeRepublicCsv.Reading reading, LocalDate from, LocalDate to,
            Map<Long, BankTransaction> movements, Matching matching) {
    }

    @Transactional(readOnly = true)
    public CompletionPreview preview(byte[] content, Long accountId) {
        Plan plan = plan(content, accountId);
        List<Completion> toComplete = new ArrayList<>();
        int alreadyComplete = 0;
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
        List<FileRow> rowsWithoutMovement = plan.matching().unmatchedRows().stream()
                .map(r -> new FileRow(r.line(), r.date(), r.amount(), r.currency(), r.description()))
                .toList();
        List<Movement> movementsWithoutRow = plan.matching().unusedIds().stream()
                .map(plan.movements()::get)
                .filter(t -> !t.getBookingDate().isBefore(plan.from()) && !t.getBookingDate().isAfter(plan.to()))
                .map(t -> new Movement(t.getId(), t.getBookingDate(), t.getAmount(), t.getCurrency()))
                .toList();
        return new CompletionPreview(plan.from(), plan.to(), plan.reading().rows().size(), plan.reading().skipped(),
                toComplete, alreadyComplete, rowsWithoutMovement, movementsWithoutRow);
    }

    /** Completa i movimenti abbinati, poi li riclassifica: con il testo, regole e suggerimenti possono lavorare. */
    @Transactional
    public CompletionResult complete(byte[] content, Long accountId) {
        Plan plan = plan(content, accountId);
        int completed = 0;
        int alreadyComplete = 0;
        for (Map.Entry<TradeRepublicCsv.Row, Long> pair : plan.matching().pairs().entrySet()) {
            TradeRepublicCsv.Row row = pair.getKey();
            if (plan.movements().get(pair.getValue()).completeDetails(row.counterparty(), row.description())) {
                completed++;
            } else {
                alreadyComplete++;
            }
        }
        transactionRepository.flush();
        if (completed > 0) {
            classificationService.classifyAll();
        }
        return new CompletionResult(completed, alreadyComplete);
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
        LocalDate from = reading.rows().stream().map(TradeRepublicCsv.Row::date).min(LocalDate::compareTo).orElseThrow();
        LocalDate to = reading.rows().stream().map(TradeRepublicCsv.Row::date).max(LocalDate::compareTo).orElseThrow();
        // Solo i movimenti contabilizzati: quelli in attesa vengono cancellati e riscaricati a ogni
        // aggiornamento, e il testo aggiunto andrebbe perso.
        Map<Long, BankTransaction> movements = transactionRepository
                .findByAccountAndStatusAndBookingDateBetweenOrderByBookingDateAscIdAsc(account,
                        TransactionStatus.BOOKED, from.minusDays(DAYS_TOLERANCE), to.plusDays(DAYS_TOLERANCE))
                .stream()
                .collect(Collectors.toMap(BankTransaction::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<Candidate> candidates = movements.values().stream()
                .map(t -> new Candidate(t.getId(), t.getBookingDate(), t.getAmount(), t.getCurrency()))
                .toList();
        return new Plan(reading, from, to, movements, match(reading.rows(), candidates));
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
