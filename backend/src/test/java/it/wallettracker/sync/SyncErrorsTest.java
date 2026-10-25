package it.wallettracker.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import it.wallettracker.bank.enablebanking.EnableBankingNotConfiguredException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/** Gli errori dell'aggiornamento, in frasi per chi usa l'app: mai il nome di un'eccezione o un codice HTTP grezzo. */
class SyncErrorsTest {

    @Test
    void theErrorsOfTheBankBecomeSentences() {
        assertThat(SyncErrors.describe(error(HttpStatus.TOO_MANY_REQUESTS))).contains("troppe richieste");
        assertThat(SyncErrors.describe(error(HttpStatus.UNAUTHORIZED))).contains("Ricollega la banca");
        assertThat(SyncErrors.describe(error(HttpStatus.FORBIDDEN))).contains("consenso");
        assertThat(SyncErrors.describe(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", null,
                "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))).contains("non rispondono");
        assertThat(SyncErrors.describe(error(HttpStatus.UNPROCESSABLE_ENTITY))).contains("codice 422");
        assertThat(SyncErrors.describe(new ResourceAccessException("Connection refused")))
                .contains("connessione a Internet");
    }

    @Test
    void ourOwnMessagesStayAndTheUnexpectedOnesPointToTheLog() {
        String notConfigured = SyncErrors.describe(new EnableBankingNotConfiguredException());
        assertThat(notConfigured).contains("Impostazioni").doesNotContain("Exception").doesNotContain("409");
        assertThat(SyncErrors.describe(new IllegalStateException("boom")))
                .isEqualTo("Errore imprevisto durante l'aggiornamento: i dettagli sono nel log del backend.");
    }

    private static HttpClientErrorException error(HttpStatus status) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), null,
                "{\"detail\": \"x\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
