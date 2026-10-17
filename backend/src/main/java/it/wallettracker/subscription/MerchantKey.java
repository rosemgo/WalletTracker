package it.wallettracker.subscription;

import java.util.Locale;

/**
 * La "chiave" di un esercente: il testo di un movimento ridotto all'essenziale, per riconoscere lo stesso
 * esercente in movimenti di mesi diversi.
 *
 * <p>Esempio: "Pagamento carta del 03/10/2026 alle 10:12 presso NETFLIX.COM 4521" e "Pagamento carta del
 * 03/11/2026 alle 09:58 presso NETFLIX.COM 4521" diventano entrambi
 * "PAGAMENTO CARTA DEL ALLE PRESSO NETFLIX COM": togliendo cifre e punteggiatura spariscono date, ore,
 * numeri di carta e di operazione, che cambiano ogni volta, e resta quello che non cambia.
 */
public final class MerchantKey {

    /** Oltre questa lunghezza la chiave viene tagliata: le parole in fondo raramente servono a distinguere. */
    static final int MAX_LENGTH = 60;

    private MerchantKey() {
    }

    /** La chiave di un movimento: dalla controparte se c'è, altrimenti dalla causale. Null se non c'è testo. */
    public static String of(String counterparty, String description) {
        String text = counterparty != null && !counterparty.isBlank() ? counterparty : description;
        if (text == null) {
            return null;
        }
        String key = text.toUpperCase(Locale.ROOT)
                .replaceAll("[^\\p{L} ]", " ")   // tutto quello che non è una lettera o uno spazio diventa spazio
                .replaceAll("\\s+", " ")         // più spazi di fila diventano uno
                .trim();
        if (key.length() > MAX_LENGTH) {
            key = key.substring(0, MAX_LENGTH).trim();
        }
        return key.length() >= 2 ? key : null;
    }

    /**
     * Un nome da proporre all'utente: il testo originale senza le parole che contengono cifre (date, ore,
     * numeri di carta), al massimo 100 caratteri. "Pagamento carta del 14/10/2026 alle 10:10 presso
     * NETFLIX.COM 4521" diventa "Pagamento carta del alle presso NETFLIX.COM": non bello, ma senza una data
     * che domani sarebbe sbagliata. L'utente lo può cambiare prima di confermare.
     */
    public static String displayName(String text) {
        String name = text.replaceAll("\\S*\\d\\S*", " ").replaceAll("\\s+", " ").trim();
        if (name.isEmpty()) {
            name = text.trim();
        }
        return name.length() > 100 ? name.substring(0, 100).trim() : name;
    }
}
