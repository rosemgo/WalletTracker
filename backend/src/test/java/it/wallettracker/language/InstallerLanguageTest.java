package it.wallettracker.language;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * La lingua scelta nello script di installazione (docs/lingue-3-installazione.md): una proposta per la prima
 * schermata di un'installazione nuova, e niente di più.
 */
class InstallerLanguageTest {

    private final LanguageService languages = mock(LanguageService.class);

    private LanguageController.LanguageDto language(String installerLanguage) {
        return new LanguageController(languages, mock(LanguageSwitch.class), installerLanguage).language();
    }

    @Test
    void aNewInstallationSuggestsTheLanguageChosenInTheInstaller() {
        when(languages.current()).thenReturn(Optional.empty());

        assertThat(language("en").language()).isNull();
        assertThat(language("en").suggested()).isEqualTo("en");
        assertThat(language("it").suggested()).isEqualTo("it");
        // Scritta a mano nel .env, con una maiuscola o uno spazio: vale lo stesso.
        assertThat(language(" EN ").suggested()).isEqualTo("en");
    }

    @Test
    void withoutAnInstallerLanguageOrWithOneWeDoNotKnowTheBrowserDecides() {
        when(languages.current()).thenReturn(Optional.empty());

        assertThat(language("").suggested()).isNull(); // ./mvnw, o un .env di prima delle lingue
        assertThat(language("fr").suggested()).isNull();
    }

    @Test
    void onceALanguageIsChosenInTheAppTheInstallerOneNoLongerCounts() {
        when(languages.current()).thenReturn(Optional.of(Language.IT));

        LanguageController.LanguageDto dto = language("en");

        assertThat(dto.language()).isEqualTo("it");
        assertThat(dto.suggested()).isNull();
    }
}
