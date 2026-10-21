package it.wallettracker.ai;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Il prompt, cioè le parole che mandiamo al modello. Sta in una classe sola, usata da tutte e due le versioni
 * ({@link SpringAiCategoryModel} e {@link RestClientCategoryModel}): per confrontarle, devono mandare
 * esattamente la stessa domanda.
 *
 * <p>Un prompt ha due parti:
 * <ul>
 *   <li>il messaggio di <b>sistema</b>: il "ruolo" e le regole, uguali per ogni domanda;</li>
 *   <li>il messaggio dell'<b>utente</b>: la domanda vera, con i dati di questo movimento.</li>
 * </ul>
 * Le categorie hanno un numero (il loro id): il modello risponde con il numero, non con il nome, così non
 * può "inventare" un nome che non esiste o scriverlo in modo diverso. E controlliamo comunque che il numero
 * sia nell'elenco ({@link #validated}).
 *
 * <p>"Non so" è una voce dell'elenco, la numero 0. Le strade più ovvie non funzionano con i modelli piccoli
 * (misurato con gemma3:4b, docs/27): se lo schema permette {@code null}, il modello non lo usa mai; se il campo
 * è facoltativo, risponde sempre {@code {}}, anche per Esselunga. Una voce in più nell'elenco invece la capisce.
 * Gli id delle categorie partono da 1 (li assegna il database), quindi lo 0 non è mai una categoria vera.
 */
public final class CategoryPrompt {

    /**
     * Le regole. In inglese, anche se l'app è in italiano: i modelli piccoli sono addestrati soprattutto in
     * inglese e seguono meglio istruzioni inglesi (misurato: con llama3.2:3b dal 43% al 67% di risposte giuste,
     * docs/27). Categorie ed esercenti restano in italiano, come sono. "Meglio non sapere che sbagliare": un
     * suggerimento sbagliato costa più di nessun suggerimento.
     */
    public static final String SYSTEM = """
            You classify transactions of an Italian bank account.
            You receive the text of a transaction (the merchant name as the bank writes it, sometimes with city, \
            country and codes) and a list of categories in Italian, each with its number.
            Pick the most suitable category, using your knowledge of Italian and international brands and shops, \
            and answer with its number in categoryId.
            If the text does not tell what it is, or no category fits, answer 0: not knowing is better than \
            being wrong.""";

    /** Il numero di "non so" nell'elenco delle categorie. */
    public static final long UNSURE = 0;

    /**
     * La risposta che vogliamo dal modello: un oggetto JSON con un solo campo, obbligatorio,
     * {@code {"categoryId": 12}} ({@code 0} = non so). Spring AI ricava lo schema JSON da questo record; nella
     * versione con RestClient lo scriviamo a mano, uguale.
     */
    public record Answer(Long categoryId) {
    }

    private CategoryPrompt() {
    }

    /** Il messaggio dell'utente: gli esempi (se ci sono), le categorie, e il movimento. */
    public static String user(CategoryQuestion question) {
        StringBuilder text = new StringBuilder();
        if (!question.examples().isEmpty()) {
            text.append("Choices made so far by the user, as examples:\n");
            question.examples().forEach(e -> text.append("- ").append(e.merchant()).append(" → ")
                    .append(e.category()).append('\n'));
            text.append('\n');
        }
        text.append("Categories:\n");
        text.append(UNSURE).append(" I don't know\n");
        text.append(question.categories().stream()
                .map(option -> option.id() + " " + option.name())
                .collect(Collectors.joining("\n")));
        text.append("\n\nTransaction: ").append(question.expense() ? "expense" : "income");
        text.append("\nMerchant: ").append(question.merchant().strip());
        return text.toString();
    }

    /**
     * La categoria scelta, solo se è una di quelle dell'elenco. Lo 0 ("non so") e un numero inventato danno
     * entrambi "nessuna categoria"; null non dovrebbe arrivare (lo schema lo vieta), ma se arrivasse vale lo stesso.
     */
    public static Optional<Long> validated(Answer answer, CategoryQuestion question) {
        if (answer == null || answer.categoryId() == null) {
            return Optional.empty();
        }
        return question.categories().stream()
                .map(CategoryQuestion.Option::id)
                .filter(answer.categoryId()::equals)
                .findFirst();
    }
}
