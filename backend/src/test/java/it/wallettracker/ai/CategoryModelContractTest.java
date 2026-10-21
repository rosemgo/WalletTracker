package it.wallettracker.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Le stesse prove per le due versioni del client di Ollama (Spring AI e RestClient): le sottoclassi dicono solo
 * come costruirlo. Così il confronto è onesto: stessa domanda, stesse richieste HTTP attese, stesse risposte.
 *
 * <p>Ollama qui è finto: {@link MockRestServiceServer} (come in EnableBankingClientTest) intercetta le richieste
 * HTTP, controlla che siano quelle attese e risponde con il JSON che darebbe Ollama. Si vede così cosa parte
 * davvero, anche con Spring AI che lo nasconde.
 */
abstract class CategoryModelContractTest {

    static final String OLLAMA = "http://ollama.test";

    /** Due categorie di spesa, con il loro numero (id). */
    static final CategoryQuestion QUESTION = new CategoryQuestion("ESSELUNGA MILANO", true,
            List.of(new CategoryQuestion.Option(12L, "Spesa alimentare"), new CategoryQuestion.Option(15L, "Ristoranti")),
            List.of(new CategoryQuestion.Example("ROSSI PIZZERIA", "Ristoranti")));

    MockRestServiceServer server;
    CategoryModel model;

    /** La versione da provare, costruita con questo RestClient.Builder (collegato al server finto). */
    abstract CategoryModel create(RestClient.Builder builder);

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        model = create(builder);
    }

    /** La risposta di /api/chat, con il testo del modello in message.content (a sua volta JSON). */
    static String ollamaAnswer(String content) {
        return """
                {"model": "gemma3:4b", "created_at": "2026-10-21T10:00:00Z",
                 "message": {"role": "assistant", "content": %s},
                 "done": true, "done_reason": "stop", "prompt_eval_count": 120, "eval_count": 8}
                """.formatted(quoted(content));
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\"", "\\\"") + "\"";
    }

    @Test
    void sendsTheQuestionAndReadsTheCategoryNumber() {
        server.expect(requestTo(OLLAMA + "/api/chat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model").value("gemma3:4b"))
                .andExpect(jsonPath("$.stream").value(false))          // una risposta sola, non a pezzi
                .andExpect(jsonPath("$.options.temperature").value(0.0)) // sempre la risposta più probabile
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value(CategoryPrompt.SYSTEM))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value(containsString("Categories:\n0 I don't know\n12 Spesa alimentare\n15 Ristoranti")))
                .andExpect(jsonPath("$.messages[1].content").value(containsString("- ROSSI PIZZERIA → Ristoranti")))
                .andExpect(jsonPath("$.messages[1].content").value(containsString("Merchant: ESSELUNGA MILANO")))
                // Lo schema della risposta: Ollama lo impone al modello.
                .andExpect(jsonPath("$.format.type").value("object"))
                .andExpect(jsonPath("$.format.properties.categoryId.type").value("integer"))
                .andExpect(jsonPath("$.format.required[0]").value("categoryId"))
                .andExpect(jsonPath("$.format.additionalProperties").value(false))
                .andRespond(withSuccess(ollamaAnswer("{\"categoryId\": 12}"), MediaType.APPLICATION_JSON));

        assertThat(model.suggest(QUESTION, "gemma3:4b")).isEqualTo(Optional.of(12L));
        server.verify();
    }

    @Test
    void zeroMeansTheModelDoesNotKnow() {
        server.expect(requestTo(OLLAMA + "/api/chat"))
                .andRespond(withSuccess(ollamaAnswer("{\"categoryId\": 0}"), MediaType.APPLICATION_JSON));

        assertThat(model.suggest(QUESTION, "gemma3:4b")).isEmpty();
    }

    @Test
    void nullAlsoMeansTheModelDoesNotKnow() {
        // Lo schema non lo permette, ma se un modello rispondesse così non deve rompersi nulla.
        server.expect(requestTo(OLLAMA + "/api/chat"))
                .andRespond(withSuccess(ollamaAnswer("{\"categoryId\": null}"), MediaType.APPLICATION_JSON));

        assertThat(model.suggest(QUESTION, "gemma3:4b")).isEmpty();
    }

    @Test
    void aNumberThatIsNotInTheListCountsAsDoesNotKnow() {
        server.expect(requestTo(OLLAMA + "/api/chat"))
                .andRespond(withSuccess(ollamaAnswer("{\"categoryId\": 99}"), MediaType.APPLICATION_JSON));

        assertThat(model.suggest(QUESTION, "gemma3:4b")).isEmpty();
    }

    @Test
    void aMissingModelIsAnError() {
        server.expect(requestTo(OLLAMA + "/api/chat"))
                .andRespond(withResourceNotFound().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\": \"model 'gemma3:4b' not found\"}"));

        assertThatThrownBy(() -> model.suggest(QUESTION, "gemma3:4b")).isInstanceOf(RuntimeException.class);
    }
}
