package it.wallettracker.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import it.wallettracker.category.Category;
import it.wallettracker.category.CategoryKind;
import it.wallettracker.subscription.MerchantKey;
import it.wallettracker.transaction.BankTransaction;
import it.wallettracker.transaction.TransactionType;

/**
 * Dai movimenti con una categoria scelta a mano ai casi della misura. Funzioni "pure", provate da
 * EvaluationCasesTest.
 *
 * <ul>
 *   <li>un caso per esercente (il movimento più recente): dieci caffè allo stesso bar non sono dieci prove;</li>
 *   <li>solo spese ed entrate, con una categoria del gruppo giusto (una spesa con una categoria di spesa): il
 *       modello sceglie solo tra quelle, quindi altre scelte non le potrebbe mai indovinare;</li>
 *   <li>gli esempi mostrati al modello sono scelte su <b>altri</b> esercenti: se gli mostrassimo la risposta,
 *       la misura barerebbe.</li>
 * </ul>
 */
public final class EvaluationCases {

    /** Quanti esempi mostrare al modello: abbastanza per capire i tuoi gusti, non tanti da allungare il prompt. */
    static final int EXAMPLES = 10;

    private EvaluationCases() {
    }

    /**
     * @param manual     i movimenti con una categoria scelta a mano
     * @param categories tutte le categorie
     * @param ownerNames i nomi dei titolari (vedi MerchantKey: se la controparte è il titolare, l'esercente è
     *                   nella causale; e così il nome del titolare non va al modello)
     * @param limit      quanti casi al massimo
     */
    public static List<ModelEvaluation.Case> build(List<BankTransaction> manual, List<Category> categories,
            List<Set<String>> ownerNames, int limit) {
        List<Map.Entry<String, BankTransaction>> entries = new ArrayList<>(latestChoices(manual, ownerNames).entrySet());
        List<ModelEvaluation.Case> cases = new ArrayList<>();
        for (Map.Entry<String, BankTransaction> entry : entries.subList(0, Math.min(limit, entries.size()))) {
            BankTransaction t = entry.getValue();
            List<CategoryQuestion.Example> examples = entries.stream()
                    .filter(other -> !other.getKey().equals(entry.getKey()))   // mai l'esercente da indovinare
                    .limit(EXAMPLES)
                    .map(other -> new CategoryQuestion.Example(merchantForModel(other.getValue(), ownerNames),
                            other.getValue().getManualCategory().getName()))
                    .toList();
            CategoryQuestion question = new CategoryQuestion(merchantForModel(t, ownerNames),
                    t.getType() == TransactionType.EXPENSE, options(categories, kindOf(t)), examples);
            cases.add(new ModelEvaluation.Case(question, t.getManualCategory().getId(), t.getManualCategory().getName()));
        }
        return cases;
    }

    /**
     * Le tue scelte, una per esercente: il movimento più recente, solo spese ed entrate con una categoria del
     * gruppo giusto (una spesa in una categoria di spesa). In ordine dal più recente. Le usano la misura (come
     * casi ed esempi) e i suggerimenti dell'IA (come esempi, {@link AiQuestions}).
     */
    static Map<String, BankTransaction> latestChoices(List<BankTransaction> manual, List<Set<String>> ownerNames) {
        Map<String, BankTransaction> byMerchant = new LinkedHashMap<>();
        manual.stream()
                .filter(t -> t.getType() == TransactionType.EXPENSE || t.getType() == TransactionType.INCOME)
                .filter(t -> t.getManualCategory().getKind() == kindOf(t)) //filtra solo le transazioni la cui categoria manuale è dello stesso gruppo del movimento: una spesa (EXPENSE) in una categoria di spesa, un'entrata (INCOME) in una categoria di entrata
                .sorted(Comparator.comparing(BankTransaction::getBookingDate).reversed())
                .forEach(t -> {
                    String key = MerchantKey.of(t.getCounterparty(), t.getDescription(), ownerNames);
                    if (key != null) {
                        byMerchant.putIfAbsent(key, t);
                    }
                });
        return byMerchant;
    }

    /** Le categorie tra cui scegliere: quelle del gruppo, nell'ordine dei menu. */
    public static List<CategoryQuestion.Option> options(List<Category> categories, CategoryKind kind) {
        return categories.stream()
                .filter(c -> c.getKind() == kind)
                .sorted(Comparator.comparingInt(Category::getPosition).thenComparing(Category::getName))
                .map(c -> new CategoryQuestion.Option(c.getId(), c.getName()))
                .toList();
    }

    /**
     * Il testo dell'esercente che mandiamo al modello: quello di MerchantKey (mai il nome del titolare), senza
     * le parole che contengono cifre. Così non partono IBAN, numeri di carta, date e codici: al modello servono
     * le parole ("NETFLIX.COM AMSTERDAM"), non i numeri. È lo stesso testo che la dashboard propone come nome.
     */
    static String merchantForModel(BankTransaction t, List<Set<String>> ownerNames) {
        return MerchantKey.displayName(MerchantKey.merchantText(t.getCounterparty(), t.getDescription(), ownerNames));
    }

    static CategoryKind kindOf(BankTransaction t) {
        return t.getType() == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
    }
}
