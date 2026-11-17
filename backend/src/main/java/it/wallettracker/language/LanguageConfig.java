package it.wallettracker.language;

import java.util.Arrays;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * In che lingua il backend scrive le frasi di una risposta: quella chiesta dalla richiesta, nell'header {@code
 * Accept-Language}, se è una delle nostre; altrimenti l'italiano.
 *
 * <p>La dashboard riempie quell'header con la lingua in cui sta mostrando le pagine (il suo intercettore {@code
 * languageInterceptor}), non con quella del browser: così un errore arriva sempre nella lingua della pagina che lo
 * mostra. Spring cerca il bean con questo nome preciso, {@code localeResolver}.
 */
@Configuration(proxyBeanMethods = false)
public class LanguageConfig {

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(Arrays.stream(Language.values()).map(Language::locale).toList());
        resolver.setDefaultLocale(Language.IT.locale());
        return resolver;
    }
}
