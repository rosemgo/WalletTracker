package it.wallettracker.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.auth.RequestOrigin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.util.FileSystemUtils;

/**
 * Le API dei backup (docs/48, parte Q), con l'app vera. Le cartelle sono quelle temporanee di
 * IntegrationTestConfiguration: qui le creiamo, con lo stato che scriverebbe il container "backup".
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class BackupControllerTest {

    private static final String NAME = "wallettracker-manuale-20261031-101500.backup";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    BackupProperties properties;

    private Path backups;
    private Path state;

    @BeforeEach
    void theBackupContainerIsRunning() throws IOException {
        backups = Files.createDirectories(Path.of(properties.directory()));
        state = Files.createDirectories(Path.of(properties.stateDirectory()));
        Files.writeString(state.resolve("stato.txt"), "aggiornato=" + Instant.now().getEpochSecond()
                + "\nautomatico=acceso\nora=03:15\ntieni=14\nin-corso=\nerrore-quando=\nerrore=\n");
        Files.writeString(backups.resolve(NAME), "Salted__byte-cifrati-finti");
    }

    @AfterEach
    void cleanUp() throws IOException {
        FileSystemUtils.deleteRecursively(backups);
        FileSystemUtils.deleteRecursively(state);
    }

    @Test
    void theStatusListsTheBackupsAndSaysWhetherTheyCanBeDownloadedFromHere() {
        assertThat(mvc.get().uri("/api/backups").header(RequestOrigin.HEADER, "local"))
                .hasStatusOk().bodyJson().satisfies(json -> {
                    json.assertThat().extractingPath("$.available").isEqualTo(true);
                    json.assertThat().extractingPath("$.serviceRunning").isEqualTo(true);
                    json.assertThat().extractingPath("$.backups[0].name").isEqualTo(NAME);
                    json.assertThat().extractingPath("$.backups[0].type").isEqualTo("manuale");
                    json.assertThat().extractingPath("$.canDownload").isEqualTo(true);
                });
        // Da un dispositivo remoto (porta 8001, o senza l'header): si vede, ma non si scarica.
        assertThat(mvc.get().uri("/api/backups").header(RequestOrigin.HEADER, "remote"))
                .hasStatusOk().bodyJson().extractingPath("$.canDownload").isEqualTo(false);
    }

    @Test
    void aBackupIsDownloadedOnlyFromTheServer() {
        assertThat(mvc.get().uri("/api/backups/" + NAME).header(RequestOrigin.HEADER, "local"))
                .hasStatusOk()
                .hasHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + NAME + "\"")
                .hasBodyTextEqualTo("Salted__byte-cifrati-finti");

        assertThat(mvc.get().uri("/api/backups/" + NAME).header(RequestOrigin.HEADER, "remote"))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.detail").asString().contains("solo dal server");
        assertThat(mvc.get().uri("/api/backups/" + NAME)).hasStatus(HttpStatus.FORBIDDEN);
        // Un nome che non è un backup: 404, anche dal server (i nomi con "../" li prova BackupServiceTest).
        assertThat(mvc.get().uri("/api/backups/encryption.key").header(RequestOrigin.HEADER, "local"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void aBackupRequestedFromTheDashboardIsLeftForTheContainer() {
        assertThat(mvc.post().uri("/api/backups")).hasStatus(HttpStatus.ACCEPTED)
                .bodyJson().extractingPath("$.inProgress").isEqualTo(true);
        assertThat(state.resolve("richiesta")).exists();

        assertThat(mvc.post().uri("/api/backups")).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.detail").asString().contains("già in corso");
    }
}
