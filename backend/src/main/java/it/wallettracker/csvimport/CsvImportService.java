package it.wallettracker.csvimport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.csvimport.ImportDtos.ImportResult;
import it.wallettracker.csvimport.ImportDtos.Preview;
import it.wallettracker.csvimport.ImportDtos.PreviewRow;
import it.wallettracker.csvimport.RowReader.ParsedRow;
import it.wallettracker.csvimport.RowReader.RowException;
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
 * L'importazione dei movimenti da un file (CSV o Excel), in due tempi:
 * <ol>
 *   <li>{@link #preview}: legge il file, propone come interpretare le colonne e mostra il risultato su
 *       alcune righe, con quante righe si aggiungerebbero. Non salva niente;</li>
 *   <li>{@link #importFile}: con la mappatura confermata dall'utente, salva i movimenti e li classifica, come
 *       quelli arrivati da Enable Banking.</li>
 * </ol>
 * Che cosa si aggiunge dipende dal conto (docs/importa-file.md):
 * <ul>
 *   <li><b>un conto manuale</b> (che Enable Banking non copre): tutte le righe del file, tranne quelle già
 *       importate prima;</li>
 *   <li><b>un conto collegato</b>: i movimenti li porta la banca, ma solo da un certo giorno in poi (lo storico che
 *       dà quando lo si collega). Dal file si aggiungono solo le righe <b>più vecchie</b> del primo movimento
 *       arrivato dalla banca: i mesi che la banca non ha mai dato. Le altre le porta la banca, e aggiungerle
 *       farebbe doppioni.</li>
 * </ul>
 * Il file non viene conservato sul server: il browser lo rimanda nella seconda richiesta.
 *
 * <p><b>Il file di Trade Republic</b> (l'esportazione delle transazioni) si riconosce dalle colonne e si legge con
 * {@link TradeRepublicCsv}, non con la mappatura: le righe dei titoli senza denaro si saltano e l'importo è quello
 * netto ({@code amount + fee + tax}), come sul conto (docs/importa-file.md, parte H).
 */
@Service
public class CsvImportService {

    /** Oltre questo numero di righe il file viene rifiutato: un estratto conto personale non ci arriva mai. */
    static final int MAX_ROWS = 50_000;

    /** Quante righe mostrare nell'anteprima, e quanti errori al massimo. */
    static final int PREVIEW_ROWS = 10;
    static final int MAX_ERRORS = 20;

    private final AccountRepository accountRepository;
    private final BankTransactionRepository transactionRepository;
    private final ClassificationService classificationService;
    private final JsonMapper jsonMapper;

    public CsvImportService(AccountRepository accountRepository, BankTransactionRepository transactionRepository,
            ClassificationService classificationService, JsonMapper jsonMapper) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.classificationService = classificationService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Il file letto: intestazione, righe, separatore.
     *
     * @param headerLine  il numero della riga dell'intestazione nel file (1 = la prima): per i messaggi d'errore
     * @param spreadsheet true per un file Excel (le date e i numeri hanno già un formato fisso)
     * @param tradeRepublic true per l'esportazione delle transazioni di Trade Republic: si legge senza mappatura
     */
    private record CsvFile(char delimiter, List<String> headers, List<List<String>> rows, int headerLine,
            boolean spreadsheet, boolean tradeRepublic) {
    }

    /**
     * Che cosa farebbe l'importazione, riga per riga.
     *
     * @param toAdd          le righe da aggiungere, con la loro impronta
     * @param duplicates     le righe già aggiunte da un'importazione precedente (stessa impronta)
     * @param coveredByBank  solo per i conti collegati: le righe del periodo che la banca porta da sé
     * @param bankFrom       solo per i conti collegati: il primo giorno dei movimenti arrivati dalla banca
     * @param toComplete     le righe già presenti a cui si completa la causale (seconda colonna, docs/tinaba.md)
     */
    private record Plan(Map<Integer, String> toAdd, int duplicates, int coveredByBank, LocalDate bankFrom,
            Map<String, ParsedRow> toComplete) {
    }

    /**
     * Il risultato della lettura di tutte le righe.
     *
     * @param fileIds per ogni riga letta, l'identificativo dell'operazione nel file (il {@code transaction_id} di
     *                Trade Republic), o null: se c'è, l'impronta si fa con quello (vedi {@link #keyFor})
     * @param skipped le righe saltate perché non muovono denaro (le operazioni sui titoli di Trade Republic)
     */
    private record Reading(List<ParsedRow> parsed, List<List<String>> rawRows, int errorCount, List<String> errors,
            List<String> fileIds, int skipped) {
    }

    /** L'anteprima, per il conto scelto. Senza mappatura ne propone una (ColumnGuesser). */
    @Transactional(readOnly = true)
    public Preview preview(byte[] content, ImportMapping mapping, Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Conto", accountId));
        CsvFile file = read(content, mapping);
        List<List<String>> samples = file.rows().subList(0, Math.min(file.rows().size(), PREVIEW_ROWS));
        ImportMapping used = mapping != null ? mapping
                : ColumnGuesser.guess(String.valueOf(file.delimiter()), file.headers(), samples, file.spreadsheet());
        boolean linked = !account.isManual();
        LocalDate bankFrom = linked ? bankFrom(account) : null;
        // Una mappatura incompleta (es. la proposta non ha trovato l'importo) non è un errore dell'anteprima:
        // mostriamo il file e diciamo che cosa manca, così l'utente lo sceglie.
        Reading reading;
        try {
            reading = readRows(file, used, account.getCurrency());
        } catch (ResponseStatusException e) {
            return new Preview(file.headers(), samples, file.rows().size(), used, List.of(), file.rows().size(),
                    List.of(e.getReason()), file.spreadsheet(), linked, bankFrom, 0, 0, 0, file.tradeRepublic(), 0);
        }
        // Le righe mostrate: quelle che si aggiungerebbero (le prime). Se non se ne aggiunge nessuna (o il file ha
        // errori), le prime del file, così si vede almeno come si leggono.
        List<ParsedRow> shown = reading.parsed();
        int toAdd = 0;
        int duplicates = 0;
        int covered = 0;
        if (reading.errorCount() == 0) {
            Plan plan = plan(account, reading);
            toAdd = plan.toAdd().size();
            duplicates = plan.duplicates();
            covered = plan.coveredByBank();
            if (!plan.toAdd().isEmpty()) {
                shown = plan.toAdd().keySet().stream().sorted().map(reading.parsed()::get).toList();
            }
        }
        List<PreviewRow> parsedRows = shown.stream()
                .limit(PREVIEW_ROWS)
                .map(r -> new PreviewRow(r.date(), r.amount(), r.currency(), r.fullDescription(), r.counterparty()))
                .toList();
        return new Preview(file.headers(), samples, file.rows().size(), used, parsedRows, reading.errorCount(),
                reading.errors(), file.spreadsheet(), linked, bankFrom, toAdd, duplicates, covered,
                file.tradeRepublic(), reading.skipped());
    }

    /**
     * Importa il file nel conto indicato: in un conto manuale tutte le righe nuove, in un conto collegato solo
     * quelle più vecchie dei movimenti arrivati dalla banca (vedi la classe). Tutto o niente: se anche una sola riga
     * non si legge, l'importazione viene rifiutata con l'elenco degli errori.
     */
    @Transactional
    public ImportResult importFile(byte[] content, Long accountId, ImportMapping mapping) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Conto", accountId));
        CsvFile file = read(content, mapping);
        Reading reading = readRows(file, mapping, account.getCurrency());
        if (reading.errorCount() > 0) {
            throw new ImportRejectedException(reading.errorCount() + " righe non si possono leggere: correggi la "
                    + "mappatura o il file. Non è stato importato niente.", reading.errors());
        }
        Plan plan = plan(account, reading);
        List<BankTransaction> toSave = new ArrayList<>();
        plan.toAdd().forEach((index, key) -> {
            ParsedRow row = reading.parsed().get(index);
            toSave.add(new BankTransaction(account, key, TransactionStatus.BOOKED, row.date(), null, row.amount(),
                    row.currency(), row.counterparty(), row.fullDescription(),
                    rawJson(file.headers(), reading.rawRows().get(index))));
        });
        // Le righe già presenti, importate quando la seconda colonna non si leggeva: la causale si completa.
        int completed = 0;
        if (!plan.toComplete().isEmpty()) {
            for (BankTransaction t : transactionRepository.findByAccountAndDedupKeyIn(account,
                    plan.toComplete().keySet())) {
                ParsedRow row = plan.toComplete().get(t.getDedupKey());
                if (t.extendDescription(row.description(), row.fullDescription())) {
                    completed++;
                }
            }
        }
        transactionRepository.saveAll(toSave);
        transactionRepository.flush();
        if (!toSave.isEmpty() || completed > 0) {
            classificationService.classifyAll();
        }
        return new ImportResult(toSave.size(), plan.duplicates(), completed, plan.coveredByBank());
    }

    /** Quanti giorni di differenza accettiamo tra la data del file e quella della banca (come CompletionService). */
    static final int DAYS_TOLERANCE = CompletionService.DAYS_TOLERANCE;

    /**
     * Che cosa aggiungere. In un conto manuale: ogni riga con un'impronta che non c'è ancora. In un conto collegato:
     * <ol>
     *   <li>le righe dal primo giorno della banca in poi no: le porta la banca;</li>
     *   <li>le righe dei {@link #DAYS_TOLERANCE} giorni prima possono essere lo stesso movimento con una data appena
     *       diversa: se c'è un movimento della banca con lo stesso importo e la stessa valuta, a pochi giorni, non si
     *       aggiunge;</li>
     *   <li>le altre, più vecchie, sì, con l'impronta dei movimenti presi da un file ({@code trcsv:}): se un giorno la
     *       banca desse uno storico più lungo, la sincronizzazione le riconosce invece di raddoppiarle
     *       (TransactionImportService.fileMovementFor, docs/31).</li>
     * </ol>
     */
    private Plan plan(Account account, Reading reading) {
        Set<String> existing = transactionRepository.findDedupKeysByAccount(account);
        boolean linked = !account.isManual();
        LocalDate bankFrom = null;
        List<BankTransaction> edge = List.of();
        if (linked) {
            bankFrom = bankFrom(account);
            if (bankFrom == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Questo conto non ha ancora movimenti "
                        + "arrivati dalla banca: aggiornalo prima (pagina Conti), così si sa da quale giorno li porta "
                        + "la banca e quali aggiungere dal file.");
            }
            edge = transactionRepository.findByAccountAndStatusAndBookingDateBetweenOrderByBookingDateAscIdAsc(
                    account, TransactionStatus.BOOKED, bankFrom, bankFrom.plusDays(DAYS_TOLERANCE)).stream()
                    .filter(t -> !t.getDedupKey().startsWith(BankTransaction.FILE_KEY_PREFIX))
                    .toList();
        }
        Map<String, Integer> occurrences = new HashMap<>();
        Map<Integer, String> toAdd = new LinkedHashMap<>();
        Map<String, ParsedRow> toComplete = new HashMap<>();
        Set<Long> usedEdge = new HashSet<>();
        int duplicates = 0;
        int covered = 0;
        for (int i = 0; i < reading.parsed().size(); i++) {
            ParsedRow row = reading.parsed().get(i);
            String key = keyFor(row, reading.fileIds().get(i), linked, occurrences);
            if (existing.contains(key)) {
                duplicates++;
                if (row.detail() != null) {
                    toComplete.put(key, row);
                }
                continue;
            }
            if (linked && (!row.date().isBefore(bankFrom) || matchesBank(row, edge, usedEdge))) {
                covered++;
                continue;
            }
            toAdd.put(i, key);
        }
        return new Plan(toAdd, duplicates, covered, bankFrom, toComplete);
    }

    /** Il primo giorno dei movimenti arrivati dalla banca (non quelli presi da un file), o null se non ce n'è. */
    private LocalDate bankFrom(Account account) {
        return transactionRepository.findFirstBankDate(account, TransactionStatus.BOOKED,
                BankTransaction.FILE_KEY_PREFIX + "%");
    }

    /**
     * Una riga dei giorni appena prima della banca è un movimento che la banca ha già dato, con una data diversa?
     * Stesso importo e valuta, al massimo {@link #DAYS_TOLERANCE} giorni di distanza, e ogni movimento una volta.
     */
    private static boolean matchesBank(ParsedRow row, List<BankTransaction> edge, Set<Long> used) {
        for (BankTransaction t : edge) {
            if (!used.contains(t.getId()) && t.getAmount().compareTo(row.amount()) == 0
                    && t.getCurrency().equals(row.currency())
                    && Math.abs(ChronoUnit.DAYS.between(row.date(), t.getBookingDate())) <= DAYS_TOLERANCE) {
                used.add(t.getId());
                return true;
            }
        }
        return false;
    }

    /**
     * Da byte a righe e colonne, con i controlli sul formato. Un file Excel (riconosciuto dai primi byte, non dal
     * nome) lo legge SpreadsheetReader; un CSV, CsvReader, con il separatore della mappatura se c'è (l'utente può
     * averlo corretto), altrimenti quello riconosciuto dal file. Poi si cerca la riga dell'intestazione
     * (ColumnGuesser.headerRow): le righe sopra, come le note di Fineco, si saltano.
     */
    private static CsvFile read(byte[] content, ImportMapping mapping) {
        if (SpreadsheetReader.isOldExcel(content)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "È un file Excel del vecchio formato (.xls): "
                    + "aprilo con Excel e salvalo come .xlsx, oppure come CSV.");
        }
        boolean spreadsheet = SpreadsheetReader.isSpreadsheet(content);
        char delimiter;
        List<List<String>> all;
        try {
            if (spreadsheet) {
                delimiter = mapping != null ? mapping.delimiterChar() : ';'; // per un Excel non conta
                all = SpreadsheetReader.read(content);
            } else {
                String text = TextDecoder.decode(content);
                delimiter = mapping != null ? mapping.delimiterChar() : CsvReader.detectDelimiter(text);
                all = CsvReader.read(text, delimiter);
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        int header = ColumnGuesser.headerRow(all);
        if (all.size() - header < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Il file deve avere una riga di intestazione e almeno un movimento.");
        }
        if (all.size() - header - 1 > MAX_ROWS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Il file ha troppe righe: al massimo " + MAX_ROWS + ".");
        }
        List<String> headers = all.get(header);
        return new CsvFile(delimiter, headers, all.subList(header + 1, all.size()), header + 1, spreadsheet,
                TradeRepublicCsv.recognizes(headers));
    }

    /**
     * Legge tutte le righe; gli errori riportano il numero di riga del file (contando le righe sopra l'intestazione,
     * quelle non vuote). Per un file Excel è la riga del foglio se non ci sono righe vuote in mezzo.
     */
    private static Reading readRows(CsvFile file, ImportMapping mapping, String currency) {
        if (file.tradeRepublic()) {
            return readTradeRepublic(file);
        }
        checkColumns(mapping, file.headers().size());
        List<ParsedRow> parsed = new ArrayList<>();
        List<List<String>> rawRows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> fileIds = new ArrayList<>();
        int errorCount = 0;
        for (int i = 0; i < file.rows().size(); i++) {
            try {
                parsed.add(RowReader.read(file.rows().get(i), mapping, currency));
                rawRows.add(file.rows().get(i));
                fileIds.add(null);
            } catch (RowException e) {
                errorCount++;
                if (errors.size() < MAX_ERRORS) {
                    errors.add("Riga " + (file.headerLine() + i + 1) + ": " + e.getMessage() + ".");
                }
            }
        }
        return new Reading(parsed, rawRows, errorCount, errors, fileIds, 0);
    }

    /**
     * Il file di Trade Republic, senza mappatura (TradeRepublicCsv): solo le righe che muovono denaro, con l'importo
     * netto, la causale ({@code description}) e la controparte (chi manda o riceve il bonifico; per dividendi e
     * acquisti, il titolo). Le operazioni sui titoli senza denaro (azioni trasferite, diritti) si contano e si saltano.
     */
    private static Reading readTradeRepublic(CsvFile file) {
        TradeRepublicCsv.Reading read = TradeRepublicCsv.read(file.headers(), file.rows(), file.headerLine());
        List<ParsedRow> parsed = new ArrayList<>();
        List<List<String>> rawRows = new ArrayList<>();
        List<String> fileIds = new ArrayList<>();
        for (TradeRepublicCsv.Row row : read.rows()) {
            parsed.add(new ParsedRow(row.date(), row.amount(), row.currency(), row.description(), row.counterparty(),
                    null));
            rawRows.add(file.rows().get(row.line() - file.headerLine() - 1));
            fileIds.add(row.transactionId());
        }
        List<String> errors = read.errors().subList(0, Math.min(read.errors().size(), MAX_ERRORS));
        return new Reading(parsed, rawRows, read.errors().size(), errors, fileIds, read.skipped());
    }

    /** Le colonne indispensabili devono essere indicate, e ogni colonna indicata deve esistere nel file. */
    private static void checkColumns(ImportMapping mapping, int columns) {
        if (mapping.dateColumn() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica la colonna della data.");
        }
        List<Integer> used = new ArrayList<>();
        used.add(mapping.dateColumn());
        if (mapping.amountMode() == ImportMapping.AmountMode.SINGLE) {
            if (mapping.amountColumn() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica la colonna dell'importo.");
            }
            used.add(mapping.amountColumn());
        } else if (mapping.amountMode() == ImportMapping.AmountMode.UNSIGNED) {
            // Senza segno: l'importo, la colonna in cui cercare le parole e almeno una parola (docs/tinaba.md).
            if (mapping.amountColumn() == null || mapping.signColumn() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Indica la colonna dell'importo e quella in cui cercare le parole delle entrate.");
            }
            if (mapping.incomeWordList().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Scrivi almeno una parola delle entrate (per esempio \"ricevuto\").");
            }
            used.add(mapping.amountColumn());
            used.add(mapping.signColumn());
        } else {
            if (mapping.incomeColumn() == null || mapping.expenseColumn() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Indica le colonne delle entrate e delle uscite.");
            }
            used.add(mapping.incomeColumn());
            used.add(mapping.expenseColumn());
        }
        used.add(mapping.descriptionColumn());
        used.add(mapping.counterpartyColumn());
        used.add(mapping.currencyColumn());
        for (Integer column : used) {
            if (column != null && column >= columns) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Il file ha " + columns + " colonne: la colonna " + (column + 1) + " non esiste.");
            }
        }
    }

    /**
     * L'impronta di una riga. Se il file dà un identificativo dell'operazione (il {@code transaction_id} di Trade
     * Republic, unico per ogni operazione) si usa quello: in un conto collegato {@code trcsv:<id>}, la stessa impronta
     * che dà "Completa i movimenti" (CompletionService.fileKeys), così caricare il file da una pagina o dall'altra
     * non fa doppioni; in un conto manuale {@code csv:tr:<id>}. Senza identificativo, {@link #fileKey} o
     * {@link #dedupKey}.
     */
    static String keyFor(ParsedRow row, String fileId, boolean linked, Map<String, Integer> occurrences) {
        if (fileId != null) {
            return (linked ? BankTransaction.FILE_KEY_PREFIX : MANUAL_TRADE_REPUBLIC_PREFIX) + fileId;
        }
        return linked ? fileKey(row, occurrences) : dedupKey(row, occurrences);
    }

    /** Il prefisso dell'impronta di una riga di Trade Republic importata in un conto manuale. */
    static final String MANUAL_TRADE_REPUBLIC_PREFIX = "csv:tr:";

    /**
     * L'impronta di una riga, per non importarla due volte. È un hash SHA-256 di data, importo, valuta, testi
     * e "occorrenza": se nello stesso file ci sono due righe identiche (due caffè uguali nello stesso
     * giorno), la seconda ha occorrenza 2, così restano due movimenti distinti. Reimportando lo stesso file,
     * o un file che si sovrappone, le impronte coincidono e le righe vengono saltate.
     *
     * <p>La seconda colonna della causale ({@code detail}) non entra: così le righe importate prima che si leggesse
     * hanno la stessa impronta, e reimportando il file si completano invece di raddoppiare.
     */
    static String dedupKey(ParsedRow row, Map<String, Integer> occurrences) {
        String base = row.date() + "|" + row.amount().toPlainString() + "|" + row.currency() + "|"
                + nullToEmpty(row.description()) + "|" + nullToEmpty(row.counterparty());
        int occurrence = occurrences.merge(base, 1, Integer::sum);
        return "csv:" + sha256(base + "|" + occurrence);
    }

    /**
     * L'impronta di una riga aggiunta a un conto collegato: come {@link #dedupKey}, ma con il prefisso dei movimenti
     * presi da un file ({@code trcsv:}, BankTransaction.FILE_KEY_PREFIX). La sincronizzazione riconosce quel prefisso:
     * se la banca un giorno mandasse lo stesso movimento, prende quello del file invece di aggiungerne un altro.
     */
    static String fileKey(ParsedRow row, Map<String, Integer> occurrences) {
        return BankTransaction.FILE_KEY_PREFIX + dedupKey(row, occurrences).substring("csv:".length());
    }

    /** La riga originale come JSON, {"Data": "03/10/2026", "Importo": "-12,99", ...}: come raw_json per Enable Banking. */
    private String rawJson(List<String> headers, List<String> row) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < row.size(); i++) {
            String header = i < headers.size() && !headers.get(i).isBlank() ? headers.get(i).trim() : "colonna " + (i + 1);
            values.putIfAbsent(header, row.get(i));
        }
        return jsonMapper.writeValueAsString(values);
    }

    private static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 c'è in ogni Java: non succede
        }
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
