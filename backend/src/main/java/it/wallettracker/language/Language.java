package it.wallettracker.language;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Le lingue dell'app. Aggiungerne una vuol dire: una voce qui, un file {@code messages_xx.properties} per le frasi del
 * backend, i nomi delle categorie di partenza ({@code BuiltinCategory}) e la traduzione della dashboard.
 */
public enum Language {

    /** L'italiano: la lingua in cui l'app è scritta, e quella del catalogo di base {@code messages.properties}. */
    IT("it", Locale.ITALIAN),
    EN("en", Locale.ENGLISH);

    private final String code;
    private final Locale locale;

    Language(String code, Locale locale) {
        this.code = code;
        this.locale = locale;
    }

    /** Il codice a due lettere (ISO 639-1): è quello che viaggia nelle API e che si salva nel database. */
    public String code() {
        return code;
    }

    /** Per le cose di Java che dipendono dalla lingua: l'ordine alfabetico, le frasi del catalogo. */
    public Locale locale() {
        return locale;
    }

    public static Optional<Language> of(String code) {
        return Arrays.stream(values()).filter(language -> language.code.equals(code)).findFirst();
    }
}
