package it.wallettracker.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.function.Consumer;

import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Le frasi del catalogo per i test che non avviano Spring: il test controlla la frase che vedrà l'utente, in italiano,
 * come prima che nel codice ci fossero i codici.
 *
 * <p>Il catalogo si legge come lo legge l'applicazione ({@code spring.messages} in application.yml).
 */
public final class TestMessages {

    private static final Messages MESSAGES = new Messages(source());

    private TestMessages() {
    }

    /** Il servizio delle frasi, per chi lo vuole nel costruttore. */
    public static Messages messages() {
        return MESSAGES;
    }

    public static String text(Message message) {
        return MESSAGES.text(message, Locale.ITALIAN);
    }

    /** La frase di un errore: di una {@link ApiException} o di una {@link MessageException}. */
    public static String text(Throwable error) {
        return switch (error) {
            case ApiException api -> text(api.message());
            case MessageException exception -> text(exception.message());
            default -> throw new AssertionError("Non è un errore con un messaggio per l'utente: " + error, error);
        };
    }

    /**
     * Per {@code assertThatThrownBy(...).satisfies(says("non trovato"))}: la frase dell'errore contiene queste parole.
     * Prende il posto di {@code hasMessageContaining}, che leggerebbe il codice e non la frase.
     */
    public static Consumer<Throwable> says(String part) {
        return error -> assertThat(text(error)).contains(part);
    }

    /** Come {@link #says}, ma la frase deve essere proprio questa. */
    public static Consumer<Throwable> saysExactly(String sentence) {
        return error -> assertThat(text(error)).isEqualTo(sentence);
    }

    static ResourceBundleMessageSource source() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setAlwaysUseMessageFormat(true);
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
