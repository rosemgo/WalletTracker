package it.wallettracker.language;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.util.List;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.auth.AppPinRepository;
import it.wallettracker.category.BuiltinCategory;
import it.wallettracker.category.Category;
import it.wallettracker.category.CategoryKind;
import it.wallettracker.category.CategoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * La lingua dell'app, attraverso le API (docs/lingue-2-inglese.md): si legge anche senza PIN, si cambia solo con il
 * PIN, e cambiandola le categorie di partenza seguono la lingua, tranne quelle che l'utente ha rinominato.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
class LanguageControllerTest {

    /** Le richieste "di chi è dentro" (IntegrationTestConfiguration). */
    @Autowired
    MockMvcTester mvc;

    @Autowired
    WebApplicationContext context;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    AppPinRepository pinRepository;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void noLanguageChosen() {
        jdbc.update("UPDATE app_settings SET language = NULL");
    }

    /** Il database è lo stesso degli altri test, che si aspettano le categorie in italiano: lo si lascia com'era. */
    @AfterEach
    void backToItalianAndNothingChosen() {
        categoryRepository.findAll().stream().filter(c -> c.getBuiltinKey() == null)
                .forEach(categoryRepository::delete);
        for (BuiltinCategory builtin : BuiltinCategory.values()) {
            jdbc.update("UPDATE category SET name = ? WHERE builtin_key = ?", builtin.name(Language.IT),
                    builtin.name());
        }
        jdbc.update("UPDATE app_settings SET language = NULL");
        pinRepository.deleteAll();
    }

