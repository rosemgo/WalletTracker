package it.wallettracker.ai;

import java.util.Optional;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;

/**
 * Chiede al modello la categoria di un movimento, con <b>Spring AI</b>: tutto il lavoro HTTP e JSON lo fa la
 * libreria. Il prompt è in {@link CategoryPrompt}. La stessa domanda va a qualunque fornitore (Ollama, Mistral,
 * Gemini): il {@link ChatClient} giusto lo dà {@link AiChatClients}.
 *
 * <ul>
 *   <li>{@code ChatClient}: per Ollama lo crea Spring all'avvio, per i servizi cloud AiChatClients;</li>
 *   <li>{@code .options(...)}: il modello scelto dall'utente e la temperatura 0. La temperatura dice quanto il
 *       modello può "variare": a 0 sceglie sempre la risposta più probabile, quindi la stessa domanda dà la
 *       stessa risposta (quello che vogliamo per classificare, non per scrivere poesie);</li>
 *   <li>{@code .entity(Answer.class, spec -> spec.useProviderStructuredOutput())}: Spring AI ricava dal record
 *       lo schema JSON della risposta, lo passa a Ollama (che lo impone al modello) e trasforma il JSON
 *       ricevuto in un oggetto Java.</li>
 * </ul>
 */
@Component
public class CategoryModel {

    private final AiChatClients chatClients;

    public CategoryModel(AiChatClients chatClients) {
        this.chatClients = chatClients;
    }

    /**
     * La categoria suggerita: l'id di una delle categorie della domanda, oppure vuoto se il modello non lo sa.
     *
     * @param target il fornitore e il modello (es. Ollama, "gemma3:4b")
     */
    public Optional<Long> suggest(CategoryQuestion question, AiTarget target) {
        return ask(chatClients.client(target.provider()), AiChatClients.options(target), question);
    }

    /** La domanda vera, uguale per tutti i fornitori. Statica: i test la chiamano con un ChatClient loro. */
    static Optional<Long> ask(ChatClient chatClient, ChatOptions.Builder<?> options, CategoryQuestion question) {
        CategoryPrompt.Answer answer = chatClient.prompt()
                .options(options)
                .system(CategoryPrompt.SYSTEM)
                .user(CategoryPrompt.user(question))
                .call()
                .entity(CategoryPrompt.Answer.class, spec -> spec.useProviderStructuredOutput());
        return CategoryPrompt.validated(answer, question);
    }
}
