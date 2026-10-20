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

    /**
     * Un suggerimento, per esercente.
     *
     * @param merchantKey  l'esercente (la chiave)
     * @param merchantName un nome leggibile, dal movimento più recente
     * @param categoryId   la categoria suggerita
     * @param category     il suo nome
     * @param transactions quanti movimenti senza categoria la riceverebbero
     * @param examples     su quante tue scelte si basa
     */
    public record Suggestion(String merchantKey, String merchantName, Long categoryId, String category,
            int transactions, int examples) {
    }

    private HistorySuggester() {
    }

    public static List<Suggestion> suggest(List<Example> examples, List<Candidate> candidates,
            Set<Dismissal> dismissals) {
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

        // 3. Un suggerimento per ogni esercente con una sola categoria scelta, se non l'hai già rifiutata.
        List<Suggestion> suggestions = new ArrayList<>();
        for (Map.Entry<String, List<Candidate>> entry : byMerchant.entrySet()) {
            Map<Long, Integer> chosen = choices.get(entry.getKey());
            if (chosen == null || chosen.size() != 1) {
                continue; // mai scelta, oppure scelte diverse: nessun suggerimento
            }
            Map.Entry<Long, Integer> only = chosen.entrySet().iterator().next();
            if (dismissals.contains(new Dismissal(entry.getKey(), only.getKey()))) {
                continue;
            }
            //prende il movimento senza categoria (Candidate) con data più recente
            Candidate latest = entry.getValue().stream().max(Comparator.comparing(Candidate::date)).orElseThrow();
            suggestions.add(new Suggestion(entry.getKey(), MerchantKey.displayName(latest.text()), only.getKey(),
                    categoryNames.get(only.getKey()), entry.getValue().size(), only.getValue()));
        }
        // Prima gli esercenti con più movimenti: accettandoli si sistema di più con un clic.
        suggestions.sort(Comparator.comparingInt(Suggestion::transactions).reversed()
                .thenComparing(Suggestion::merchantName));
        return suggestions;
    }
}
