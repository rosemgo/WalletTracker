package it.wallettracker.language;

import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La lingua dell'app (docs/lingue-2-inglese.md).
 *
 * <p>È un'impostazione dell'<b>installazione</b>, non del browser: l'app è di una persona sola, che la apre dal
 * computer e dal telefono e la vuole uguale. E i nomi delle categorie di partenza sono dati condivisi: non possono
 * essere in una lingua sul telefono e in un'altra sul computer.
 *
 * <p>Le frasi che il backend manda alla dashboard seguono invece la lingua di ogni <b>richiesta</b> (header {@code
 * Accept-Language}, che la dashboard riempie con la lingua in cui sta mostrando le pagine: {@code LanguageConfig}).
 * Di solito coincidono; nei pochi secondi in cui un dispositivo non ha ancora saputo del cambio, ognuno legge le
 * frasi nella lingua che sta guardando.
 */
@Service
public class LanguageService {

    private final AppSettingsRepository repository;

    public LanguageService(AppSettingsRepository repository) {
        this.repository = repository;
    }

    /** La lingua scelta; vuoto se nessuno l'ha ancora scelta (un'installazione nuova, prima della prima schermata). */
    @Transactional(readOnly = true)
    public Optional<Language> current() {
        return repository.findById(AppSettings.ID).map(AppSettings::getLanguage).flatMap(Language::of);
    }

    /** Per ordinare i nomi in ordine alfabetico: la lingua scelta, o l'italiano finché non c'è. */
    @Transactional(readOnly = true)
    public Locale locale() {
        return current().orElse(Language.IT).locale();
    }

    /** Salva la lingua. Chi la cambia per l'utente passa da {@link LanguageSwitch}, che sistema anche le categorie. */
    @Transactional
    void save(Language language) {
        repository.findById(AppSettings.ID).orElseThrow().setLanguage(language.code());
    }
}
