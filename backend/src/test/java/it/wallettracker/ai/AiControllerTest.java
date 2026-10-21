package it.wallettracker.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.account.Account;
import it.wallettracker.account.AccountRepository;
import it.wallettracker.account.AccountRole;
import it.wallettracker.category.Category;
import it.wallettracker.category.CategoryRepository;
import it.wallettracker.classification.ClassificationService;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.TransactionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * cosa. Così i test (e la CI) non hanno bisogno di Ollama né di un modello scaricato. Per la misura della
 * precisione è finto anche CategoryModel (le sue prove sono in CategoryModelTest).
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

    @Autowired
    ModelEvaluation modelEvaluation;

    @Autowired
    ClassificationService classificationService;

    @Autowired
    BankTransactionRepository transactionRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @MockitoBean
    OllamaApi ollamaApi;

    @MockitoBean
    CategoryModel categoryModel;

    private int counter;

    @AfterEach
    void backToDefaults() {
        AiSettings settings = settingsRepository.findById(AiSettings.ID).orElseThrow();
        settings.change(AiProvider.NONE, "gemma3:4b");
        settingsRepository.save(settings);
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void byDefaultTheAiIsOffAndAnOllamaThatDoesNotAnswerIsReported() {
        when(ollamaApi.listModels()).thenThrow(new ResourceAccessException("Connection refused"));

        assertThat(mvc.get().uri("/api/ai")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.provider").isEqualTo("NONE");
            json.assertThat().extractingPath("$.ollamaModel").isEqualTo("gemma3:4b"); // V19
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
        when(ollamaApi.listModels()).thenReturn(models(), models(), models("gemma3:4b"));
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
            json.assertThat().extractingPath("$.download.model").isEqualTo("gemma3:4b");
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

    @Test
    void theEvaluationAsksTheModelAboutYourChoicesWithoutTellingItTheAnswer() throws InterruptedException {
        Category groceries = categoryRepository.findByNameIgnoreCase("Spesa alimentare").orElseThrow();
        Category bars = categoryRepository.findByNameIgnoreCase("Bar e ristoranti").orElseThrow();
        chosen(expense("ESSELUNGA"), groceries);
        chosen(expense("PIZZERIA DA GINO"), bars);
        classificationService.classifyAll();
        when(ollamaApi.listModels()).thenReturn(models("gemma3:4b"));
        // Il modello finto indovina Esselunga e sbaglia la pizzeria.
        when(categoryModel.suggest(any(CategoryQuestion.class), eq("gemma3:4b")))
                .thenReturn(Optional.of(groceries.getId()));

        assertThat(evaluate("{\"model\": \"gemma3:4b\", \"samples\": 10}"))
                .hasStatus(202);
        for (int i = 0; i < 50 && modelEvaluation.state().running(); i++) {
            Thread.sleep(100);
        }

        assertThat(mvc.get().uri("/api/ai")).bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.evaluation.running").isEqualTo(false);
            json.assertThat().extractingPath("$.evaluation.done").isEqualTo(2);
            json.assertThat().extractingPath("$.evaluation.right").isEqualTo(1);
            json.assertThat().extractingPath("$.evaluation.wrong").isEqualTo(1);
            json.assertThat().extractingPath("$.evaluation.mistakes[0].merchant").isEqualTo("PIZZERIA DA GINO");
            json.assertThat().extractingPath("$.evaluation.mistakes[0].expected").isEqualTo("Bar e ristoranti");
            json.assertThat().extractingPath("$.evaluation.mistakes[0].answer").isEqualTo("Spesa alimentare");
        });
        // Le domande: mai la risposta tra gli esempi.
        ArgumentCaptor<CategoryQuestion> questions = ArgumentCaptor.forClass(CategoryQuestion.class);
        verify(categoryModel, times(2)).suggest(questions.capture(), eq("gemma3:4b"));
        assertThat(questions.getAllValues()).allSatisfy(question -> assertThat(question.examples())
                .noneMatch(example -> example.merchant().equals(question.merchant())));
    }

    @Test
    void withoutChoicesMadeByHandThereIsNothingToMeasure() {
        when(ollamaApi.listModels()).thenReturn(models("gemma3:4b"));

        assertThat(evaluate("{\"model\": \"gemma3:4b\", \"samples\": 10}"))
                .hasStatus(409).bodyJson().extractingPath("$.detail").asString()
                .contains("Non c'è niente da misurare");
    }

    @Test
    void onlyADownloadedModelCanBeMeasured() {
        when(ollamaApi.listModels()).thenReturn(models("llama3.2:3b"));

        assertThat(evaluate("{\"model\": \"gemma3:4b\", \"samples\": 10}"))
                .hasStatus(409).bodyJson().extractingPath("$.detail").asString()
                .contains("non è scaricato");
    }

    @Test
    void wrongEvaluationRequestsAre400() {
        assertThat(evaluate("{\"model\": \"gemma3:4b\", \"samples\": 0}")).hasStatus(400);
        assertThat(evaluate("{\"model\": \"gemma3:4b\", \"samples\": 101}")).hasStatus(400);
        assertThat(evaluate("{\"model\": \"x; rm\", \"samples\": 10}")).hasStatus(400);
    }

    private MvcTestResult evaluate(String json) {
        return mvc.post().uri("/api/ai/evaluation").contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private BankTransaction expense(String counterparty) {
        Account account = accountRepository.findAll().stream().findFirst().orElseGet(() ->
                accountRepository.save(Account.manual("Banca", "Conto", null, "EUR", AccountRole.MAIN)));
        return transactionRepository.save(new BankTransaction(account, "tx-" + (++counter), TransactionStatus.BOOKED,
                LocalDate.of(2026, 10, counter), null, new BigDecimal("-25.00"), "EUR", counterparty, null, "{}"));
    }

    private void chosen(BankTransaction transaction, Category category) {
        transaction.setManualCategory(category);
        transactionRepository.save(transaction);
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
