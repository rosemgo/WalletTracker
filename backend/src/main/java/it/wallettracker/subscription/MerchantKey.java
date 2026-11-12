package it.wallettracker.subscription;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * La "chiave" di un esercente: il testo di un movimento ridotto all'essenziale, per riconoscere lo stesso
 * esercente in movimenti diversi (abbonamenti, suggerimenti di categoria).
 *
 * <p>Come si calcola:
 * <ol>
 *   <li><b>quale testo</b>: la controparte, se c'è e non è il titolare del conto; altrimenti la causale.
 *       Alcune banche mettono come controparte il titolare stesso e il negozio nella causale: usare la
 *       controparte darebbe a tutti quei movimenti, di negozi diversi, la stessa chiave (il tuo nome).
 *       Se la causale scrive il nome dopo un'<b>etichetta</b> ("Anagrafica Ordinante ACME SRL", "Creditor id.
 *       IT00ZZZ… ACME SRL Id Mandato…"), si prende solo quel nome: il resto della causale è uguale per tutti i
 *       bonifici e gli addebiti della stessa banca, e con il taglio a 60 caratteri il nome si perdeva
 *       (docs/40);</li>
 *   <li><b>maiuscole</b>, e tutto ciò che non è una lettera o una cifra diventa uno spazio;</li>
 *   <li>si tolgono le <b>parole che contengono cifre</b>: date, ore, numeri di carta e codici d'ordine
 *       ({@code BT9YM4924}), che cambiano a ogni movimento. Togliere solo le cifre lascerebbe i pezzi di
 *       lettere del codice ({@code BT YM}), diversi ogni volta.</li>
 * </ol>
 * Esempi: "Pagamento carta del 03/10/2026 alle 10:12 presso NETFLIX.COM 4521" →
 * "PAGAMENTO CARTA DEL ALLE PRESSO NETFLIX COM"; "AMAZON* BT9YM4924 LUXEMBOURG LUX" →
 * "AMAZON LUXEMBOURG LUX".
 *
 * <p>Attenzione: questa regola è "congelata" anche nelle migrazioni {@code db.migration.V16__chiavi_esercente} e
 * {@code db.migration.V28__nome_nella_causale}, che hanno ricalcolato le chiavi salvate. Cambiandola, serve una nuova migrazione che ricalcoli le chiavi salvate
 * (abbonamenti, suggerimenti rifiutati, risposte dell'IA), altrimenti non corrisponderebbero più ai movimenti.
 */
public final class MerchantKey {

    /** Oltre questa lunghezza la chiave viene tagliata: le parole in fondo raramente servono a distinguere. */
    static final int MAX_LENGTH = 60;

    /**
     * Il nome della controparte scritto nella causale dopo un'etichetta, fino all'etichetta che segue (o alla fine):
     * <ul>
     *   <li>bonifici: "Anagrafica Ordinante NOME" (chi ti paga) o "Anagrafica Beneficiario NOME" (chi paghi tu),
     *       seguiti da "Note: ...";</li>
     *   <li>addebiti diretti SEPA (SDD): "Creditor id. CODICE NOME Id Mandato ...", dove il codice è
     *       l'identificativo del creditore (una parola sola, es. IT00ZZZ0000000000).</li>
     * </ul>
     * {@code (.+?)} è "pigro": prende il testo più corto possibile prima di un'etichetta che chiude. {@code (?!...)}
     * esclude un nome che sia proprio quell'etichetta ("Anagrafica Ordinante Note: ...", il nome manca).
     */
    private static final Pattern NAME_IN_DESCRIPTION = Pattern.compile(
            "\\b(?:Anagrafica\\s+(?:Ordinante|Beneficiario)|Creditor\\s+id\\.?\\s+\\S+)\\s+"
                    + "(?!Note\\s*:|Id\\s+Mandato\\b)(.+?)" // il nome non può essere l'etichetta che segue
                    + "(?=\\s+Note\\s*:|\\s+Id\\s+Mandato\\b|\\s*$)",
            Pattern.CASE_INSENSITIVE);

    private MerchantKey() {
    }

    /**
     * La chiave di un movimento; null se non c'è un testo utile.
     *
     * @param ownerNames i nomi dei titolari dei conti, come insiemi di parole maiuscole (vedi
     *                   {@code ClassificationService.ownerNames})
     */
    public static String of(String counterparty, String description, List<Set<String>> ownerNames) {
        String text = merchantText(counterparty, description, ownerNames);
        return text == null ? null : keyOf(text);
    }

    /**
     * Il testo che identifica l'esercente: la controparte, se c'è e non è un titolare; altrimenti il nome scritto
     * nella causale dopo un'etichetta, se c'è; altrimenti la causale intera.
     */
    public static String merchantText(String counterparty, String description, List<Set<String>> ownerNames) {
        if (counterparty != null && !counterparty.isBlank() && !ownerNames.contains(words(counterparty))) {
            return counterparty;
        }
        if (description == null || description.isBlank()) {
            return null;
        }
        String name = nameInDescription(description);
        return name != null ? name : description;
    }

    /** Il nome dopo un'etichetta (vedi {@link #NAME_IN_DESCRIPTION}); null se non c'è, o se non ha lettere. */
    public static String nameInDescription(String description) {
        Matcher matcher = NAME_IN_DESCRIPTION.matcher(description);
        if (!matcher.find()) {
            return null;
        }
        String name = matcher.group(1).strip();
        return name.chars().anyMatch(Character::isLetter) ? name : null;
    }

    /**
     * La prima parola della chiave ("AMAZON" per "AMAZON LUXEMBOURG LUX"): un confronto più largo, usato dove
     * sbagliare costa poco (l'ordine del menu della categoria), non dove si assegna qualcosa.
     */
    public static String firstWord(String key) {
        return key == null ? null : key.split(" ")[0];
    }

    /** Dal testo alla chiave (passi 2 e 3 sopra). */
    static String keyOf(String text) {
        String[] tokens = text.toUpperCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim().split(" ");
        String key = Arrays.stream(tokens)
                .filter(token -> token.chars().noneMatch(Character::isDigit))
                .collect(Collectors.joining(" "))
                .trim();
        if (key.length() > MAX_LENGTH) {
            key = key.substring(0, MAX_LENGTH).trim();
        }
        return key.length() >= 2 ? key : null;
    }

    /** Le parole di un testo, in maiuscolo: lo stesso criterio dei nomi dei titolari. */
    private static Set<String> words(String text) {
        return Arrays.stream(text.toUpperCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(word -> !word.isEmpty())
                .collect(Collectors.toSet());
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
