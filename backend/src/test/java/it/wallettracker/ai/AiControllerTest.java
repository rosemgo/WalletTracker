package it.wallettracker.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import it.wallettracker.IntegrationTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaApi.ListModelResponse;
import org.springframework.ai.ollama.api.OllamaApi.Model;
import org.springframework.ai.ollama.api.OllamaApi.ProgressResponse;
import org.springframework.ai.ollama.api.OllamaApi.PullModelRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;

/**
 * Test delle impostazioni dell'IA. Ollama è finto ({@code @MockitoBean}): decidiamo noi se "risponde" e che
 * cosa. Così i test (e la CI) non hanno bisogno di Ollama né di un modello scaricato.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class AiControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    AiSettingsRepository settingsRepository;

    @Autowired
    ModelDownload modelDownload;

    @MockitoBean
    OllamaApi ollamaApi;

    @AfterEach
    void backToDefaults() {
        AiSettings settings = settingsRepository.findById(AiSettings.ID).orElseThrow();
        settings.change(AiProvider.NONE, "qwen2.5:3b");
        settingsRepository.save(settings);
    }

    @Test
    void byDefaultTheAiIsOffAndAnOllamaThatDoesNotAnswerIsReported() {
        when(ollamaApi.listModels()).thenThrow(new ResourceAccessException("Connection refused"));

        assertThat(mvc.get().uri("/api/ai")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.provider").isEqualTo("NONE");
            json.assertThat().extractingPath("$.ollamaModel").isEqualTo("qwen2.5:3b");
            json.assertThat().extractingPath("$.ollamaReachable").isEqualTo(false);
            json.assertThat().extractingPath("$.modelInstalled").isEqualTo(false);
        });
    }

    @Test
    void theUserTurnsOllamaOnAndSeesWhetherTheModelIsDownloaded() {
        when(ollamaApi.listModels()).thenReturn(models("llama3.2:latest"));

        assertThat(put("{\"provider\": \"OLLAMA\", \"ollamaModel\": \"qwen2.5:3b\"}"))
                .hasStatusOk().bodyJson().satisfies(json -> {
                    json.assertThat().extractingPath("$.provider").isEqualTo("OLLAMA");
                    json.assertThat().extractingPath("$.ollamaReachable").isEqualTo(true);
                    json.assertThat().extractingPath("$.installedModels[0]").isEqualTo("llama3.2:latest");
                    json.assertThat().extractingPath("$.modelInstalled").isEqualTo(false); // non ancora scaricato
                });
        // "llama3.2" senza etichetta è "llama3.2:latest", che c'è.
        assertThat(put("{\"provider\": \"OLLAMA\", \"ollamaModel\": \"llama3.2\"}"))
                .hasStatusOk().bodyJson().extractingPath("$.modelInstalled").isEqualTo(true);
    }

    @Test
    void wrongSettingsAre400() {
        assertThat(put("{\"provider\": \"BOH\", \"ollamaModel\": \"qwen2.5:3b\"}")).hasStatus(400);
        assertThat(put("{\"provider\": \"OLLAMA\", \"ollamaModel\": \"qwen; rm -rf /\"}")).hasStatus(400);
        assertThat(put("{\"provider\": \"OLLAMA\"}")).hasStatus(400);
    }

    @Test
    void theDownloadRunsInTheBackgroundAndItsProgressIsVisible() throws InterruptedException {
        // Prima dello scaricamento il modello non c'è, alla fine sì (Ollama lo dice a chi chiede).
        when(ollamaApi.listModels()).thenReturn(models(), models(), models("qwen2.5:3b"));
        // Ollama manda l'avanzamento a pezzi: qui tre messaggi, con una pausa per vedere lo stato "in corso".
        when(ollamaApi.pullModel(any(PullModelRequest.class))).thenReturn(Flux.just(
                        new ProgressResponse("pulling manifest", null, null, null),
                        new ProgressResponse("downloading", "sha256:abc", 2_000L, 500L),
                        new ProgressResponse("success", null, null, null))
                .delayElements(Duration.ofMillis(200)));

        assertThat(mvc.post().uri("/api/ai/model/download")).hasStatus(202);
        verify(ollamaApi).pullModel(any(PullModelRequest.class));

        // Subito dopo: in corso. La richiesta HTTP non ha aspettato la fine.
        assertThat(mvc.get().uri("/api/ai")).bodyJson().extractingPath("$.download.running").isEqualTo(true);
        // Aspettiamo la fine (al massimo 5 secondi), come fa la dashboard chiedendo ogni tanto.
        for (int i = 0; i < 50 && modelDownload.state().running(); i++) {
            Thread.sleep(100);
        }
        assertThat(modelDownload.state().status()).isEqualTo("success");
        assertThat(mvc.get().uri("/api/ai")).bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.download.model").isEqualTo("qwen2.5:3b");
            json.assertThat().extractingPath("$.download.running").isEqualTo(false);
        });
    }

    @Test
    void aStreamThatEndsWithoutTheModelIsAFailureNotASuccess() {
        // Com'è davvero: con internet bloccato Ollama manda {"error": "..."} come messaggio normale e chiude.
        // Spring AI lo legge come un avanzamento vuoto, e lo stream finisce senza errori.
        when(ollamaApi.listModels()).thenReturn(models());
        when(ollamaApi.pullModel(any(PullModelRequest.class))).thenReturn(Flux.just(
                new ProgressResponse("pulling manifest", null, null, null),
                new ProgressResponse(null, null, null, null)));

        assertThat(mvc.post().uri("/api/ai/model/download")).hasStatus(202);

        assertThat(mvc.get().uri("/api/ai")).bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.download.status").isEqualTo("errore");
            json.assertThat().extractingPath("$.download.error").asString()
                    .contains("Ollama ha finito senza scaricare il modello");
            json.assertThat().extractingPath("$.modelInstalled").isEqualTo(false);
        });
    }

    @Test
    void aFailedDownloadTellsWhy() throws InterruptedException {
        when(ollamaApi.listModels()).thenReturn(models());
        when(ollamaApi.pullModel(any(PullModelRequest.class)))
                .thenReturn(Flux.error(new IllegalStateException("pull model manifest: file does not exist")));

        assertThat(mvc.post().uri("/api/ai/model/download")).hasStatus(202);

        assertThat(mvc.get().uri("/api/ai")).bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.download.running").isEqualTo(false);
            json.assertThat().extractingPath("$.download.error").asString().contains("file does not exist");
        });
    }

    private MvcTestResult put(String json) {
        return mvc.put().uri("/api/ai").contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private static ListModelResponse models(String... names) {
        return new ListModelResponse(List.of(names).stream()
                .map(name -> new Model(name, name, null, 0L, "digest", null))
                .toList());
    }
}
