package it.wallettracker.message;

import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Trasforma un {@link Message} nella sua frase, presa dal catalogo {@code messages.properties}.
 *
 * <p>{@link MessageSource} è l'interfaccia di Spring per i testi in più lingue: cerca il codice nel file della lingua
 * richiesta ({@code messages_en.properties} per l'inglese) e, se non c'è, in quello di base ({@code
 * messages.properties}, in italiano). Oggi esiste solo quello di base, quindi la frase è sempre in italiano.
 *
 * <p>La lingua è quella della richiesta in corso: {@link LocaleContextHolder} la conserva per il thread che sta
 * rispondendo (Spring la legge dall'header {@code Accept-Language} del browser).
 */
@Component
public class Messages {

    private final MessageSource source;

    public Messages(MessageSource source) {
        this.source = source;
    }

    /** La frase nella lingua della richiesta in corso. */
    public String text(Message message) {
        return text(message, LocaleContextHolder.getLocale());
    }

    /** La frase del catalogo, dato il codice e i parametri. */
    public String text(String code, Object... args) {
        return text(Message.of(code, args));
    }

    /** La frase in una lingua precisa: per i filtri, che lavorano prima che Spring abbia letto quella della richiesta. */
    public String text(Message message, Locale locale) {
        if (message.isVerbatim()) {
            return String.valueOf(message.args().getFirst());
        }
        // Un parametro può essere a sua volta un messaggio: prima si scrive lui, poi la frase che lo contiene.
        Object[] args = message.args().stream()
                .map(arg -> arg instanceof Message inner ? text(inner, locale) : arg)
                .toArray();
        return source.getMessage(message.code(), args, locale);
    }
}
