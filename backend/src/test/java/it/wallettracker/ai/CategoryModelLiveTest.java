package it.wallettracker.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.category.Category;
import it.wallettracker.category.CategoryKind;
import it.wallettracker.category.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;

/**
 * La misura dei modelli su un insieme di prova (src/test/resources/ai/esercenti-di-prova.csv), con un
 * <b>Ollama vero</b>. Non gira nella CI (servono Ollama e GB di modelli): si lancia a mano, ad esempio
 * <pre>
 * OLLAMA_LIVE=true OLLAMA_MODELS=gemma3:4b,llama3.2:3b ./mvnw test -Dtest=CategoryModelLiveTest
 * </pre>
 * Per ogni modello e ogni variante del prompt (senza e con esempi) stampa quante risposte sono giuste,
 * sbagliate o "non so", e il tempo medio. Vedi docs/28, Parte G.
 */
@SpringBootTest
@Import(IntegrationTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "OLLAMA_LIVE", matches = "true")
class CategoryModelLiveTest {

    /** Alcune "scelte dell'utente" da mostrare come esempio: esercenti diversi da quelli di prova. */
    private static final List<String[]> EXAMPLES = List.of(
            new String[] {"COOP ALLEANZA 3.0", "Spesa alimentare"},
            new String[] {"AUTOGRILL SPA", "Bar e ristoranti"},
            new String[] {"IREN LUCE GAS", "Spese fisse e utenze"},
            new String[] {"TAMOIL 0234", "Trasporti e auto"},
            new String[] {"EASYJET", "Viaggi e vacanze"},
            new String[] {"DISNEY PLUS", "Intrattenimento e tempo libero"},
            new String[] {"FARMACIA SAN MARCO", "Cure sanitarie e farmacia"},
            new String[] {"ALLIANZ SPA", "Assicurazioni e finanza"});

    record Case(String merchant, boolean expense, String expected) {
    }

    @Autowired
    CategoryModel model;

    @Autowired
    CategoryRepository categoryRepository;

    @Test
    void measure() throws IOException {
        List<Case> cases = cases();
        List<String> ollamaModels = Arrays.asList(System.getenv().getOrDefault("OLLAMA_MODELS", "gemma3:4b").split(","));
        System.out.printf("%n%-14s %-12s %6s %6s %8s %8s %8s%n", "modello", "prompt", "giuste", "sbagl.", "non so",
                "%giuste", "s/dom.");
        int asked = 0;
        for (String ollamaModel : ollamaModels) {
            for (boolean withExamples : new boolean[] {false, true}) {
                int right = 0;
                int wrong = 0;
                int unsure = 0;
                long start = System.nanoTime();
                for (Case c : cases) {
                    Optional<Long> answer = model.suggest(question(c, withExamples), AiTarget.ollama(ollamaModel));
                    asked++;
                    Optional<Long> expected = c.expected().isEmpty() ? Optional.empty()
                            : Optional.of(category(c.expected()).getId());
                    if (answer.equals(expected)) {
                        right++;
                    } else if (answer.isEmpty()) {
                        unsure++;
                    } else {
                        wrong++;
                        System.out.printf("   ✗ %-42s atteso %-28s risposta %s%n", c.merchant(),
                                c.expected().isEmpty() ? "(non so)" : c.expected(),
                                answer.map(id -> categoryRepository.findById(id).orElseThrow().getName()).orElse("?"));
                    }
                }
                double seconds = (System.nanoTime() - start) / 1e9 / cases.size();
                System.out.printf("%-14s %-12s %6d %6d %8d %7.0f%% %8.1f%n", ollamaModel,
                        withExamples ? "con esempi" : "senza", right, wrong, unsure, 100.0 * right / cases.size(),
                        seconds);
            }
        }
        assertThat(asked).isPositive();
    }

    private CategoryQuestion question(Case c, boolean withExamples) {
        CategoryKind kind = c.expense() ? CategoryKind.EXPENSE : CategoryKind.INCOME;
        List<CategoryQuestion.Option> options = categoryRepository.findAll().stream()
                .filter(category -> category.getKind() == kind)
                .sorted((a, b) -> Integer.compare(a.getPosition(), b.getPosition()))
                .map(category -> new CategoryQuestion.Option(category.getId(), category.getName()))
                .toList();
        List<CategoryQuestion.Example> examples = withExamples
                ? EXAMPLES.stream().map(e -> new CategoryQuestion.Example(e[0], e[1])).toList()
                : List.of();
        return new CategoryQuestion(c.merchant(), c.expense(), options, examples);
    }

    private Category category(String name) {
        return categoryRepository.findByNameIgnoreCase(name).orElseThrow();
    }

    private static List<Case> cases() throws IOException {
        String csv = new ClassPathResource("ai/esercenti-di-prova.csv").getContentAsString(StandardCharsets.UTF_8);
        return csv.lines()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .map(line -> line.split(";", -1))
                .map(f -> new Case(f[0], f[1].equals("S"), f[2]))
                .toList();
    }
}
