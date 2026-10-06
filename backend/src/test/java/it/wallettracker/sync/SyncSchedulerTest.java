package it.wallettracker.sync;

import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.sync.SyncService.Round;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Verifica che il timer funzioni: con la sincronizzazione accesa (nei test è spenta, vedi
 * src/test/resources/config/application.yml) e un controllo ogni secondo, Spring deve chiamare
 * {@link SyncService#syncAllInBackground()} più volte da solo, senza che nessuno lo inviti.
 */
@SpringBootTest(properties = {
        "wallettracker.sync.enabled=true",
        // un secondo di attesa iniziale: il tempo di dire al finto SyncService cosa restituire
        "wallettracker.sync.initial-delay=1s",
        "wallettracker.sync.check-every=1s"})
@Import(IntegrationTestConfiguration.class)
class SyncSchedulerTest {

    @MockitoBean
    SyncService syncService;

    @Test
    void theSchedulerChecksForAccountsToUpdateAtRegularIntervals() {
        when(syncService.syncAllInBackground()).thenReturn(new Round(0, 0, 0));

        // timeout(6000): aspetta fino a 6 secondi che la chiamata avvenga (almeno 2 volte).
        verify(syncService, timeout(6000).atLeast(2)).syncAllInBackground();
    }
}