    @Test
    void aNewInstallationHasNoLanguageYetAndSaysWhichOnesItKnows() {
        assertThat(mvc.get().uri("/api/language")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.language").isNull();
            json.assertThat().extractingPath("$.supported").isEqualTo(List.of("it", "en"));
        });
    }

    @Test
    void theLanguageIsReadWithoutThePinButChangedOnlyWithIt() {
        // Un PIN c'è, e questo browser non l'ha inserito: come il telefono alla pagina del PIN.
        jdbc.update("INSERT INTO app_pin (id, pin_hash, recovery_hash, generation, updated_at) "
                + "VALUES (1, 'x', 'x', 1, now())");
        MockMvcTester outside = MockMvcTester.create(
                MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());

        assertThat(outside.get().uri("/api/language")).hasStatusOk();
        assertThat(outside.put().uri("/api/language").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"language\": \"en\"}")).hasStatus(401);
        assertThat(mvc.get().uri("/api/language")).bodyJson().extractingPath("$.language").isNull();
    }

    @Test
    void choosingEnglishRenamesTheStarterCategoriesAndItalianBringsThemBack() {
        assertThat(choose("en")).hasStatusOk().bodyJson().extractingPath("$.language").isEqualTo("en");

        assertThat(names()).contains("Groceries", "Bars and restaurants", "Stamp duty", "Card settlement")
                .doesNotContain("Spesa alimentare", "Bar e ristoranti", "Imposta di bollo", "Saldo carta");
        // Ogni categoria di partenza ha il suo nome inglese: nessuna è rimasta indietro.
        for (BuiltinCategory builtin : BuiltinCategory.values()) {
            assertThat(nameOf(builtin)).isEqualTo(builtin.name(Language.EN));
        }

        assertThat(choose("it")).hasStatusOk().bodyJson().extractingPath("$.language").isEqualTo("it");
        for (BuiltinCategory builtin : BuiltinCategory.values()) {
            assertThat(nameOf(builtin)).isEqualTo(builtin.name(Language.IT));
        }
    }

    @Test
    void aCategoryRenamedByTheUserKeepsItsNameInEveryLanguage() {
        Long id = jdbc.queryForObject("SELECT id FROM category WHERE builtin_key = 'GROCERIES'", Long.class);
        assertThat(mvc.put().uri("/api/categories/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Supermercato\", \"kind\": \"EXPENSE\"}")).hasStatusOk();

        choose("en");
        assertThat(nameOf(BuiltinCategory.GROCERIES)).isEqualTo("Supermercato");
        assertThat(nameOf(BuiltinCategory.EATING_OUT)).isEqualTo("Bars and restaurants");

        choose("it");
        assertThat(nameOf(BuiltinCategory.GROCERIES)).isEqualTo("Supermercato");

        // Rimesso a mano il nome di partenza (in una lingua qualsiasi), torna a seguire la lingua.
        assertThat(mvc.put().uri("/api/categories/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"spesa alimentare\", \"kind\": \"EXPENSE\"}")).hasStatusOk();
        choose("en");
        assertThat(nameOf(BuiltinCategory.GROCERIES)).isEqualTo("Groceries");
    }

    @Test
    void aNameAlreadyTakenByACategoryOfTheUserIsLeftToTheUser() {
        categoryRepository.save(new Category("Groceries", CategoryKind.EXPENSE));

        assertThat(choose("en")).hasStatusOk();

        // Due categorie con lo stesso nome non possono esistere: quella di partenza resta in italiano, le altre no.
        assertThat(nameOf(BuiltinCategory.GROCERIES)).isEqualTo("Spesa alimentare");
        assertThat(nameOf(BuiltinCategory.EATING_OUT)).isEqualTo("Bars and restaurants");
        assertThat(names()).containsOnlyOnce("Groceries");
    }

    @Test
    void movementsRulesAndBudgetsFollowBecauseTheyPointToTheCategoryNotToItsName() {
        Long id = jdbc.queryForObject("SELECT id FROM category WHERE builtin_key = 'COUPONS'", Long.class);
        Long rules = jdbc.queryForObject("SELECT count(*) FROM classification_rule WHERE category_id = ?",
                Long.class, id);
        assertThat(rules).isPositive(); // la regola di partenza "Cedola"

        choose("en");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM classification_rule WHERE category_id = ?", Long.class,
                id)).isEqualTo(rules);
        assertThat(mvc.get().uri("/api/rules")).bodyJson().extractingPath("$[?(@.name == 'Cedola')].category")
                .isEqualTo(List.of("Coupons")); // il nome della regola resta com'è, la sua categoria segue la lingua
    }

    @Test
    void anUnknownLanguageIsRefusedAndNothingChanges() {
        assertThat(choose("xx")).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("language.unsupported");
        assertThat(choose("")).hasStatus(400);

        assertThat(mvc.get().uri("/api/language")).bodyJson().extractingPath("$.language").isNull();
        assertThat(nameOf(BuiltinCategory.GROCERIES)).isEqualTo("Spesa alimentare");
    }

    /** Le frasi del backend seguono la lingua chiesta dalla richiesta: la dashboard chiede quella che sta mostrando. */
    @Test
    void theSentencesOfTheBackendFollowTheLanguageOfTheRequest() {
        String duplicate = "{\"name\": \"Spesa alimentare\", \"kind\": \"EXPENSE\"}";

        assertThat(save(duplicate, "en")).hasStatus(409).bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.detail").isEqualTo("A category \"Spesa alimentare\" already exists.");
            json.assertThat().extractingPath("$.code").isEqualTo("category.duplicate");
        });
        // Come la manda un browser: più lingue, con il paese. Conta la prima che conosciamo.
        assertThat(save(duplicate, "en-GB,en;q=0.9,it;q=0.8")).bodyJson().extractingPath("$.detail")
                .isEqualTo("A category \"Spesa alimentare\" already exists.");
        assertThat(save(duplicate, "it-IT,it;q=0.9")).bodyJson().extractingPath("$.detail")
                .isEqualTo("Esiste già una categoria \"Spesa alimentare\".");
        // Una lingua che non abbiamo, o nessuna: l'italiano.
        assertThat(save(duplicate, "de-DE")).bodyJson().extractingPath("$.detail")
                .isEqualTo("Esiste già una categoria \"Spesa alimentare\".");
        assertThat(mvc.post().uri("/api/categories").contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .bodyJson().extractingPath("$.detail").isEqualTo("Esiste già una categoria \"Spesa alimentare\".");
    }

    private MvcTestResult choose(String language) {
        return mvc.put().uri("/api/language").contentType(MediaType.APPLICATION_JSON)
                .content("{\"language\": \"" + language + "\"}").exchange();
    }

    private MvcTestResult save(String body, String acceptLanguage) {
        return mvc.post().uri("/api/categories").header("Accept-Language", acceptLanguage)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private List<String> names() {
        return jdbc.queryForList("SELECT name FROM category", String.class);
    }

    private String nameOf(BuiltinCategory builtin) {
        return jdbc.queryForObject("SELECT name FROM category WHERE builtin_key = ?", String.class, builtin.name());
    }
}
