package it.wallettracker.backup;

import java.nio.file.Path;

import it.wallettracker.auth.RequestOrigin;
import it.wallettracker.backup.BackupService.Status;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * I backup nella dashboard (Impostazioni → Backup, docs/48 parte Q):
 * <ul>
 *   <li>{@code GET /api/backups}: lo stato e l'elenco;</li>
 *   <li>{@code POST /api/backups}: "fai un backup adesso" (lo fa il container "backup", entro qualche secondo);</li>
 *   <li>{@code GET /api/backups/{nome}}: scarica un backup, <b>solo dal server</b> (la porta "del server", docs/46
 *       parte C). Il file è cifrato, ma resta tutti i tuoi dati: da un dispositivo remoto non si scarica.</li>
 * </ul>
 * Il ripristino e la password dei backup restano allo script di installazione (docs/48, parte Q1).
 */
@RestController
@RequestMapping("/api/backups")
public class BackupController {

    private final BackupService backups;

    public BackupController(BackupService backups) {
        this.backups = backups;
    }

    @GetMapping
    public Status status(HttpServletRequest request) {
        return backups.status().withCanDownload(RequestOrigin.isLocal(request));
    }

    @PostMapping
    public ResponseEntity<Status> request(HttpServletRequest request) {
        backups.requestBackup();
        // 202 Accepted: "ricevuto, lo faccio": il backup non è ancora finito quando la risposta parte.
        return ResponseEntity.accepted().body(status(request));
    }

    @GetMapping("/{name}")
    public ResponseEntity<Resource> download(@PathVariable String name, HttpServletRequest request) {
        if (!RequestOrigin.isLocal(request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "I backup si scaricano solo dal server, su http://localhost:8000.");
        }
        Path file = backups.file(name);
        return ResponseEntity.ok()
                // "attachment": il browser lo salva come file, con questo nome, invece di provare ad aprirlo.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.getFileName().toString()).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file));
    }
}
