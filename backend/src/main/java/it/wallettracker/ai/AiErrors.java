package it.wallettracker.ai;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dagli errori delle librerie a messaggi che dicono che cosa fare. Le librerie di Mistral e di Google lanciano
 * eccezioni diverse, ma il codice HTTP della risposta c'è sempre nel messaggio ("HTTP 401", "401 Unauthorized"):
 * guardiamo quello, nell'eccezione e nelle sue cause.
 *
 * <p>Un'eccezione, scoperta provando con Google vero: con una chiave sbagliata Google non risponde 401 ma
 * <b>400</b> ("richiesta non valida"), con il motivo {@code API_KEY_INVALID} nel messaggio. Lo cerchiamo prima del
 * codice, altrimenti diremmo "richiesta non valida" invece di "chiave non valida".
 *
 * <p>Provato da AiErrorsTest, e dal vivo con i server finti di CloudChatTest.
 */
public final class AiErrors {

    /** Le parole con cui i servizi dicono "chiave sbagliata" anche senza un 401 (Google: 400 API_KEY_INVALID). */
    private static final Pattern INVALID_KEY = Pattern.compile("API_KEY_INVALID|API key not valid|Invalid API key",
            Pattern.CASE_INSENSITIVE);

    /** Un codice HTTP di errore da solo (non dentro un numero più lungo, come una data o un id). */
    private static final Pattern HTTP_CODE = Pattern.compile("(?<!\\d)(400|401|403|404|429|500|503)(?!\\d)");

    private AiErrors() {
    }

    public static String describe(Throwable error, AiProvider provider) {
        String name = provider.label;
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof IOException || t instanceof org.springframework.web.client.ResourceAccessException) {
                return "Non riesco a raggiungere " + name + ": controlla la connessione a internet"
                        + (provider == AiProvider.OLLAMA ? " (o se Ollama è avviato)." : ".");
            }
            if (INVALID_KEY.matcher(String.valueOf(t.getMessage())).find()) {
                return invalidKey(name);
            }
            Matcher code = HTTP_CODE.matcher(String.valueOf(t.getMessage()));
            if (code.find()) {
                return switch (code.group(1)) {
                    case "401", "403" -> invalidKey(name);
                    case "404" -> "Il modello non esiste per " + name + ": controlla il nome nelle Impostazioni.";
                    case "429" -> name + " risponde \"troppe richieste\": hai raggiunto il limite del tuo piano (al "
                            + "minuto o al giorno). Riprova più tardi: le risposte già avute restano salvate.";
                    case "400" -> name + " ha rifiutato la domanda (richiesta non valida): " + shortly(t.getMessage());
                    default -> name + " ha un problema temporaneo: riprova tra qualche minuto.";
                };
            }
        }
        return name + " non ha risposto: " + shortly(error.getMessage());
    }

    private static String invalidKey(String name) {
        return "La chiave di " + name + " non è valida, o non ha i permessi: controllala o creane una nuova, poi "
                + "inseriscila di nuovo nelle Impostazioni.";
    }

    /** I messaggi delle librerie possono essere lunghissimi (tutto il JSON della risposta): bastano 200 caratteri. */
    private static String shortly(String message) {
        String text = String.valueOf(message);
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
