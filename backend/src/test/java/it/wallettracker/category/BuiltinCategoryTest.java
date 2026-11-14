package it.wallettracker.category;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import it.wallettracker.IntegrationTestConfiguration;
import it.wallettracker.language.Language;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * I nomi delle categorie di partenza scritti nel Java ({@link BuiltinCategory}) e quelli inseriti dalle migrazioni
 * devono essere gli stessi: se differissero, una categoria mai toccata sembrerebbe "rinominata dall'utente" e non
 * seguirebbe più la lingua.
 */
@SpringBootTest
@Import(IntegrationTestConfiguration.class)
class BuiltinCategoryTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void everyStarterCategoryOfTheDatabaseHasItsKeyAndItsItalianName() {
        List<String> inTheDatabase = jdbc.queryForList(
                "SELECT builtin_key || '=' || name FROM category WHERE builtin ORDER BY builtin_key", String.class);

        List<String> inTheCode = Arrays.stream(BuiltinCategory.values())
                .map(builtin -> builtin.name() + "=" + builtin.name(Language.IT)).sorted().toList();

        assertThat(inTheDatabase).isEqualTo(inTheCode);
    }

    @Test
    void everyStarterCategoryHasANameInEveryLanguageAndNoTwoShareOne() {
        for (Language language : Language.values()) {
            List<String> names = Arrays.stream(BuiltinCategory.values())
                    .map(builtin -> builtin.name(language).toLowerCase()).toList();
            assertThat(names).doesNotContainNull().doesNotHaveDuplicates();
        }
    }

    @Test
    void aNameIsTheStarterOneInAnyLanguageWhateverTheCase() {
        assertThat(BuiltinCategory.GROCERIES.isDefaultName("Spesa alimentare")).isTrue();
        assertThat(BuiltinCategory.GROCERIES.isDefaultName("GROCERIES")).isTrue();
        assertThat(BuiltinCategory.GROCERIES.isDefaultName("Supermercato")).isFalse();
    }
}
