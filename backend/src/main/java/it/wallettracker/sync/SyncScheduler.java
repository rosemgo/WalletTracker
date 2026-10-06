package it.wallettracker.sync;

import it.wallettracker.sync.SyncService.Round;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Il "timer" della sincronizzazione automatica.
 *
 * <ul>
 *   <li>{@code @EnableScheduling} accende in Spring il meccanismo dei metodi programmati
 *       ({@code @Scheduled}): Spring crea un thread che li esegue al momento giusto;</li>
 *   <li>{@code @ConditionalOnBooleanProperty}: questa classe esiste solo se
 *       {@code wallettracker.sync.enabled} è true. Con il profilo "poc" è false: il programma della
 *       Fase 0 aggiorna i conti da solo e poi deve terminare, non restare acceso.</li>
 * </ul>
 */
@Component
@EnableScheduling
@ConditionalOnBooleanProperty("wallettracker.sync.enabled")
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final SyncService syncService;

    public SyncScheduler(SyncService syncService) {
        this.syncService = syncService;
    }

    /**
     * Ogni {@code check-every} (es. 30 minuti) controlla se ci sono conti da aggiornare.
     *
     * <p>Perché controllare spesso, se ogni conto si aggiorna ogni 6 ore? Perché il computer può essere
     * spento, in sospensione o riavviato: controllando ogni 30 minuti, appena l'applicazione torna attiva
     * i conti "scaduti" vengono aggiornati. Il controllo in sé non costa nulla: legge solo il database.
     *
     * <p>{@code fixedDelay}: il giro successivo parte 30 minuti dopo la <b>fine</b> del precedente,
     * quindi due giri non si sovrappongono mai. Se il metodo lancia un'eccezione, Spring la scrive nel
     * log e al giro successivo riprova.
     */
    @Scheduled(initialDelayString = "${wallettracker.sync.initial-delay}",
            fixedDelayString = "${wallettracker.sync.check-every}")
    public void syncDueAccounts() {
        Round round = syncService.syncAllInBackground();
        if (round.updated() + round.failed() > 0) {
            log.info("Sincronizzazione: {} conti aggiornati, {} con errori, {} non ancora da aggiornare",
                    round.updated(), round.failed(), round.notDue());
        }
    }
}
