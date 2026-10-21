package it.wallettracker.suggestion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import it.wallettracker.subscription.MerchantKey;

/**
 * I suggerimenti di categoria "dalla tua storia" (Fase 3, passo 7a): se hai scelto a mano la categoria di un
 * movimento di un esercente, gli altri movimenti dello stesso esercente ancora senza categoria ricevono lo
 * stesso suggerimento.
 *
 * <p>Lo stesso esercente si riconosce con {@link MerchantKey} (come per gli abbonamenti): "NETFLIX.COM 4521"
 * e "NETFLIX.COM 7788" sono lo stesso esercente.
 *
 * <p>Le regole di prudenza (il programma suggerisce, non indovina):
 * <ul>
 *   <li>se per lo stesso esercente hai scelto categorie <b>diverse</b> (Amazon: una volta spesa alimentare,
 *       una volta elettronica), non c'è un suggerimento: decidi tu movimento per movimento;</li>
 *   <li>un suggerimento rifiutato ("no") per quell'esercente non torna;</li>
 *   <li>è solo un suggerimento: finché non lo accetti, il movimento resta senza categoria.</li>
 * </ul>
 * È una classe "pura" (niente database, niente Spring), provata da HistorySuggesterTest.
 *
 * <p>Dal passo 7b.3 ci sono anche i suggerimenti dell'<b>IA</b>, per gli esercenti che non hai mai scelto: le
 * risposte salvate di "Chiedi all'IA" ({@link AiAnswer}). Valgono le stesse regole (il "no" non torna, decidi
 * tu), e la tua storia ha sempre la precedenza: se scegli a mano un esercente, la risposta dell'IA non conta più.
 */
public final class HistorySuggester {

    /** Una tua scelta: il movimento di questo esercente l'hai messo in questa categoria. */
    public record Example(String merchantKey, Long categoryId, String category) {
    }

    /** Un movimento ancora senza categoria. */
    public record Candidate(String merchantKey, String text, LocalDate date) {
    }

    /** Un "no" già detto: per questo esercente, questa categoria non va proposta. */
    public record Dismissal(String merchantKey, Long categoryId) {
    }

    /** La risposta dell'IA per un esercente: questa categoria, data da questo modello. */
    public record AiAnswer(String merchantKey, Long categoryId, String category, String model) {
    }

    /**
     * Un suggerimento, per esercente.
     *
     * @param merchantKey  l'esercente (la chiave)
     * @param merchantName un nome leggibile, dal movimento più recente
     * @param categoryId   la categoria suggerita
     * @param category     il suo nome
     * @param transactions quanti movimenti senza categoria la riceverebbero
     * @param examples     su quante tue scelte si basa (0 per un suggerimento dell'IA)
     * @param model        il modello che l'ha suggerito, o null se viene dalla tua storia
     */
    public record Suggestion(String merchantKey, String merchantName, Long categoryId, String category,
            int transactions, int examples, String model) {
    }

    /**
     * Una categoria che hai già usato per un esercente, e quante volte. Serve nel menu della categoria di un
     * movimento: in cima le categorie già usate per il suo esercente, la più usata per prima.
     */
    public record UsedCategory(Long categoryId, String category, int times) {
    }

    /**
     * Le categorie già usate per gli esercenti che iniziano con questa parola.
     *
     * @param word       la prima parola dell'esercente (es. "AMAZON"); null se il movimento non ha testo
     * @param categories le categorie, la più usata per prima
     */
    public record UsedCategories(String word, List<UsedCategory> categories) {
    }

    private HistorySuggester() {
    }

    /**
     * Le categorie usate per gli esempi con questa chiave, la più usata per prima (a parità, in ordine
     * alfabetico). Al contrario di {@link #suggest}, qui le categorie diverse non sono un problema: è per un
     * movimento solo, e scegli tu quale. Chi chiama decide quale chiave usare (per il menu: la prima parola).
     */
    public static List<UsedCategory> usedCategories(List<Example> examples, String merchantKey) {
        if (merchantKey == null) {
            return List.of(); // un movimento senza testo non ha un esercente
        }
        Map<Long, Integer> times = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (Example example : examples) {
            if (merchantKey.equals(example.merchantKey())) {
                times.merge(example.categoryId(), 1, Integer::sum);
                names.put(example.categoryId(), example.category());
            }
        }
        return times.entrySet().stream()
                .map(e -> new UsedCategory(e.getKey(), names.get(e.getKey()), e.getValue()))
                .sorted(Comparator.comparingInt(UsedCategory::times).reversed()
                        .thenComparing(UsedCategory::category))
                .toList();
    }

