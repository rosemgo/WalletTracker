package it.wallettracker.ai;

import java.util.Optional;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage;
import org.springframework.stereotype.Component;

/**
 * Chiede al modello la categoria di un movimento, con <b>Spring AI</b>: tutto il lavoro HTTP e JSON lo fa la
 * libreria. Il prompt è in {@link CategoryPrompt}. La stessa domanda va a qualunque fornitore (Ollama,
 * Gemini): il {@link ChatClient} giusto lo dà {@link AiChatClients}.
 *
 * <ul>
 *   <li>{@code ChatClient}: per Ollama lo crea Spring all'avvio, per i servizi cloud AiChatClients;</li>
 *   <li>{@code .options(...)}: il modello scelto dall'utente e la temperatura 0. La temperatura dice quanto il
 *       modello può "variare": a 0 sceglie sempre la risposta più probabile, quindi la stessa domanda dà la
 *       stessa risposta (quello che vogliamo per classificare, non per scrivere poesie);</li>
 *   <li>{@code .entity(Answer.class, spec -> spec.useProviderStructuredOutput())}: Spring AI ricava dal record
 *       lo schema JSON della risposta, lo passa a Ollama (che lo impone al modello) e trasforma il JSON
 *       ricevuto in un oggetto Java;</li>
 *   <li>{@code .responseEntity(...)} invece di {@code .entity(...)}: insieme all'oggetto ci dà la risposta completa
 *       ({@link ChatResponse}), con il modello che ha risposto davvero e i token usati (vedi {@link Reply}).</li>
 * </ul>
 */
@Component
public class CategoryModel {

    private final AiChatClients chatClients;

    public CategoryModel(AiChatClients chatClients) {
        this.chatClients = chatClients;
    }

    /**
     * La risposta, con i dettagli che servono a "Prova la connessione".
     *
     * @param categoryId     l'id di una delle categorie della domanda, oppure vuoto se il modello non lo sa
     * @param model          il modello che ha risposto <b>davvero</b>: con un alias come "gemini-flash-latest" è
     *                       Google a scegliere il modello ("gemini-3.8-flash"), e lo dice nella risposta
     *                       ({@code modelVersion}). Null se il fornitore non lo dice
     * @param thinkingTokens i token di "ragionamento" (thinking) che il modello ha usato prima di rispondere: non si
     *                       vedono, ma costano tempo e quota. Solo Gemini li conta; null per gli altri
     */
    public record Reply(Optional<Long> categoryId, String model, Integer thinkingTokens) {
    }

    /**
     * La categoria suggerita: l'id di una delle categorie della domanda, oppure vuoto se il modello non lo sa.
     *
     * @param target il fornitore e il modello (es. Ollama, "gemma3:4b")
     */
    public Optional<Long> suggest(CategoryQuestion question, AiTarget target) {
        return ask(question, target).categoryId();
    }

    /** Come {@link #suggest}, con i dettagli della risposta. */
    public Reply ask(CategoryQuestion question, AiTarget target) {
        return ask(chatClients.client(target.provider()), AiChatClients.options(target), question);
    }

    /** La domanda vera, uguale per tutti i fornitori. Statica: i test la chiamano con un ChatClient loro. */
    static Reply ask(ChatClient chatClient, ChatOptions.Builder<?> options, CategoryQuestion question) {
        ResponseEntity<ChatResponse, CategoryPrompt.Answer> response = chatClient.prompt()
                .options(options)
                .system(CategoryPrompt.SYSTEM)
                .user(CategoryPrompt.user(question))
                .call()
                .responseEntity(CategoryPrompt.Answer.class, spec -> spec.useProviderStructuredOutput());
        ChatResponse chatResponse = response.response();
        String model = chatResponse != null ? chatResponse.getMetadata().getModel() : null;
        Usage usage = chatResponse != null ? chatResponse.getMetadata().getUsage() : null;
        Integer thinking = usage instanceof GoogleGenAiUsage google ? google.getThoughtsTokenCount() : null;
        return new Reply(CategoryPrompt.validated(response.entity(), question),
                model == null || model.isBlank() ? null : model, thinking);
    }
}
