package it.wallettracker.backup;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * I backup visti dalla dashboard (Fase 4, passo 5: docs/48, parte Q).
 *
 * <p>I backup li fa <b>solo</b> il container "backup" (docker/backup). Il backend non comanda Docker (dargli il
 * "socket" di Docker vorrebbe dire dargli il controllo del server) e la cartella dei backup la vede in sola lettura.
 * Si parlano con due file, in una cartella che vedono tutti e due ({@code .data/backup-stato}):
 * <ul>
 *   <li>{@code stato.txt}, scritto dal container: backup automatico acceso o spento, un backup in corso, l'ultimo
 *       errore, e {@code aggiornato}, rinnovato almeno ogni minuto (se è vecchio, il container è spento);</li>
 *   <li>{@code richiesta}, scritto da qui ("fai un backup adesso"): il container lo vede entro 5 secondi.</li>
 * </ul>
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    /**
     * Il nome di un backup, esattamente come lo scrive il container. Serve due volte: per elencare solo i backup (e
     * non i file provvisori o altro) e per lo scaricamento, dove un nome che non torna non apre niente (niente
     * "../" verso altre cartelle).
     */
    static final Pattern NAME = Pattern.compile("wallettracker-(manuale|automatico|prima-del-ripristino)-\\d{8}-\\d{6}\\.backup");

    /** Il container rinnova lo stato almeno ogni minuto: tre minuti senza vuol dire che è spento. */
    static final Duration ALIVE = Duration.ofMinutes(3);
    /** Oltre due giorni senza un backup riuscito, la dashboard avvisa. */
    static final Duration TOO_OLD = Duration.ofHours(48);
    /** Quanti backup elencare al massimo (i più nuovi). */
    static final int LISTED = 30;

    static final String STATE_FILE = "stato.txt";
    static final String REQUEST_FILE = "richiesta";

    private final Path directory;
    private final Path stateDirectory;
    private final Clock clock;

    public BackupService(BackupProperties properties, Clock clock) {
        this.directory = Path.of(properties.directory());
        this.stateDirectory = Path.of(properties.stateDirectory());
        this.clock = clock;
    }

    /**
     * Un file di backup.
     *
     * @param type      manuale, automatico o prima-del-ripristino
     * @param createdAt quando è stato scritto (la data del file)
     */
    public record BackupFile(String name, String type, Instant createdAt, long sizeBytes) {
    }

    /**
     * Lo stato dei backup, per la pagina Impostazioni e per l'avviso in cima all'app.
     *
     * @param available        il container "backup" c'è (l'app è installata con Docker): senza, il resto non conta
     * @param serviceRunning   il container ha rinnovato lo stato da poco
     * @param automaticEnabled la password dei backup c'è: il backup di ogni notte è acceso
     * @param inProgress       un backup chiesto o in corso
     * @param lastSuccessAt    il backup più nuovo (null se non ce n'è nessuno)
     * @param attention        c'è qualcosa da sistemare: la dashboard mostra un avviso
     * @param canDownload      la richiesta arriva dal server: i backup si possono scaricare
     */
    public record Status(boolean available, boolean serviceRunning, boolean automaticEnabled, String backupTime,
            int keep, boolean inProgress, Instant lastErrorAt, String lastError, Instant lastSuccessAt,
            boolean attention, List<BackupFile> backups, boolean canDownload) {

        Status withCanDownload(boolean value) {
            return new Status(available, serviceRunning, automaticEnabled, backupTime, keep, inProgress, lastErrorAt,
                    lastError, lastSuccessAt, attention, backups, value);
        }
    }

    public Status status() {
        if (!Files.isDirectory(stateDirectory)) {
            return new Status(false, false, false, null, 0, false, null, null, null, false, List.of(), false);
        }
        Instant now = clock.instant();
        Map<String, String> state = readState();
        Instant updated = epoch(state.get("aggiornato"));
        boolean running = updated != null && Duration.between(updated, now).compareTo(ALIVE) < 0;
        boolean automatic = "acceso".equals(state.get("automatico"));
        boolean inProgress = Files.exists(stateDirectory.resolve(REQUEST_FILE))
                || (running && !state.getOrDefault("in-corso", "").isEmpty());
        Instant errorAt = epoch(state.get("errore-quando"));
        String error = blankToNull(state.get("errore"));

        List<BackupFile> backups = list();
        Instant lastSuccess = backups.isEmpty() ? null : backups.getFirst().createdAt();
        boolean errorAfterSuccess = errorAt != null && (lastSuccess == null || errorAt.isAfter(lastSuccess));
        boolean attention = !running || !automatic || lastSuccess == null
                || Duration.between(lastSuccess, now).compareTo(TOO_OLD) > 0 || errorAfterSuccess;
        int keep = parseInt(state.get("tieni"));
        return new Status(true, running, automatic, blankToNull(state.get("ora")), keep, inProgress,
                errorAfterSuccess ? errorAt : null, errorAfterSuccess ? error : null, lastSuccess, attention, backups,
                false);
    }

    /**
     * "Fai un backup adesso": scrive la richiesta per il container. Rifiutata se il container è spento, se manca la
     * password (il backup non potrebbe essere cifrato) o se un backup è già chiesto o in corso.
     */
    public void requestBackup() {
        Status status = status();
        if (!status.available() || !status.serviceRunning()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Il servizio dei backup non risponde: dal server, controlla con  docker compose ps");
        }
        if (!status.automaticEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Manca la password dei backup: dal server, lancia lo script di installazione con -Backup (docs/48).");
        }
        if (status.inProgress()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Un backup è già in corso: aspetta che finisca.");
        }
        try {
            // createFile fallisce se il file c'è già: due richieste nello stesso istante ne scrivono una sola.
            Files.createFile(stateDirectory.resolve(REQUEST_FILE));
            log.info("Backup chiesto dalla dashboard.");
        } catch (FileAlreadyExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Un backup è già in corso: aspetta che finisca.");
        } catch (IOException e) {
            log.warn("Non riesco a scrivere la richiesta di backup in {}", stateDirectory, e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Non riesco a chiedere il backup.");
        }
    }

    /**
     * Il file di un backup, da scaricare. Solo un nome di backup valido, e solo dentro la cartella dei backup: con
     * qualunque altro nome (per esempio "../.env") la risposta è 404, come per un backup che non c'è.
     */
    public Path file(String name) {
        Path file = directory.resolve(name).normalize();
        if (!NAME.matcher(name).matches() || !directory.normalize().equals(file.getParent())
                || !Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Backup non trovato.");
        }
        return file;
    }

    /** I backup della cartella, dal più nuovo, al massimo {@value #LISTED}. */
    List<BackupFile> list() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(this::backupFile)
                    .filter(file -> file != null)
                    .sorted(Comparator.comparing(BackupFile::createdAt).reversed())
                    .limit(LISTED)
                    .toList();
        } catch (IOException e) {
            log.warn("Non riesco a leggere la cartella dei backup {}", directory, e);
            return List.of();
        }
    }

    private BackupFile backupFile(Path path) {
        String name = path.getFileName().toString();
        Matcher matcher = NAME.matcher(name);
        if (!matcher.matches() || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return new BackupFile(name, matcher.group(1), Files.getLastModifiedTime(path).toInstant(),
                    Files.size(path));
        } catch (IOException e) {
            return null;
        }
    }

    /** Le righe "nome=valore" di stato.txt; vuoto se il file non c'è (il container non è mai partito). */
    private Map<String, String> readState() {
        Map<String, String> state = new HashMap<>();
        Path file = stateDirectory.resolve(STATE_FILE);
        if (!Files.isRegularFile(file)) {
            return state;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                int equals = line.indexOf('=');
                if (equals > 0) {
                    state.put(line.substring(0, equals), line.substring(equals + 1).trim());
                }
            }
        } catch (IOException e) {
            log.warn("Non riesco a leggere lo stato dei backup {}", file, e);
        }
        return state;
    }

    /** Secondi dal 1970 (date +%s), come li scrive il container; null se manca o non è un numero. */
    private static Instant epoch(String value) {
        try {
            return value == null || value.isBlank() ? null : Instant.ofEpochSecond(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
