package it.wallettracker.ai;

import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import it.wallettracker.message.Message;

/**
 * Dagli errori delle librerie a messaggi che dicono che cosa fare. Le librerie (di Ollama, di Google) lanciano
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

    public static Message describe(Throwable error, AiProvider provider) {
        String name = provider.label;
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof IOException || t instanceof org.springframework.web.client.ResourceAccessException) {
                // messaggio: Non riesco a raggiungere {0}: controlla la connessione a internet (o se Ollama è avviato).
                // messaggio: Non riesco a raggiungere {0}: controlla la connessione a internet.
                return Message.of(provider == AiProvider.OLLAMA ? "ai.error.unreachable-ollama" : "ai.error.unreachable",
                        name);
            }
            if (INVALID_KEY.matcher(String.valueOf(t.getMessage())).find()) {
                return invalidKey(name);
            }
            Matcher code = HTTP_CODE.matcher(String.valueOf(t.getMessage()));
            if (code.find()) {
                return switch (code.group(1)) {
                    case "401", "403" -> invalidKey(name);
                    // messaggio: Il modello non esiste per {0}: controlla il nome nelle Impostazioni.
                    case "404" -> Message.of("ai.error.model-not-found", name);
                    // messaggio: {0} risponde "troppe richieste": hai raggiunto il limite del tuo piano (al minuto o
                    //            al giorno). Riprova più tardi: le risposte già avute restano salvate.
                    case "429" -> Message.of("ai.error.too-many-requests", name);
                    // messaggio: {0} ha rifiutato la domanda (richiesta non valida): {1}
                    case "400" -> Message.of("ai.error.bad-request", name, shortly(t.getMessage()));
                    // messaggio: {0} ha un problema temporaneo: riprova tra qualche minuto.
                    default -> Message.of("ai.error.temporary", name);
                };
            }
        }
        // messaggio: {0} non ha risposto: {1}
        return Message.of("ai.error.no-answer", name, shortly(error.getMessage()));
    }

    /** Il codice HTTP di errore nel messaggio dell'eccezione o delle sue cause (400, 429, 503...), se c'è. */
    public static Optional<Integer> httpCode(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            Matcher code = HTTP_CODE.matcher(String.valueOf(t.getMessage()));
            if (code.find()) {
                return Optional.of(Integer.parseInt(code.group(1)));
            }
        }
        return Optional.empty();
    }

    private static Message invalidKey(String name) {
        // messaggio: La chiave di {0} non è valida, o non ha i permessi: controllala o creane una nuova, poi
        //            inseriscila di nuovo nelle Impostazioni.
        return Message.of("ai.error.invalid-key", name);
    }

    /** I messaggi delle librerie possono essere lunghissimi (tutto il JSON della risposta): bastano 200 caratteri. */
    private static String shortly(String message) {
        String text = String.valueOf(message);
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
