package it.wallettracker.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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
import it.wallettracker.suggestion.SuggestionDismissalRepository;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.BankTransactionRepository;
import it.wallettracker.transaction.TransactionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaApi.ListModelResponse;
import org.springframework.ai.ollama.api.OllamaApi.Model;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * "Chiedi all'IA" (passo 7b.3), dall'inizio alla fine: quali esercenti si chiedono, le risposte salvate, i
 * suggerimenti nella pagina Movimenti, "accetta". Ollama e il modello sono finti ({@code @MockitoBean}): il
 * modello "risponde" quello che decidiamo noi, così si prova la logica senza Ollama (che ha già i suoi test,
 * CategoryModelTest).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class AiSuggestionControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    AiSettingsRepository settingsRepository;

    @Autowired
    AiCategoryAnswerRepository answerRepository;

    @Autowired
    AiAnswerJob answerJob;

    @Autowired
    ClassificationService classificationService;

    @Autowired
    BankTransactionRepository transactionRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    SuggestionDismissalRepository dismissalRepository;

    @MockitoBean
    OllamaApi ollamaApi;

    @MockitoBean
    CategoryModel categoryModel;

    private Account account;
    private Category groceries;
    private int counter;

    @BeforeEach
    void setUp() {
        account = accountRepository.save(Account.manual("Banca", "Conto", null, "EUR", AccountRole.MAIN));
        groceries = categoryRepository.findByNameIgnoreCase("Spesa alimentare").orElseThrow();
        when(ollamaApi.listModels()).thenReturn(new ListModelResponse(
                List.of(new Model("gemma3:4b", "gemma3:4b", null, 0L, "digest", null))));
    }

    @AfterEach
    void cleanDatabase() {
        aiOn(false);
        answerRepository.deleteAll();
        dismissalRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void withTheAiOffThereIsNothingToAsk() {
        expense("CONAD");
        classificationService.classifyAll();

        assertThat(mvc.get().uri("/api/ai/suggestions")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.enabled").isEqualTo(false);
            json.assertThat().extractingPath("$.toAsk").isEqualTo(0);
        });
        assertThat(mvc.post().uri("/api/ai/suggestions")).hasStatus(409).bodyJson()
                .extractingPath("$.detail").asString().contains("spenti");
    }

    @Test
    void askingTheAiGivesSuggestionsYouCanAccept() throws InterruptedException {
        aiOn(true);
        chosen(expense("ESSELUNGA"), groceries);   // l'hai scelto tu: non si chiede all'IA
        expense("ESSELUNGA");
        expense("CONAD SUPERSTORE");
        expense("CONAD SUPERSTORE");
        expense("XYZ 12345");
        classificationService.classifyAll();
        // Il modello finto: Conad è spesa alimentare, XYZ "non so".
        when(categoryModel.suggest(any(CategoryQuestion.class), eq(AiTarget.ollama("gemma3:4b")))).thenAnswer(call -> {
            CategoryQuestion question = call.getArgument(0);
            return question.merchant().contains("CONAD") ? Optional.of(groceries.getId()) : Optional.empty();
        });

        assertThat(mvc.get().uri("/api/ai/suggestions")).bodyJson().extractingPath("$.toAsk").isEqualTo(2);
        assertThat(mvc.post().uri("/api/ai/suggestions")).hasStatus(202);
        waitForTheAnswers();

        assertThat(mvc.get().uri("/api/ai/suggestions")).bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.job.done").isEqualTo(2);
            json.assertThat().extractingPath("$.job.found").isEqualTo(1);
            json.assertThat().extractingPath("$.toAsk").isEqualTo(0); // anche "non so" non si richiede
        });
        verify(categoryModel, times(2)).suggest(any(), any());
        // I suggerimenti: Esselunga dalla tua storia, poi Conad dall'IA. XYZ ("non so") no.
        assertThat(mvc.get().uri("/api/category-suggestions")).bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.length()").isEqualTo(2);
            json.assertThat().extractingPath("$[0].merchantKey").isEqualTo("ESSELUNGA");
            json.assertThat().extractingPath("$[0].model").isNull();
            json.assertThat().extractingPath("$[1].merchantKey").isEqualTo("CONAD SUPERSTORE");
            json.assertThat().extractingPath("$[1].category").isEqualTo("Spesa alimentare");
            json.assertThat().extractingPath("$[1].transactions").isEqualTo(2);
            json.assertThat().extractingPath("$[1].model").isEqualTo("gemma3:4b");
        });

        // "Accetta" funziona come per i suggerimenti dalla storia.
        assertThat(mvc.post().uri("/api/category-suggestions/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchantKey\": \"CONAD SUPERSTORE\", \"categoryId\": " + groceries.getId() + "}"))
                .hasStatusOk().bodyJson().extractingPath("$.updated").isEqualTo(2);
        // Nessun altro esercente da chiedere: un secondo "Chiedi all'IA" lo dice.
        assertThat(mvc.post().uri("/api/ai/suggestions")).hasStatus(409).bodyJson()
                .extractingPath("$.detail").asString().contains("Non ci sono esercenti da chiedere");
    }

    @Test
    void turningTheAiOffHidesItsSuggestions() throws InterruptedException {
        aiOn(true);
        expense("CONAD SUPERSTORE");
        classificationService.classifyAll();
        when(categoryModel.suggest(any(CategoryQuestion.class), any())).thenReturn(Optional.of(groceries.getId()));
        assertThat(mvc.post().uri("/api/ai/suggestions")).hasStatus(202);
        waitForTheAnswers();
        assertThat(mvc.get().uri("/api/category-suggestions")).bodyJson().extractingPath("$.length()").isEqualTo(1);

        aiOn(false);

        assertThat(mvc.get().uri("/api/category-suggestions")).bodyJson().extractingPath("$.length()").isEqualTo(0);
    }

    @Test
    void aModelThatIsNotDownloadedIsReported() {
        aiOn(true);
        when(ollamaApi.listModels()).thenReturn(new ListModelResponse(List.of()));
        expense("CONAD");
        classificationService.classifyAll();

        assertThat(mvc.post().uri("/api/ai/suggestions")).hasStatus(409).bodyJson()
                .extractingPath("$.detail").asString().contains("non è scaricato");
    }

    private void waitForTheAnswers() throws InterruptedException {
        for (int i = 0; i < 50 && answerJob.state().running(); i++) {
            Thread.sleep(100);
        }
    }

    private void aiOn(boolean on) {
        AiSettings settings = settingsRepository.findById(AiSettings.ID).orElseThrow();
        settings.change(on ? AiProvider.OLLAMA : AiProvider.NONE, "gemma3:4b");
        settingsRepository.save(settings);
    }

    private BankTransaction expense(String counterparty) {
        return transactionRepository.save(new BankTransaction(account, "tx-" + (++counter), TransactionStatus.BOOKED,
                LocalDate.of(2026, 10, counter), null, new BigDecimal("-25.00"), "EUR", counterparty, null, "{}"));
    }

    private void chosen(BankTransaction transaction, Category category) {
        transaction.setManualCategory(category);
        transactionRepository.save(transaction);
    }
}
