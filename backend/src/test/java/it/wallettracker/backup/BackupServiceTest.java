package it.wallettracker.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

/** Le regole dei backup nella dashboard (docs/48, parte Q): cartelle temporanee e un orologio fermo, niente Spring. */
class BackupServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");

    @TempDir
    Path root;

    private Path backups;
    private Path state;
    private BackupService service;

    @BeforeEach
    void setUp() throws IOException {
        backups = Files.createDirectory(root.resolve("backup"));
        state = root.resolve("backup-stato");
        service = new BackupService(new BackupProperties(backups.toString(), state.toString()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Lo stato come lo scrive il container: aggiornato "secondsAgo" secondi fa, automatico acceso o spento. */
    private void writeState(long secondsAgo, boolean automatic, String extra) throws IOException {
        Files.createDirectories(state);
        Files.writeString(state.resolve("stato.txt"), "aggiornato=" + NOW.minusSeconds(secondsAgo).getEpochSecond()
                + "\nautomatico=" + (automatic ? "acceso" : "spento") + "\nora=03:15\ntieni=14\n" + extra);
    }

    private Path backup(String name, Duration age) throws IOException {
        Path file = Files.writeString(backups.resolve(name), "Salted__finto");
        Files.setLastModifiedTime(file, FileTime.from(NOW.minus(age)));
        return file;
    }

    @Test
    void withoutTheBackupContainerTheBackupsAreNotAvailable() {
        BackupService.Status status = service.status();

        assertThat(status.available()).isFalse();
        assertThat(status.attention()).isFalse(); // in sviluppo, senza Docker, niente avviso
    }

    @Test
    void aRecentBackupWithTheServiceRunningNeedsNoAttention() throws IOException {
        writeState(30, true, "in-corso=\nerrore-quando=\nerrore=\n");
        backup("wallettracker-automatico-20261031-031500.backup", Duration.ofHours(7));
        backup("wallettracker-manuale-20261030-101500.backup", Duration.ofDays(1));
        // Non sono backup: un file provvisorio, un nome sbagliato, un altro file.
        Files.writeString(backups.resolve(".wallettracker-manuale-20261031-095900.backup.in-corso"), "x");
        Files.writeString(backups.resolve("wallettracker-manuale-1.backup"), "x");
        Files.writeString(backups.resolve("appunti.txt"), "x");

        BackupService.Status status = service.status();

        assertThat(status.available()).isTrue();
        assertThat(status.serviceRunning()).isTrue();
        assertThat(status.automaticEnabled()).isTrue();
        assertThat(status.backupTime()).isEqualTo("03:15");
        assertThat(status.keep()).isEqualTo(14);
        assertThat(status.backups()).extracting(BackupService.BackupFile::name)
                .containsExactly("wallettracker-automatico-20261031-031500.backup",
                        "wallettracker-manuale-20261030-101500.backup");
        assertThat(status.backups().getFirst().type()).isEqualTo("automatico");
        assertThat(status.lastSuccessAt()).isEqualTo(NOW.minus(Duration.ofHours(7)));
        assertThat(status.attention()).isFalse();
    }

    @Test
    void attentionWhenTheServiceIsOffTheAutomaticBackupIsOffTheLastBackupIsOldOrTheLastOneFailed() throws IOException {
        backup("wallettracker-automatico-20261031-031500.backup", Duration.ofHours(7));

        writeState(240, true, ""); // 4 minuti senza aggiornare: il container è spento
        assertThat(service.status().serviceRunning()).isFalse();
        assertThat(service.status().attention()).isTrue();

        writeState(30, false, ""); // nessuna password
        assertThat(service.status().attention()).isTrue();

        writeState(30, true, "errore-quando=" + NOW.minusSeconds(60).getEpochSecond()
                + "\nerrore=Backup non riuscito: dal server, guarda i log\n");
        BackupService.Status failed = service.status();
        assertThat(failed.attention()).isTrue();
        assertThat(failed.lastError()).startsWith("Backup non riuscito");

        // Un errore più vecchio dell'ultimo backup riuscito non conta più.
        writeState(30, true, "errore-quando=" + NOW.minus(Duration.ofDays(1)).getEpochSecond() + "\nerrore=vecchio\n");
        assertThat(service.status().attention()).isFalse();
        assertThat(service.status().lastError()).isNull();

        Files.setLastModifiedTime(backups.resolve("wallettracker-automatico-20261031-031500.backup"),
                FileTime.from(NOW.minus(Duration.ofDays(3))));
        assertThat(service.status().attention()).isTrue(); // più di due giorni fa
    }

    @Test
    void aBackupIsRequestedOnlyOnceAndOnlyIfTheServiceCanDoIt() throws IOException {
        writeState(240, true, "");
        assertThatThrownBy(service::requestBackup).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("non risponde");

        writeState(30, false, "");
        assertThatThrownBy(service::requestBackup).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Manca la password");

        writeState(30, true, "in-corso=\n");
        service.requestBackup();
        assertThat(state.resolve("richiesta")).exists();
        assertThat(service.status().inProgress()).isTrue();
        assertThatThrownBy(service::requestBackup).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("già in corso");
    }

    @Test
    void onlyARealBackupInsideTheFolderCanBeDownloaded() throws IOException {
        Path good = backup("wallettracker-manuale-20261031-101500.backup", Duration.ZERO);
        Files.writeString(root.resolve("segreto.txt"), "non uscire di qui");

        assertThat(service.file("wallettracker-manuale-20261031-101500.backup")).isEqualTo(good.normalize());
        for (String name : new String[] {"../segreto.txt", "segreto.txt", "wallettracker-manuale-20261031-999999.backup",
                "../backup/wallettracker-manuale-20261031-101500.backup", ".wallettracker-manuale-20261031-101500.backup"}) {
            assertThatThrownBy(() -> service.file(name)).as(name).isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("non trovato");
        }
    }
}
