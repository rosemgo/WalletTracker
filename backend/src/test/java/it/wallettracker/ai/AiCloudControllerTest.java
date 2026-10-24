package it.wallettracker.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.Optional;

import it.wallettracker.IntegrationTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * L'IA nel cloud (passo 7c) dal punto di vista delle API: la chiave (cifrata, mai restituita), il consenso, i
 * controlli prima di usare un servizio, la prova della connessione. Il modello è finto ({@code @MockitoBean}):
 * le chiamate vere a Gemini le prova CloudChatTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class AiCloudControllerTest {

    static final String KEY = "Abc123secretKeyXYZ9";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    OllamaApi ollamaApi;

    @MockitoBean
    CategoryModel categoryModel;

    @AfterEach
    void backToDefaults() {
        jdbc.update("""
                UPDATE ai_settings SET provider = 'NONE', gemini_api_key = NULL, gemini_consent_at = NULL,
                    gemini_model = 'gemini-flash-latest'""");
    }

    @Test
    void theKeyIsSavedEncryptedAndNeverSentBack() {
        assertThat(saveKey("GEMINI", KEY)).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.gemini.keySet").isEqualTo(true);
            json.assertThat().extractingPath("$.gemini.keyHint").isEqualTo("…XYZ9");
        });

        // Nel database c'è solo la versione cifrata.
        String stored = jdbc.queryForObject("SELECT gemini_api_key FROM ai_settings", String.class);
        assertThat(stored).startsWith("v1:").doesNotContain(KEY);
        // E nella risposta di GET /api/ai la chiave non c'è, da nessuna parte.
        assertThat(mvc.get().uri("/api/ai")).hasStatusOk().bodyText().doesNotContain(KEY).doesNotContain(stored);
    }

    @Test
    void aCloudServiceNeedsTheKeyAndTheConsent() {
        // Senza chiave: no.
        assertThat(choose("GEMINI", false)).hasStatus(409).bodyJson().extractingPath("$.detail").asString()
                .contains("Inserisci prima la chiave di Google Gemini");
        saveKey("GEMINI", KEY);
        // Con la chiave ma senza consenso: no.
        assertThat(choose("GEMINI", false)).hasStatus(409).bodyJson().extractingPath("$.detail").asString()
                .contains("serve il tuo consenso");
        // Con il consenso: sì, e il consenso resta salvato.
        assertThat(choose("GEMINI", true)).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.provider").isEqualTo("GEMINI");
            json.assertThat().extractingPath("$.gemini.consent").isEqualTo(true);
        });
        choose("NONE", false);
        assertThat(choose("GEMINI", false)).hasStatusOk(); // già dato: non serve ridarlo
    }

    @Test
    void removingTheKeyOfTheChosenServiceTurnsTheAiOff() {
        saveKey("GEMINI", KEY);
        choose("GEMINI", true);

        assertThat(mvc.delete().uri("/api/ai/keys/GEMINI")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.provider").isEqualTo("NONE");
            json.assertThat().extractingPath("$.gemini.keySet").isEqualTo(false);
        });
    }

    @Test
    void theModelsCanBeChanged() {
        assertThat(mvc.put().uri("/api/ai").contentType(MediaType.APPLICATION_JSON).content("""
                {"provider": "NONE", "ollamaModel": "gemma3:4b",
                 "geminiModel": "gemini-flash-lite-latest"}""").exchange())
                .hasStatusOk().bodyJson().satisfies(json -> {
                    json.assertThat().extractingPath("$.gemini.model").isEqualTo("gemini-flash-lite-latest");
                });
    }

    @Test
    void wrongRequestsAre400() {
        assertThat(saveKey("GEMINI", "short")).hasStatus(400);                 // troppo corta
        assertThat(saveKey("GEMINI", "with spaces and; stuff")).hasStatus(400); // caratteri non ammessi
        assertThat(saveKey("OLLAMA", KEY)).hasStatus(400);                       // Ollama non ha una chiave
        assertThat(saveKey("MISTRAL", KEY)).hasStatus(400);                      // non c'è più (V23)
        assertThat(saveKey("BOH", KEY)).hasStatus(400);
        assertThat(jdbc.queryForObject("SELECT gemini_api_key FROM ai_settings", String.class)).isNull();
    }

    @Test
    void theConnectionTestAsksOneQuestion() {
        saveKey("GEMINI", KEY);
        choose("GEMINI", true);
        when(categoryModel.suggest(any(CategoryQuestion.class), eq(new AiTarget(AiProvider.GEMINI, "gemini-flash-latest"))))
                .thenAnswer(call -> {
                    CategoryQuestion question = call.getArgument(0);
                    return Optional.of(question.categories().stream()
                            .filter(o -> o.name().equals("Spesa alimentare")).findFirst().orElseThrow().id());
                });

        assertThat(mvc.post().uri("/api/ai/connection-test/GEMINI")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.ok").isEqualTo(true);
            json.assertThat().extractingPath("$.answer").isEqualTo("Spesa alimentare");
        });
    }

    @Test
    void aFailedConnectionTestSaysWhy() {
        saveKey("GEMINI", KEY);
        choose("GEMINI", true);
        when(categoryModel.suggest(any(), any())).thenThrow(new NonTransientAiException("401 Unauthorized"));

        assertThat(mvc.post().uri("/api/ai/connection-test/GEMINI")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.ok").isEqualTo(false);
            json.assertThat().extractingPath("$.error").asString().startsWith("La chiave di Google Gemini non è valida");
        });
    }

    @Test
    void withoutConsentNothingIsSentToTheCloud() {
        saveKey("GEMINI", KEY); // la chiave sì, il consenso no

        assertThat(mvc.post().uri("/api/ai/connection-test/GEMINI")).hasStatus(409);
        assertThat(mvc.post().uri("/api/ai/evaluation").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\": \"GEMINI\", \"model\": \"gemini-flash-latest\", \"samples\": 5}"))
                .hasStatus(409);
        org.mockito.Mockito.verifyNoInteractions(categoryModel);
    }

    private MvcTestResult saveKey(String provider, String key) {
        return mvc.put().uri("/api/ai/keys/" + provider).contentType(MediaType.APPLICATION_JSON)
                .content("{\"apiKey\": \"" + key + "\"}").exchange();
    }

    private MvcTestResult choose(String provider, boolean consent) {
        return mvc.put().uri("/api/ai").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\": \"" + provider + "\", \"ollamaModel\": \"gemma3:4b\", \"consent\": " + consent
                        + "}").exchange();
    }

}
