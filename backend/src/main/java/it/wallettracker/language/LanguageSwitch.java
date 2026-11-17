package it.wallettracker.language;

import it.wallettracker.category.CategoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Il cambio di lingua: la nuova lingua e, insieme, i nomi delle categorie di partenza.
 *
 * <p>È una classe a parte da {@link LanguageService} perché {@code CategoryService} ha bisogno della lingua (per
 * l'ordine alfabetico) e il cambio di lingua ha bisogno di {@code CategoryService}: in una classe sola, i due
 * servizi dipenderebbero l'uno dall'altro, e Spring non saprebbe quale creare per primo.
 */
@Service
public class LanguageSwitch {

    private static final Logger log = LoggerFactory.getLogger(LanguageSwitch.class);

    private final LanguageService languages;
    private final CategoryService categories;

    public LanguageSwitch(LanguageService languages, CategoryService categories) {
        this.languages = languages;
        this.categories = categories;
    }

    /**
     * Sceglie la lingua. Le categorie di partenza che hanno ancora il loro nome passano alla nuova lingua, nella
     * stessa transazione: o cambia tutto, o niente. Quelle rinominate dall'utente non si toccano.
     */
    @Transactional
    public void choose(Language language) {
        languages.save(language);
        int renamed = categories.translateBuiltin(language);
        log.info("Lingua dell'app: {} ({} categorie di partenza rinominate)", language.code(), renamed);
    }
}
