package it.wallettracker.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import it.wallettracker.category.Category;
import it.wallettracker.subscription.MerchantKey;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.TransactionType;

/**
 * Quali esercenti chiedere all'IA, e con quale domanda (Fase 3, passo 7b.3). Funzioni "pure", provate da
 * AiQuestionsTest.
 *
 * <p>Si chiedono solo gli esercenti:
 * <ul>
 *   <li>con almeno un movimento <b>senza categoria</b> (spesa o entrata);</li>
 *   <li>per cui <b>non hai mai scelto</b> una categoria a mano: per quelli ci sono i suggerimenti "dalla tua
 *       storia" (passo 7a), più affidabili di qualunque modello; e se hai scelto categorie diverse (Amazon), non
 *       sarà il modello a decidere;</li>
 *   <li><b>non ancora chiesti</b>: la risposta è salvata (V20), anche quando è "non so".</li>
 * </ul>
 * Prima quelli con più movimenti: accettando il suggerimento si sistema di più con un clic.
 */
public final class AiQuestions {

    /** Quanti esercenti al massimo per ogni "Chiedi all'IA": con ~5 secondi a domanda, un paio di minuti. */
    public static final int MAX_PER_RUN = 20;

    /**
     * Una domanda da fare.
     *
     * @param merchantKey  l'esercente, per salvare la risposta
     * @param question     la domanda per il modello
     * @param transactions quanti movimenti senza categoria ha
     */
    public record Ask(String merchantKey, CategoryQuestion question, int transactions) {
    }

    private AiQuestions() {
    }

    /**
     * @param uncategorized i movimenti senza categoria (spese ed entrate)
     * @param manual        i movimenti con una categoria scelta a mano: dicono quali esercenti saltare, e danno
     *                      gli esempi
     * @param categories    tutte le categorie
     * @param ownerNames    i nomi dei titolari (vedi MerchantKey)
     * @param alreadyAsked  gli esercenti già chiesti
     * @param limit         quante domande al massimo
     */
    public static List<Ask> build(List<BankTransaction> uncategorized, List<BankTransaction> manual,
            List<Category> categories, List<Set<String>> ownerNames, Collection<String> alreadyAsked, int limit) {
        // Gli esercenti che hai già scelto a mano (qualunque categoria): non si chiedono.
        Set<String> chosen = new HashSet<>();
        for (BankTransaction t : manual) {
            String key = MerchantKey.of(t.getCounterparty(), t.getDescription(), ownerNames);
            if (key != null) {
                chosen.add(key);
            }
        }
        Set<String> skip = new HashSet<>(alreadyAsked);
        skip.addAll(chosen);

        // I movimenti senza categoria, per esercente.
        Map<String, List<BankTransaction>> byMerchant = new HashMap<>();
        for (BankTransaction t : uncategorized) {
            if (t.getType() != TransactionType.EXPENSE && t.getType() != TransactionType.INCOME) {
                continue;
            }
            String key = MerchantKey.of(t.getCounterparty(), t.getDescription(), ownerNames);
            if (key != null && !skip.contains(key)) {
                byMerchant.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
            }
        }

        // Gli esempi: le tue scelte più recenti, uguali per tutte le domande (nessuna riguarda questi esercenti).
        List<CategoryQuestion.Example> examples = EvaluationCases.latestChoices(manual, ownerNames).values().stream()
                .limit(EvaluationCases.EXAMPLES)
                .map(t -> new CategoryQuestion.Example(EvaluationCases.merchantForModel(t, ownerNames),
                        t.getManualCategory().getName()))
                .toList();

        return byMerchant.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, List<BankTransaction>> e) -> e.getValue().size())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(limit)
                .map(e -> {
                    // Il movimento più recente dà il testo e il tipo (spesa o entrata).
                    BankTransaction latest = e.getValue().stream()
                            .max(Comparator.comparing(BankTransaction::getBookingDate)).orElseThrow();
                    CategoryQuestion question = new CategoryQuestion(
                            EvaluationCases.merchantForModel(latest, ownerNames),
                            latest.getType() == TransactionType.EXPENSE,
                            EvaluationCases.options(categories, EvaluationCases.kindOf(latest)), examples);
                    return new Ask(e.getKey(), question, e.getValue().size());
                })
                .toList();
    }
}
