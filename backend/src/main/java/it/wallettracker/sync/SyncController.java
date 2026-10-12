package it.wallettracker.sync;

import it.wallettracker.sync.SyncService.Round;
import it.wallettracker.web.PresentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Il pulsante "Aggiorna ora" della dashboard. */
@RestController
public class SyncController {

    private final SyncService syncService;
    private final PresentUser presentUser;

    public SyncController(SyncService syncService, PresentUser presentUser) {
        this.syncService = syncService;
        this.presentUser = presentUser;
    }

    /**
     * {@code POST /api/sync}: aggiorna subito tutti i conti, con l'utente presente.
     * È un POST e non un GET perché fa un'azione e cambia i dati (un GET deve solo leggere).
     */
    @PostMapping("/api/sync")
    public Round syncNow(HttpServletRequest request) {
        return syncService.syncAllNow(presentUser.headersFor(request));
    }
}
