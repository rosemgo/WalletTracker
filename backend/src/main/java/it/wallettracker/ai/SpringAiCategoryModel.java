package it.wallettracker.ai;

import java.util.Optional;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;

/**
 * La categoria con <b>Spring AI</b>: tutto il lavoro HTTP e JSON lo fa la libreria.
 *
 * <ul>
 *   <li>{@code ChatClient.Builder} lo crea Spring (configurazione automatica dello starter di Ollama);</li>
 *   <li>{@code .options(...)}: il modello scelto dall'utente e la temperatura 0. La temperatura dice quanto il
 *       modello può "variare": a 0 sceglie sempre la risposta più probabile, quindi la stessa domanda dà la
 *       stessa risposta (quello che vogliamo per classificare, non per scrivere poesie);</li>
 *   <li>{@code .entity(Answer.class, spec -> spec.useProviderStructuredOutput())}: Spring AI ricava dal record
 *       lo schema JSON della risposta, lo passa a Ollama (che lo impone al modello) e trasforma il JSON
 *       ricevuto in un oggetto Java.</li>
 * </ul>
 */
@Component
public class SpringAiCategoryModel implements CategoryModel {

    private final ChatClient chatClient;

    public SpringAiCategoryModel(ChatClient.Builder chatClientBuilder) {

        this.chatClient = chatClientBuilder.build();

    }

    @Override
    public Optional<Long> suggest(CategoryQuestion question, String model) {
        CategoryPrompt.Answer answer = chatClient.prompt()
                .options(OllamaChatOptions.builder().model(model).temperature(0.0))
                .system(CategoryPrompt.SYSTEM)
                .user(CategoryPrompt.user(question))
                .call()
                .entity(CategoryPrompt.Answer.class, spec -> spec.useProviderStructuredOutput());
        return CategoryPrompt.validated(answer, question);
    }

    @Override
    public AiClient client() {
        return AiClient.SPRING_AI;
    }
}
