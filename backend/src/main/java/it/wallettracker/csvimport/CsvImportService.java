package it.wallettracker.csvimport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
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
 * L'importazione dei movimenti da file CSV, in due tempi:
 * <ol>
 *   <li>{@link #preview}: legge il file, propone come interpretare le colonne e mostra il risultato su
 *       alcune righe. Non salva niente;</li>
 *   <li>{@link #importFile}: con la mappatura confermata dall'utente, salva i movimenti nel conto manuale
 *       scelto e li classifica, come quelli arrivati da Enable Banking.</li>
 * </ol>
 * Il file non viene conservato sul server: il browser lo rimanda nella seconda richiesta.
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

    /** Il file letto: intestazione, righe, separatore. */
    private record CsvFile(char delimiter, List<String> headers, List<List<String>> rows) {
    }

    /** Il risultato della lettura di tutte le righe con una mappatura. */
    private record Reading(List<ParsedRow> parsed, List<List<String>> rawRows, int errorCount, List<String> errors) {
    }

    /** L'anteprima. Senza mappatura ne propone una (ColumnGuesser). */
    public Preview preview(byte[] content, ImportMapping mapping, String currency) {
        CsvFile file = read(content, mapping);
        List<List<String>> samples = file.rows().subList(0, Math.min(file.rows().size(), PREVIEW_ROWS));
        ImportMapping used = mapping != null ? mapping
                : ColumnGuesser.guess(String.valueOf(file.delimiter()), file.headers(), samples);
        // Una mappatura incompleta (es. la proposta non ha trovato l'importo) non è un errore dell'anteprima:
        // mostriamo il file e diciamo che cosa manca, così l'utente lo sceglie.
        Reading reading;
        try {
            reading = readRows(file, used, currency);
        } catch (ResponseStatusException e) {
            return new Preview(file.headers(), samples, file.rows().size(), used, List.of(), file.rows().size(),
                    List.of(e.getReason()));
        }
        List<PreviewRow> parsedRows = reading.parsed().stream()
                .limit(PREVIEW_ROWS)
                .map(r -> new PreviewRow(r.date(), r.amount(), r.currency(), r.description(), r.counterparty()))
                .toList();
        return new Preview(file.headers(), samples, file.rows().size(), used, parsedRows, reading.errorCount(),
                reading.errors());
    }

    /**
     * Importa il file nel conto manuale indicato. Tutto o niente: se anche una sola riga non si legge,
     * l'importazione viene rifiutata con l'elenco degli errori.
     */
    @Transactional
    public ImportResult importFile(byte[] content, Long accountId, ImportMapping mapping) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Conto", accountId));
        if (!account.isManual()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Puoi importare un file solo in un conto manuale: i conti collegati si aggiornano da soli.");
        }
        CsvFile file = read(content, mapping);
        Reading reading = readRows(file, mapping, account.getCurrency());
        if (reading.errorCount() > 0) {
            throw new ImportRejectedException(reading.errorCount() + " righe non si possono leggere: correggi la "
                    + "mappatura o il file. Non è stato importato niente.", reading.errors());
        }

        // Le impronte dei movimenti già presenti nel conto, in una sola query (non una per riga).
        Set<String> existing = transactionRepository.findDedupKeysByAccount(account);
        Map<String, Integer> occurrences = new HashMap<>();
        List<BankTransaction> toSave = new ArrayList<>();
        int duplicates = 0;
        for (int i = 0; i < reading.parsed().size(); i++) {
            ParsedRow row = reading.parsed().get(i);
            String key = dedupKey(row, occurrences);
            if (existing.contains(key)) {
                duplicates++;
                continue;
            }
            toSave.add(new BankTransaction(account, key, TransactionStatus.BOOKED, row.date(), null, row.amount(),
                    row.currency(), row.counterparty(), row.description(), rawJson(file.headers(), reading.rawRows().get(i))));
        }
        transactionRepository.saveAll(toSave);
        transactionRepository.flush();
        classificationService.classifyAll();
        return new ImportResult(toSave.size(), duplicates);
    }

    /**
     * Da byte a righe e colonne, con i controlli sul formato. Il separatore è quello della mappatura, se c'è
     * (l'utente può averlo corretto), altrimenti quello riconosciuto dal file.
     */
    private static CsvFile read(byte[] content, ImportMapping mapping) {
        String text = TextDecoder.decode(content);
        char delimiter = mapping != null ? mapping.delimiterChar() : CsvReader.detectDelimiter(text);
        List<List<String>> all;
        try {
            all = CsvReader.read(text, delimiter);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (all.size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Il file deve avere una riga di intestazione e almeno un movimento.");
        }
        if (all.size() - 1 > MAX_ROWS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Il file ha troppe righe: al massimo " + MAX_ROWS + ".");
        }
        return new CsvFile(delimiter, all.getFirst(), all.subList(1, all.size()));
    }

    /** Legge tutte le righe; gli errori riportano il numero di riga del file (l'intestazione è la riga 1). */
    private static Reading readRows(CsvFile file, ImportMapping mapping, String currency) {
        checkColumns(mapping, file.headers().size());
        List<ParsedRow> parsed = new ArrayList<>();
        List<List<String>> rawRows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int errorCount = 0;
        for (int i = 0; i < file.rows().size(); i++) {
            try {
                parsed.add(RowReader.read(file.rows().get(i), mapping, currency));
                rawRows.add(file.rows().get(i));
            } catch (RowException e) {
                errorCount++;
                if (errors.size() < MAX_ERRORS) {
                    errors.add("Riga " + (i + 2) + ": " + e.getMessage() + ".");
                }
            }
        }
        return new Reading(parsed, rawRows, errorCount, errors);
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
     * L'impronta di una riga, per non importarla due volte. È un hash SHA-256 di data, importo, valuta, testi
     * e "occorrenza": se nello stesso file ci sono due righe identiche (due caffè uguali nello stesso
     * giorno), la seconda ha occorrenza 2, così restano due movimenti distinti. Reimportando lo stesso file,
     * o un file che si sovrappone, le impronte coincidono e le righe vengono saltate.
     */
    static String dedupKey(ParsedRow row, Map<String, Integer> occurrences) {
        String base = row.date() + "|" + row.amount().toPlainString() + "|" + row.currency() + "|"
                + nullToEmpty(row.description()) + "|" + nullToEmpty(row.counterparty());
        int occurrence = occurrences.merge(base, 1, Integer::sum);
        return "csv:" + sha256(base + "|" + occurrence);
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