    /** I suggerimenti dalla tua storia soltanto (senza IA). */
    public static List<Suggestion> suggest(List<Example> examples, List<Candidate> candidates,
            Set<Dismissal> dismissals) {
        return suggest(examples, candidates, dismissals, List.of());
    }

    /** I suggerimenti dalla tua storia e, per gli esercenti che non hai mai scelto, quelli dell'IA. */
    public static List<Suggestion> suggest(List<Example> examples, List<Candidate> candidates,
            Set<Dismissal> dismissals, List<AiAnswer> aiAnswers) {
        // 1. Per ogni esercente, le categorie che hai scelto manualmente (e quante volte).
        Map<String, Map<Long, Integer>> choices = new HashMap<>(); //mappa costituita da {commerciante, {categoria, numero di volte che quel commerciante ha avuto quella categoria nei movimenti}}
        Map<Long, String> categoryNames = new HashMap<>();
        for (Example example : examples) {
            choices.computeIfAbsent(example.merchantKey(), key -> new HashMap<>())
                    .merge(example.categoryId(), 1, Integer::sum); //semplicemente somma 1 al valore della mappa, in pratica è un contatore del numero di volte che compare una categoria per quel commerciante
            categoryNames.put(example.categoryId(), example.category());
        }

        // 2. I movimenti senza categoria, raggruppati per esercente; il nome viene dal più recente.
        Map<String, List<Candidate>> byMerchant = new HashMap<>();
        for (Candidate candidate : candidates) {
            byMerchant.computeIfAbsent(candidate.merchantKey(), key -> new ArrayList<>()).add(candidate);
        }

        Map<String, AiAnswer> aiByMerchant = new HashMap<>();
        for (AiAnswer answer : aiAnswers) {
            aiByMerchant.put(answer.merchantKey(), answer);
        }

        // 3. Un suggerimento per ogni esercente con una sola categoria scelta, se non l'hai già rifiutata.
        List<Suggestion> suggestions = new ArrayList<>();
        for (Map.Entry<String, List<Candidate>> entry : byMerchant.entrySet()) {
            Map<Long, Integer> chosen = choices.get(entry.getKey());
            if (chosen == null) {
                // Mai scelto a mano: c'è forse una risposta dell'IA.
                AiAnswer answer = aiByMerchant.get(entry.getKey());
                if (answer != null && !dismissals.contains(new Dismissal(entry.getKey(), answer.categoryId()))) {
                    suggestions.add(new Suggestion(entry.getKey(), nameOf(entry.getValue()), answer.categoryId(),
                            answer.category(), entry.getValue().size(), 0, answer.model()));
                }
                continue;
            }
            if (chosen.size() != 1) {
                continue; // scelte diverse: nessun suggerimento, nemmeno dall'IA
            }
            Map.Entry<Long, Integer> only = chosen.entrySet().iterator().next();
            if (dismissals.contains(new Dismissal(entry.getKey(), only.getKey()))) {
                continue;
            }
            suggestions.add(new Suggestion(entry.getKey(), nameOf(entry.getValue()), only.getKey(),
                    categoryNames.get(only.getKey()), entry.getValue().size(), only.getValue(), null));
        }
        // Prima quelli dalla tua storia (più affidabili), poi quelli dell'IA; in ogni gruppo prima gli esercenti
        // con più movimenti: accettandoli si sistema di più con un clic.
        suggestions.sort(Comparator.comparing((Suggestion s) -> s.model() != null)
                .thenComparing(Comparator.comparingInt(Suggestion::transactions).reversed())
                .thenComparing(Suggestion::merchantName));
        return suggestions;
    }

    /** Il nome da mostrare: dal movimento senza categoria (Candidate) con la data più recente. */
    private static String nameOf(List<Candidate> candidates) {
        Candidate latest = candidates.stream().max(Comparator.comparing(Candidate::date)).orElseThrow();
        return MerchantKey.displayName(latest.text());
    }
}
