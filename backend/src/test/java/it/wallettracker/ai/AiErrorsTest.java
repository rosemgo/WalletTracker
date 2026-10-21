package it.wallettracker.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;

import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.web.client.ResourceAccessException;

/** Dagli errori delle librerie ai messaggi per l'utente: funzioni pure. */
class AiErrorsTest {

    @Test
    void theHttpCodeSaysWhatHappened() {
        assertThat(AiErrors.describe(new NonTransientAiException("HTTP 401 - {\"message\":\"Unauthorized\"}"),
                AiProvider.MISTRAL)).startsWith("La chiave di Mistral AI non è valida");
        assertThat(AiErrors.describe(new RuntimeException("404 Not Found. models/gemini-9 is not found"),
                AiProvider.GEMINI)).startsWith("Il modello non esiste per Google Gemini");
        assertThat(AiErrors.describe(new RuntimeException("429 Too Many Requests"), AiProvider.GEMINI))
                .contains("troppe richieste");
    }

    @Test
    void googleSaysWrongKeyWithA400() {
        // Il messaggio vero di Google, visto provando con una chiave inventata.
        String google = "400 . API key not valid. Please pass a valid API key.\nDetails: {\"@type\":"
                + "\"type.googleapis.com/google.rpc.ErrorInfo\",\"reason\":\"API_KEY_INVALID\"}";
        assertThat(AiErrors.describe(new RuntimeException(google), AiProvider.GEMINI))
                .startsWith("La chiave di Google Gemini non è valida");
    }

    @Test
    void theCodeIsFoundAlsoInTheCause() {
        RuntimeException wrapped = new RuntimeException("Errore", new IllegalStateException("status 403 Forbidden"));
        assertThat(AiErrors.describe(wrapped, AiProvider.GEMINI)).startsWith("La chiave di Google Gemini non è valida");
    }

    @Test
    void numbersThatAreNotHttpCodesAreIgnored() {
        // 4290 non è 429, e un id che contiene 401 nemmeno.
        assertThat(AiErrors.describe(new RuntimeException("request 14012 failed after 4290 ms"), AiProvider.MISTRAL))
                .startsWith("Mistral AI non ha risposto: request 14012");
    }

    @Test
    void noNetworkIsSaidClearly() {
        assertThat(AiErrors.describe(new ResourceAccessException("I/O error", new ConnectException("refused")),
                AiProvider.MISTRAL)).isEqualTo("Non riesco a raggiungere Mistral AI: controlla la connessione a internet.");
        assertThat(AiErrors.describe(new ResourceAccessException("I/O error"), AiProvider.OLLAMA))
                .endsWith("(o se Ollama è avviato).");
    }
}
