package it.wallettracker.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.web.client.RestClient;

/**
 * Le prove di {@link CategoryModelContractTest} sulla versione con Spring AI. Nell'app i tre pezzi li crea la
 * configurazione automatica; qui li montiamo a mano, con l'OllamaApi collegato al server finto:
 * OllamaApi (l'HTTP) → OllamaChatModel (il "modello" di Spring AI) → ChatClient (l'interfaccia comoda).
 */
class SpringAiCategoryModelTest extends CategoryModelContractTest {

    @Override
    CategoryModel create(RestClient.Builder builder) {
        OllamaApi ollamaApi = OllamaApi.builder().baseUrl(OLLAMA).restClientBuilder(builder).build();
        OllamaChatModel chatModel = OllamaChatModel.builder().ollamaApi(ollamaApi).build();
        return new SpringAiCategoryModel(ChatClient.builder(chatModel));
    }
}
