package it.wallettracker.ai;

import java.util.Optional;

/**
 * Un modello di IA che sceglie la categoria di un esercente. Due implementazioni, da confrontare:
 * <ul>
 *   <li>{@link SpringAiCategoryModel}: con Spring AI ({@code ChatClient});</li>
 *   <li>{@link RestClientCategoryModel}: con RestClient, scrivendo noi la richiesta HTTP a Ollama.</li>
 * </ul>
 */
public interface CategoryModel {

    /**
     * @param model il modello di Ollama da usare (es. "qwen2.5:3b")
     * @return la categoria scelta, o vuoto se il modello non lo sa (o risponde con un numero non in elenco)
     * @throws RuntimeException se Ollama non risponde o risponde con un errore
     */
    Optional<Long> suggest(CategoryQuestion question, String model);

    /** Quale delle due: il valore della proprietà wallettracker.ai.client. */
    AiClient client();
}
