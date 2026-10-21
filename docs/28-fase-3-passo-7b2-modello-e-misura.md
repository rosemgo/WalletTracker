# 28. Fase 3, passo 7b.2: il modello che sceglie la categoria, in due versioni, e la misura

**Obiettivo:** chiedere a un modello di IA "di che categoria è questo movimento?", con **due versioni** del
client che fanno la stessa cosa, una con **Spring AI** e una con **`RestClient`** scritto a mano, per vederle a
confronto. Poi **misurare** quanto spesso il modello indovina, prima su esercenti di prova e poi, dall'app, sulle
**tue** scelte. La misura serve a scegliere il modello: lo sceglie un numero, non l'intuito.

Continua la guida del passo 7b.1 (`docs/27`): lì Ollama, Spring AI nel progetto, IA accesa/spenta e lo
scaricamento del modello. Qui, nell'ordine in cui le cose sono state fatte davvero:

| Parte | Cosa |
|---|---|
| A | il problema: che cosa entra, che cosa esce, quali dati partono |
| B | la domanda a mano, con `curl` |
| C | il prompt, scritto una volta per tutte e due le versioni |
| D | versione 1: Spring AI |
| E | versione 2: `RestClient` |
| F | il confronto, riga per riga, e che cosa nascondeva Spring AI |
| G | i test: le stesse prove per le due versioni |
| H | la misura: quale modello, quale prompt (tutti i numeri) |
| I | la misura dentro l'app, sulle tue scelte |
| J | sicurezza |
| K | esercizi guidati, con le soluzioni |

> Prerequisiti: `docs/27`, Ollama avviato con un modello scaricato, `git pull`. Al primo avvio Flyway applica la
> **V19** (il modello predefinito diventa `gemma3:4b`, Parte H).

I suggerimenti dell'IA nella pagina Movimenti ("Chiedi all'IA", accetta / no) sono il passo 7b.3.

---

## Parte A: il problema

### A1. Che cosa entra e che cosa esce

Per ogni domanda il modello riceve:

- il **testo dell'esercente**, come lo scrive la banca: `NETFLIX.COM AMSTERDAM`, `MAR GROUP SPA DECO FOGLIANISE ITA`;
- se è una **spesa** o un'**entrata**;
- l'**elenco delle categorie** del gruppo giusto (per una spesa, solo quelle di spesa), ognuna con il suo
  **numero** (l'id del database);
- facoltativi, alcuni **esempi** delle tue scelte: `ESSELUNGA → Spesa alimentare`.

E risponde con **un numero**: la categoria scelta, oppure `0` = "non lo so".

### A2. Perché un numero e non il nome

Se il modello rispondesse con il nome, potrebbe scriverlo in un altro modo ("spesa alimentare", "Alimentari") o
inventarne uno che non esiste. Con il numero:

1. lo **schema JSON** (Parte B) obbliga il modello a rispondere con un intero;
2. il backend **controlla** che l'intero sia nell'elenco (`CategoryPrompt.validated`): un numero inventato vale
   come "non so".

È la stessa idea di Yazio (`docs/27`, A1): il modello sceglie, ma il dato vero (la categoria) viene dal nostro
database, non dal testo del modello.

### A3. Perché "non so" è importante

Un suggerimento sbagliato costa più di nessun suggerimento: l'utente deve accorgersene e correggerlo. Per
questo il modello deve poter dire "non lo so" per `PAG 0099887766`. Sembra facile: nella Parte H vedremo che
le due strade ovvie **non funzionano** con i modelli piccoli, e quella che funziona.

### A4. Quali dati partono

Solo quelli che servono, anche se il modello gira sul tuo computer (nel passo 7c potrà essere un servizio
cloud, e le abitudini si prendono adesso):

| Parte | Esempio |
|---|---|
| il testo dell'esercente, **senza le parole che contengono cifre** | `PAGAMENTO CARTA DEL NETFLIX.COM` |
| spesa o entrata | `expense` |
| i nomi delle categorie | `Spesa alimentare` |

**Non** partono: importi, date, IBAN, numeri di carta, il nome del titolare, il conto. Il testo è quello di
`MerchantKey.merchantText` (che non usa mai il nome del titolare, `docs/26`), passato a
`MerchantKey.displayName` (che toglie le parole con cifre: date, carte, IBAN, codici). Lo prova un test
(`EvaluationCasesTest.neverSendsTheOwnerNameNorNumbers`).

---

## Parte B: la domanda a mano, con `curl`

Prima del codice, la richiesta vera. È quella che manderanno tutte e due le versioni:

```bash
curl http://localhost:11434/api/chat -d '{
  "model": "gemma3:4b",
  "stream": false,
  "options": {"temperature": 0},
  "format": {
    "type": "object",
    "properties": {"categoryId": {"type": "integer"}},
    "required": ["categoryId"],
    "additionalProperties": false
  },
  "messages": [
    {"role": "system", "content": "You classify transactions of an Italian bank account. ... answer 0: not knowing is better than being wrong."},
    {"role": "user", "content": "Categories:\n0 I don'"'"'t know\n1 Spesa alimentare\n2 Bar e ristoranti\n...\n\nTransaction: expense\nMerchant: ESSELUNGA MILANO"}
  ]
}'
```

Pezzo per pezzo:

| Campo | Che cosa fa |
|---|---|
| `stream: false` | una risposta sola, alla fine (`docs/27`, B3) |
| `options.temperature: 0` | la **temperatura** dice quanto il modello può "variare". A 0 sceglie sempre la parola più probabile: la stessa domanda dà (quasi sempre) la stessa risposta. Per classificare è quello che vogliamo; per scrivere una poesia no |
| `format` | uno **schema JSON**: Ollama costringe il modello a scrivere solo testo che lo rispetta (un oggetto con `categoryId` intero). Non è una preghiera nel prompt: le parole fuori schema il modello non le può proprio scrivere |
| `messages[0]`, `system` | le regole, uguali per ogni domanda (Parte C) |
| `messages[1]`, `user` | la domanda di questo movimento |

La risposta (vera, abbreviata):

```json
{"model":"gemma3:4b","created_at":"...","message":{"role":"assistant","content":"{ \"categoryId\": 1 }"},
 "done":true,"done_reason":"stop","total_duration":4212345678,"prompt_eval_count":231,"eval_count":9}
```

Attenzione a `message.content`: è un **testo** che contiene JSON. Va letto **due volte**: prima la risposta di
Ollama, poi il testo del modello. Con Spring AI la seconda lettura la fa la libreria; con `RestClient` la
scriviamo noi (Parte E).

---

## Parte C: il prompt

`backend/src/main/java/it/wallettracker/ai/CategoryPrompt.java`. Sta in una classe sola, usata da tutte e due
le versioni: per confrontarle devono mandare **esattamente** la stessa domanda.

### C1. La domanda come dati

`CategoryQuestion.java`, un record (dati, niente logica):

```java
public record CategoryQuestion(String merchant, boolean expense, List<Option> categories, List<Example> examples) {
    public record Option(Long id, String name) {}           // una categoria tra cui scegliere
    public record Example(String merchant, String category) {} // una tua scelta, come esempio
}
```

### C2. Le regole, in inglese

```java
public static final String SYSTEM = """
        You classify transactions of an Italian bank account.
        You receive the text of a transaction (the merchant name as the bank writes it, sometimes with city, \
        country and codes) and a list of categories in Italian, each with its number.
        Pick the most suitable category, using your knowledge of Italian and international brands and shops, \
        and answer with its number in categoryId.
        If the text does not tell what it is, or no category fits, answer 0: not knowing is better than \
        being wrong.""";
```

- Perché in **inglese**, in un'app italiana? I modelli piccoli sono addestrati soprattutto su testi inglesi e
  seguono meglio istruzioni inglesi. Non è un'opinione: misurato (Parte H2), con `llama3.2:3b` si passa dal 43%
  al 67% di risposte giuste. Categorie ed esercenti restano in italiano, come sono.
- `"""` è un **text block** di Java: un testo su più righe. La `\` a fine riga unisce la riga alla successiva
  (sono frasi lunghe, spezzate solo per leggerle nel codice).
- "using your knowledge of ... brands": chiediamo esplicitamente di usare quello che il modello sa dei marchi
  (che Esselunga è un supermercato). È il motivo per cui usiamo un modello e non le regole.

### C3. Il messaggio dell'utente

```java
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
```

Il risultato, per una spesa da Esselunga:

```
Choices made so far by the user, as examples:
- ROSSI PIZZERIA → Bar e ristoranti

Categories:
0 I don't know
1 Spesa alimentare
2 Bar e ristoranti
...

Transaction: expense
Merchant: ESSELUNGA MILANO
```

"0 I don't know" è una **voce dell'elenco**: è la soluzione al problema di A3, spiegata nella Parte H3. Gli id
delle categorie li assegna il database partendo da 1, quindi lo 0 non è mai una categoria vera.

### C4. La risposta e il controllo

```java
public record Answer(Long categoryId) {}

public static Optional<Long> validated(Answer answer, CategoryQuestion question) {
    if (answer == null || answer.categoryId() == null) {
        return Optional.empty();
    }
    return question.categories().stream()
            .map(CategoryQuestion.Option::id)
            .filter(answer.categoryId()::equals)
            .findFirst();
}
```

`Optional<Long>`: "forse un id". Vuoto se il modello dice 0, o un numero che non è nell'elenco, o (non dovrebbe
succedere, lo schema lo vieta) null. Chi usa il risultato non deve ricordarsi questi casi: c'è o non c'è.

### C5. Un'interfaccia, due versioni

`CategoryModel.java`:

```java
public interface CategoryModel {
    Optional<Long> suggest(CategoryQuestion question, String model);
    AiClient client(); // SPRING_AI o REST_CLIENT: quale versione sono
}
```

Le due classi delle Parti D ed E sono due `@Component` che implementano questa interfaccia. Chi le usa (la
misura, Parte I) chiede a Spring **tutte** le implementazioni con un `List<CategoryModel>` nel costruttore, e
sceglie quella con il `client()` richiesto.

---

## Parte D: versione 1, Spring AI

`SpringAiCategoryModel.java`, tutto il codice:

```java
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
```

Riga per riga:

1. **`ChatClient.Builder`** nel costruttore: lo crea Spring (configurazione automatica dello starter di Ollama,
   `docs/27` C4), già collegato a `OllamaChatModel` e quindi a `OllamaApi` con l'indirizzo di
   `spring.ai.ollama.base-url`. Noi chiamiamo solo `build()`.
2. **`prompt()`** inizia una domanda. Il resto è una catena di metodi ("fluent API"), come `RestClient`.
3. **`.options(...)`**: il modello scelto dall'utente (cambia a runtime, quindi lo passiamo a ogni domanda) e la
   temperatura. `OllamaChatOptions` è la parte **specifica di Ollama**: con un fornitore cloud ci sarebbero le
   sue opzioni.
4. **`.system(...)` e `.user(...)`**: i due messaggi della Parte C.
5. **`.call()`**: qui parte la richiesta HTTP, e si aspetta la risposta (con `.stream()` arriverebbe a pezzi).
6. **`.entity(Answer.class, spec -> spec.useProviderStructuredOutput())`**: la parte più interessante.
   - Spring AI **ricava dal record** `Answer` uno schema JSON;
   - `useProviderStructuredOutput()` gli dice di passarlo **al fornitore** (per Ollama: nel campo `format`), che
     lo impone al modello. Senza, Spring AI aggiungerebbe in fondo al prompt una frase del tipo "rispondi in
     JSON con questo schema", e il modello potrebbe non ubbidire;
   - legge `message.content` e lo trasforma in un `Answer` con Jackson.

21 righe in tutto, senza commenti. Tutto l'HTTP e il JSON li fa la libreria.

---

## Parte E: versione 2, `RestClient`

`RestClientCategoryModel.java`. La stessa cosa, scritta a mano come per Enable Banking (`docs/09`).

### E1. Le classi della richiesta e della risposta

```java
static final Map<String, Object> ANSWER_SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of("categoryId", Map.of("type", "integer")),
        "required", List.of("categoryId"),
        "additionalProperties", false);

record ChatRequest(String model, List<Message> messages, boolean stream, Object format,
        Map<String, Object> options) {}

record Message(String role, String content) {}

record ChatResponse(Message message) {}
```

- `ANSWER_SCHEMA`: lo schema della Parte B, **scritto da noi**. Con Spring AI lo genera la libreria.
- `ChatRequest`: i campi con i nomi esatti che vuole Ollama. Jackson trasforma il record in JSON usando i nomi
  dei componenti (`model`, `messages`, `stream`...).
- `ChatResponse`: della risposta ci serve solo `message`. Gli altri campi (`done`, `total_duration`...)
  Jackson li ignora (con Jackson 3, quello di Spring Boot 4, i campi sconosciuti non sono un errore di serie).

### E2. Il client, con un tempo massimo

```java
@Autowired
public RestClientCategoryModel(RestClient.Builder builder, JsonMapper jsonMapper,
        @Value("${spring.ai.ollama.base-url}") String ollamaUrl) {
    this(builder.baseUrl(ollamaUrl).requestFactory(requestFactory(Duration.ofMinutes(3))).build(), jsonMapper);
}

/** Per i test: un RestClient già pronto (collegato a un server finto, vedi RestClientCategoryModelTest). */
RestClientCategoryModel(RestClient restClient, JsonMapper jsonMapper) {
    this.restClient = restClient;
    this.jsonMapper = jsonMapper;
}
```

- **Due costruttori.** Spring usa quello con `@Autowired` (con due costruttori deve sapere quale); il test
  usa l'altro (Parte G). Il primo **delega** al secondo con `this(...)`.
- Lo stesso indirizzo di Spring AI (`spring.ai.ollama.base-url`): le due versioni parlano allo stesso Ollama.
- **Il tempo massimo di lettura**, 3 minuti: un modello sulla CPU può metterci decine di secondi, soprattutto alla
  prima domanda (quando si carica in memoria). Senza un limite, un Ollama bloccato bloccherebbe per sempre il
  thread che aspetta. Spring AI ha i suoi tempi, configurabili: qui li decidiamo noi.

### E3. La chiamata

```java
public Optional<Long> suggest(CategoryQuestion question, String model) {
    ChatRequest request = new ChatRequest(model,
            List.of(new Message("system", CategoryPrompt.SYSTEM),
                    new Message("user", CategoryPrompt.user(question))),
            false, ANSWER_SCHEMA, Map.of("temperature", 0));
    ChatResponse response = restClient.post()
            .uri("/api/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()        // un errore HTTP (es. 404 "model not found") diventa un'eccezione
            .body(ChatResponse.class);
    if (response == null || response.message() == null) {
        throw new IllegalStateException("Ollama ha risposto senza un messaggio");
    }
    CategoryPrompt.Answer answer = jsonMapper.readValue(response.message().content(), CategoryPrompt.Answer.class);
    return CategoryPrompt.validated(answer, question);
}
```

1. costruiamo la richiesta della Parte B;
2. `post()...retrieve().body(ChatResponse.class)`: la **prima lettura**, la risposta di Ollama;
3. il controllo che il messaggio ci sia: con Spring AI lo fa la libreria;
4. `jsonMapper.readValue(...)`: la **seconda lettura**, il testo del modello come `Answer`;
5. lo stesso `validated` della versione 1.

---

## Parte F: il confronto

### F1. Fianco a fianco

| | Spring AI | `RestClient` |
|---|---|---|
| righe di codice (senza commenti e import) | 21 | 53 |
| lo schema JSON | generato dal record `Answer` | scritto a mano (`ANSWER_SCHEMA`) |
| le classi della richiesta e della risposta | della libreria (`OllamaApi.ChatRequest`...) | nostre (`ChatRequest`, `Message`, `ChatResponse`) |
| la doppia lettura del JSON | la libreria | noi |
| tempo massimo, tentativi in caso di errore | della libreria, configurabili | noi (tempo massimo sì, tentativi no) |
| passare a un servizio cloud (7c) | cambi starter e opzioni | riscrivi richiesta, risposta, autenticazione |
| che cosa vedi | quello che la libreria ti mostra | tutto quello che hai scritto |
| risposte, nei test | uguali | uguali |
| risposte, dal vivo (gemma3:4b, 49 esercenti) | (misura in corso) | |

### F2. Che cosa nascondeva Spring AI

La prima versione aveva lo schema scritto a mano così: `"categoryId": {"type": ["integer", "null"]}`, cioè
"un intero, **oppure null** per dire non so". Il prompt diceva "answer null". Tutto sembrava a posto, e la
prima misura dal vivo dava le **stesse** risposte con i due client senza esempi (49 su 49). Con gli esempi,
invece, 47 su 49: perché due versioni "uguali" rispondono in modo diverso?

Per scoprirlo, nel test (Parte G) abbiamo stampato il **corpo della richiesta** che parte davvero. Spring AI
mandava:

```json
"format": {"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object",
           "properties": {"categoryId": {"type": "integer", "format": "int64"}},
           "required": ["categoryId"], "additionalProperties": false}
```

Lo schema generato dal record dice "un intero, **obbligatorio**": null **non** è permesso. Con Spring AI il
modello non poteva dire "non so", qualunque cosa chiedesse il prompt. Le due versioni non erano uguali.

È l'ultima riga della tabella di `docs/27`, A4, vista dal vivo: "dettagli nascosti dalla libreria". E la
lezione è sempre la stessa (la stessa di `docs/27`, E4): **guarda che cosa parte davvero**. Un test con un
server finto lo mostra in un attimo.

Si poteva rendere il campo facoltativo anche in Spring AI: con `@Nullable` sul componente del record lo schema
non lo mette più tra gli obbligatori (provato). Ma la misura della Parte H3 ha mostrato che né null né un campo
facoltativo funzionano con questi modelli. La soluzione finale, lo 0 nell'elenco, va bene a tutte e due le
versioni con lo **stesso** schema: quello che Spring AI genera da `Answer`, e che `ANSWER_SCHEMA` ricopia. Le
differenze rimaste (`$schema`, `"format": "int64"`) sono annotazioni che Ollama non usa per i numeri.

### F3. Quale useremo

Spring AI, come avevi deciso, per allinearci a quello che si fa in azienda: nel passo 7c lo stesso codice
userà un servizio cloud cambiando solo configurazione. `RestClientCategoryModel` resta: costa poco, è provata
dagli stessi test, ed è la "versione trasparente" con cui confrontare quando qualcosa non torna. Nella misura
dentro l'app (Parte I) puoi scegliere quale usare.

---

## Parte G: i test, le stesse prove per le due versioni

### G1. Una classe astratta, due sottoclassi

`backend/src/test/java/it/wallettracker/ai/CategoryModelContractTest.java` contiene le prove; le sottoclassi
dicono solo **come costruire** la versione da provare:

```java
abstract class CategoryModelContractTest {
    abstract CategoryModel create(RestClient.Builder builder);

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        model = create(builder);
    }

    @Test
    void sendsTheQuestionAndReadsTheCategoryNumber() { ... }
    @Test
    void zeroMeansTheModelDoesNotKnow() { ... }
    @Test
    void nullAlsoMeansTheModelDoesNotKnow() { ... }
    @Test
    void aNumberThatIsNotInTheListCountsAsDoesNotKnow() { ... }
    @Test
    void aMissingModelIsAnError() { ... }
}
```

JUnit esegue i `@Test` della classe astratta **per ogni sottoclasse**: 5 prove × 2 versioni = 10 test. Si
chiama "contract test": il contratto è l'interfaccia `CategoryModel`, e ogni implementazione lo deve
rispettare. Se un giorno aggiungi una terza versione (un fornitore cloud), scrivi una sottoclasse di 5 righe e
hai già tutte le prove.

### G2. Un Ollama finto

`MockRestServiceServer` (lo stesso di `EnableBankingClientTest`) si attacca a un `RestClient.Builder`: ogni
richiesta fatta con un `RestClient` costruito da quel builder non va in rete, ma al server finto, che
**controlla** che sia quella attesa e risponde con il JSON che decidiamo noi:

```java
server.expect(requestTo(OLLAMA + "/api/chat"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.model").value("gemma3:4b"))
        .andExpect(jsonPath("$.stream").value(false))
        .andExpect(jsonPath("$.options.temperature").value(0.0))
        .andExpect(jsonPath("$.messages[0].content").value(CategoryPrompt.SYSTEM))
        .andExpect(jsonPath("$.messages[1].content")
                .value(containsString("Categories:\n0 I don't know\n12 Spesa alimentare\n15 Ristoranti")))
        .andExpect(jsonPath("$.format.properties.categoryId.type").value("integer"))
        .andExpect(jsonPath("$.format.required[0]").value("categoryId"))
        .andRespond(withSuccess(ollamaAnswer("{\"categoryId\": 12}"), MediaType.APPLICATION_JSON));

assertThat(model.suggest(QUESTION, "gemma3:4b")).isEqualTo(Optional.of(12L));
server.verify(); // e la richiesta attesa è arrivata davvero
```

`jsonPath("$.format.required[0]")` è un percorso nel JSON della richiesta: `$` è la radice, `.format` un campo,
`[0]` il primo elemento di un array.

### G3. Come si collega Spring AI al server finto

Nell'app i pezzi di Spring AI li crea la configurazione automatica. Nel test li montiamo a mano, e così si vede
com'è fatto Spring AI dentro:

```java
class SpringAiCategoryModelTest extends CategoryModelContractTest {
    @Override
    CategoryModel create(RestClient.Builder builder) {
        OllamaApi ollamaApi = OllamaApi.builder().baseUrl(OLLAMA).restClientBuilder(builder).build();
        OllamaChatModel chatModel = OllamaChatModel.builder().ollamaApi(ollamaApi).build();
        return new SpringAiCategoryModel(ChatClient.builder(chatModel));
    }
}
```

Tre strati: `OllamaApi` (l'HTTP, fatto con un `RestClient`: per questo accetta il nostro builder) →
`OllamaChatModel` (il "modello di chat" di Spring AI, uguale per tutti i fornitori) → `ChatClient`
(l'interfaccia comoda). La versione `RestClient`, invece, usa il costruttore per i test della Parte E2:

```java
return new RestClientCategoryModel(builder.baseUrl(OLLAMA).build(), new JsonMapper());
```

### G4. Un test che passa al primo colpo: prova a romperlo

I 10 test sono passati subito. Un test che passa al primo colpo potrebbe anche non controllare niente. Prova:
cambia `.value(0.0)` della temperatura in `.value(0.5)` ed esegui:

```bash
./mvnw test -Dtest='RestClientCategoryModelTest,SpringAiCategoryModelTest'
```
```
JSON path "$.options.temperature" expected:<0.5> but was:<0.0>
```

Falliscono tutti e due: il controllo funziona. Rimetti `0.0`. (È così che abbiamo stampato il corpo della
richiesta della Parte F2: aggiungendo per un momento
`.andExpect(request -> System.out.println(((MockClientHttpRequest) request).getBodyAsString()))`.)

---

## Parte H: la misura, quale modello e quale prompt

### H1. Gli esercenti di prova

`backend/src/test/resources/ai/esercenti-di-prova.csv`: 49 esercenti scritti come li scrivono le banche, con la
categoria giusta. Un **dataset di riferimento** ("golden dataset", in azienda): domande con la risposta nota,
per misurare invece di "provare un paio di esempi e sembra andare".

```
# esercente;S (spesa) o E (entrata);categoria attesa (vuota = il modello dovrebbe dire "non so")
ESSELUNGA MILANO;S;Spesa alimentare
MAR GROUP SPA DECO FOGLIANISE ITA;S;Spesa alimentare
NETFLIX.COM AMSTERDAM;S;Intrattenimento e tempo libero
XYZ 12345 ABC;S;
...
```

Ci sono esercenti facili (Esselunga), difficili (MAR GROUP SPA DECO = un supermercato Decò, ma dal nome non si
capisce), ambigui (ATM MILANO: i trasporti di Milano o un bancomat?) e tre sigle senza senso, per cui la
risposta giusta è "non so".

### H2. La prova, con il codice vero

`CategoryModelLiveTest.java` fa le 49 domande con il codice dell'app (le due versioni, con e senza esempi) e
stampa una tabella. Non parte con gli altri test, che non hanno Ollama: solo se lo chiedi.

```bash
cd backend
OLLAMA_LIVE=true OLLAMA_MODELS=gemma3:4b ./mvnw test -Dtest=CategoryModelLiveTest
```

- `@EnabledIfEnvironmentVariable(named = "OLLAMA_LIVE", matches = "true")`: JUnit esegue la classe solo se la
  variabile d'ambiente vale `true`. Senza, il test risulta "saltato" (anche nella CI);
- `OLLAMA_MODELS`: i modelli da provare, separati da virgole (devono essere scaricati);
- `OLLAMA_CLIENTS`: facoltativo, `SPRING_AI`, `REST_CLIENT` o tutti e due (predefinito).

Gli esempi della prova "con esempi" sono 8 scelte fisse (`COOP ALLEANZA → Spesa alimentare`,
`AUTOGRILL → Bar e ristoranti`...), su esercenti **diversi** da quelli da indovinare.

### H3. Tutte le prove fatte, in ordine

Le prime prove le abbiamo fatte con un piccolo script Python (più veloce da cambiare), poi con il codice Java.
I secondi sono quelli del computer su cui è stata scritta la guida, dove Ollama metteva parte del modello su una
scheda video: su un portatile senza, aspettati tempi più lunghi. Le percentuali, invece, non dipendono dal
computer.

**1. Il primo modello, `qwen2.5:3b`, prompt in italiano.** Il predefinito della V18, scelto prima di misurare.

| variante | giuste | s/domanda |
|---|---|---|
| il modello risponde con il nome, scelto da un elenco fisso (`enum` nello schema) | 51% | 2,0 |
| prima una frase di "ragionamento", poi il nome | 47% | 3,4 |
| testo libero, cercato poi nell'elenco | 41% | 0,9 |
| con il numero della categoria (codice Java) | 49% | 1,4 |
| con il numero e 8 esempi | 41% | 1,3 |

Una risposta su due: troppo poco. E le varianti "furbe" (ragionare prima di rispondere, gli esempi) **non
aiutano**: un modello piccolo si confonde con più testo.

**2. Il prompt in inglese.** Stesso schema (`enum`), istruzioni in inglese:

| modello | italiano | inglese | memoria mentre lavora |
|---|---|---|---|
| `qwen2.5:1.5b` | 14% | 22% | ~1,5 GB |
| `qwen2.5:3b` | 51% | 53% | ~2,5 GB |
| `llama3.2:3b` | 43% | **67%** | ~2,5 GB |
| `gemma3:4b` | 76% | **78%** | ~3 GB |
| `qwen2.5:7b` | — | 73% | ~5 GB |

- `qwen2.5:1.5b` è inutilizzabile;
- `qwen2.5:7b`, il doppio della memoria, **non** è più preciso di `gemma3:4b`: più grande non vuol dire migliore
  per **questo** compito (una spiegazione probabile: Gemma è addestrato su molte lingue, e conosce meglio i
  marchi italiani);
- l'inglese aiuta molto `llama3.2`, poco gli altri. Lo teniamo: non peggiora mai.

**3. "Non so".** In tutte le prove sopra il modello non ha **mai** detto "non so", nemmeno per `QWERTY 998`.
Abbiamo provato, con `gemma3:4b` e il numero della categoria:

| come si dice "non so" | risultato |
|---|---|
| `"categoryId": null`, permesso dallo schema | null non lo usa mai: per le sigle sceglie "Altre spese" |
| campo facoltativo (`{}` = non so), come fa Spring AI con `@Nullable` | risponde `{}` **sempre**, anche per Esselunga |
| una voce `0 I don't know` nell'elenco | "non so" per le sigle; e indovina pure Netflix e l'Agenzia delle Entrate, che prima sbagliava |

Perché? Con lo schema imposto, il modello scrive un carattere alla volta tra quelli permessi. Un `null` non
gli "viene in mente" quando sta scegliendo un numero; un oggetto vuoto, invece, è la strada più corta. Una voce
dell'elenco è una scelta come le altre, e il modello la sa usare. Lezione generale: **con i modelli piccoli,
quello che vuoi deve stare nell'elenco delle scelte**, non in una regola del prompt.

**4. Il risultato finale, con il codice Java** (numero della categoria, 0 = non so, inglese):

_(Tabella in aggiornamento: la misura finale con gemma3:4b e llama3.2:3b è in corso.)_

### H4. La scelta: `gemma3:4b`, e `llama3.2:3b` per chi ha poca memoria

| modello | da scaricare | memoria mentre lavora | per chi |
|---|---|---|---|
| **`gemma3:4b`** | 3,3 GB | circa 3 GB | consigliato: computer con almeno 8 GB di RAM (come il tuo) |
| `llama3.2:3b` | 2,0 GB | circa 2,5 GB | computer con poca memoria |

Con 8 GB di RAM, `gemma3:4b` mentre lavora ne usa circa 3: restano 5 GB per il sistema, il browser, il backend
e PostgreSQL. E quando è inattivo, dopo 5 minuti Ollama lo toglie dalla memoria (`docs/27`, A2). Un utente con
un computer più potente può scegliere un modello più grande (il campo del modello è libero), ma la misura dice
che non serve.

La migrazione **V19** fa di `gemma3:4b` il modello predefinito:

```sql
ALTER TABLE ai_settings ALTER COLUMN ollama_model SET DEFAULT 'gemma3:4b';

UPDATE ai_settings SET ollama_model = 'gemma3:4b', updated_at = now()
WHERE provider = 'NONE' AND ollama_model = 'qwen2.5:3b';
```

- la V18 è già applicata e **non si modifica** (Flyway lo scoprirebbe dal checksum e si fermerebbe): si scrive
  una migrazione nuova;
- l'`UPDATE` cambia la riga solo se è ancora quella iniziale (IA spenta e modello mai toccato). Se hai già
  scelto un modello, la tua scelta resta.

### H5. Gli errori che restano

Gli errori di `gemma3:4b` (dalla tabella stampata dal test) sono di tre tipi:

1. **scelte discutibili**, più che errori: Netflix, Spotify e Steam in "Multimedia ed elettronica" invece di
   "Intrattenimento"; TIM e Fastweb in "Multimedia" invece di "Spese fisse e utenze". Una persona potrebbe
   scegliere come il modello;
2. **difetti del dataset**: `ATM MILANO` può essere un bancomat; con quel testo nemmeno una persona sa
   rispondere;
3. **errori veri**: `MAR GROUP SPA DECO` (un supermercato) in "Shopping".

Per i primi due, gli **esempi delle tue scelte** aiutano: se hai messo Disney+ in "Intrattenimento", il
modello ci mette anche Netflix. Per questo, nella misura dentro l'app e nei suggerimenti (7b.3), al modello
mostriamo sempre le tue scelte su altri esercenti.

---

## Parte I: la misura dentro l'app, sulle tue scelte

I 49 esercenti di prova non sono i tuoi. La misura vera è sui movimenti che hai **già categorizzato a mano**:
si chiede al modello la categoria **senza dirgli la tua scelta**, e si confronta. Si fa da Impostazioni →
"Misura la precisione".

### I1. Dai movimenti ai casi: `EvaluationCases`

`EvaluationCases.build(manual, categories, ownerNames, limit)`, una funzione "pura" (riceve dati, restituisce
dati, niente database): per questo si prova con test semplici, senza Spring (`EvaluationCasesTest`). Le regole:

1. **un caso per esercente**, il movimento più recente: dieci caffè allo stesso bar non sono dieci prove;
2. **solo spese ed entrate**, con una categoria del **gruppo giusto**: il modello sceglie tra le categorie di
   spesa per una spesa, quindi una spesa messa in una categoria di entrata non la potrebbe mai indovinare;
3. **gli esempi sono scelte su altri esercenti**, mai quello da indovinare:

```java
List<CategoryQuestion.Example> examples = entries.stream()
        .filter(other -> !other.getKey().equals(entry.getKey()))   // mai l'esercente da indovinare
        .limit(EXAMPLES)
        .map(other -> new CategoryQuestion.Example(merchantForModel(other.getValue(), ownerNames),
                other.getValue().getManualCategory().getName()))
        .toList();
```

Se tra gli esempi ci fosse `ESSELUNGA → Spesa alimentare` e la domanda fosse su Esselunga, il modello
"copierebbe" la risposta: la misura **barerebbe** e direbbe 100%. In azienda si dice che i dati di prova non
devono "trapelare" negli esempi (*data leakage*). Lo prova
`EvaluationCasesTest.theExamplesNeverContainTheMerchantToGuess`.

### I2. Il lavoro in background: `ModelEvaluation`

20 domande da ~5 secondi = quasi 2 minuti. Come lo scaricamento (`docs/27`, Parte E), la richiesta HTTP fa
partire il lavoro e risponde subito (202); la pagina chiede lo stato ogni secondo.

La differenza con lo scaricamento: là il lavoro lo faceva Ollama e noi ascoltavamo uno stream (`Flux`). Qui il
lavoro è **nostro**, un ciclo di domande, e ci serve un thread su cui farlo girare:

```java
private final ExecutorService executor = Executors.newSingleThreadExecutor();

public synchronized void start(String model, AiClient client, List<Case> cases) {
    State current = state.get();
    if (current != null && current.running()) {
        return;                                   // una misura alla volta
    }
    CategoryModel categoryModel = models.stream().filter(m -> m.client() == client).findFirst().orElseThrow();
    state.set(new State(model, client, true, 0, cases.size(), 0, 0, 0, 0, List.of(), null));
    executor.submit(() -> run(categoryModel, model, client, cases));
}

@PreDestroy
void shutdown() {
    executor.shutdownNow();                       // alla chiusura dell'app, il thread si ferma
}
```

- **`ExecutorService`** è un "gestore di thread" di Java: gli dai un lavoro (`submit`) e lui lo esegue su un suo
  thread. `newSingleThreadExecutor()`: un thread solo, quindi i lavori vanno uno dopo l'altro, mai insieme
  (due misure insieme si rallenterebbero a vicenda sullo stesso Ollama);
- **`synchronized`**: due clic veloci su "Misura" sono due richieste HTTP su due thread di Tomcat; senza,
  potrebbero passare tutte e due il controllo `running()` prima che una delle due cambi lo stato;
- **`@PreDestroy`**: Spring lo chiama quando l'app si chiude. Senza, il thread della misura terrebbe in vita il
  programma;
- lo **stato** è un record immutabile in un `AtomicReference`, come per lo scaricamento (`docs/27`, E3): il
  thread della misura lo **sostituisce** dopo ogni domanda, i thread delle richieste HTTP lo leggono. Mai un
  oggetto mezzo aggiornato.

Il ciclo conta giuste, sbagliate e "non so", calcola il tempo medio e tiene l'elenco degli errori; se il
modello non risponde (Ollama spento a metà, tempo massimo superato) si ferma e lo scrive nello stato.

### I3. L'API

`POST /api/ai/evaluation` con `{"model": "gemma3:4b", "client": "SPRING_AI", "samples": 20}`:

```java
public record EvaluationRequest(
        @NotNull @Pattern(regexp = MODEL_NAME) String model,
        @NotNull AiClient client,
        @Min(1) @Max(100) int samples) {}
```

In `AiService.evaluate`, prima di partire, tre controlli con un messaggio chiaro (409 Conflict: la richiesta è
corretta, ma lo stato attuale non la permette):

- Ollama non risponde → "Ollama non risponde: è avviato?";
- il modello non è scaricato → "Il modello ... non è scaricato.";
- nessuna scelta fatta a mano → "Non c'è niente da misurare: ...".

La misura funziona **anche con l'IA spenta**: serve proprio a decidere se accenderla, e con quale modello. Lo
stato arriva in `GET /api/ai`, nel nuovo campo `evaluation`.

Prova a mano (con il backend avviato e qualche categoria scelta a mano):

```bash
curl -k -X POST https://localhost:8443/api/ai/evaluation -H 'Content-Type: application/json' \
     -d '{"model": "gemma3:4b", "client": "SPRING_AI", "samples": 5}'
curl -k https://localhost:8443/api/ai    # ripetilo: "done" sale fino a 5
```

### I4. La pagina

In Impostazioni, nel riquadro dell'IA:

- la tabella dei **modelli consigliati** (`RECOMMENDED_MODELS` in `ai-settings-panel.ts`), con "Usa questo" che
  scrive il nome nel campo del modello;
- **"Misura la precisione"**, visibile quando Ollama risponde e c'è almeno un modello scaricato: il modello
  (tra quelli scaricati), il client, quanti esercenti, e il pulsante "Misura";
- durante la misura, l'avanzamento ("Misura di gemma3:4b in corso: 12 di 20", con la barra) e la percentuale
  fin lì; alla fine il risultato e la tabella degli errori: esercente, la tua scelta, il modello.

Il polling è lo stesso dello scaricamento, con una condizione in più:

```ts
if (status.download?.running || status.evaluation?.running) {
  this.timer = setTimeout(() => this.load(false), POLL_MS);
}
```

### I5. I test

- `EvaluationCasesTest` (6 test, senza Spring): un caso per esercente, mai la risposta negli esempi, i gruppi
  giusti, che cosa si salta, niente nome del titolare né numeri, il limite;
- `AiControllerTest` (4 test in più): i due client sono `@MockitoBean` (finti); il test controlla risultato,
  errori, che le domande non contengano mai la risposta tra gli esempi (`ArgumentCaptor` "cattura" gli argomenti
  con cui è stato chiamato il finto) e che sia stato usato il client scelto e non l'altro; i tre 409; i 400;
- `ai-settings-panel.spec.ts` (4 test in più): i modelli consigliati; la misura con il polling e i timer finti
  (`docs/27`, G2); l'errore se non parte; niente misura senza modelli scaricati.

---

## Parte J: sicurezza

| Rischio | Difesa |
|---|---|
| dati personali al modello | solo testo dell'esercente senza cifre, spesa/entrata, nomi delle categorie (A4) |
| il modello "inventa" una categoria | risponde con un numero imposto dallo schema, controllato dal backend (`validated`) |
| un suggerimento sbagliato preso per buono | il modello suggerisce soltanto (7b.3); la misura mostra quanto sbaglia, errori compresi |
| un nome di modello costruito per fare danni | `@Pattern` anche sulla richiesta della misura |
| richieste enormi o infinite | al massimo 100 esercenti; una misura alla volta; tempo massimo di 3 minuti a domanda con `RestClient` |
| una misura che tiene il computer occupato | parte solo con un clic; Ollama toglie il modello dalla memoria dopo 5 minuti |

---

## Parte K: esercizi guidati

### Esercizio 1: scopri lo schema da solo

Senza guardare la Parte F2: nel test `sendsTheQuestionAndReadsTheCategoryNumber` aggiungi la riga che stampa il
corpo della richiesta (G4), esegui solo `SpringAiCategoryModelTest`, e trova nel corpo lo schema generato da
Spring AI. Poi aggiungi `@Nullable` (di `org.jspecify.annotations`) al componente di `Answer`: che cosa cambia
nello schema?

<details>
<summary>Soluzione</summary>

Senza `@Nullable`: `"required": ["categoryId"]`. Con `@Nullable`: `required` sparisce, il campo diventa
facoltativo (il tipo resta `integer`). Il test `sendsTheQuestion...` allora fallisce su
`$.format.required[0]`: è il test che ti avvisa che il contratto è cambiato. Togli `@Nullable`.
</details>

### Esercizio 2: misura un modello in più

Scarica `qwen2.5:3b` dalla pagina Impostazioni (scrivi il nome, Salva, "Scarica il modello") e misuralo con
"Misura la precisione" sulle tue scelte, poi con `gemma3:4b`. I numeri sono simili a quelli della Parte H3? Se
no, perché potrebbero essere diversi?

<details>
<summary>Soluzione</summary>

Possono essere diversi perché i tuoi esercenti e le tue categorie non sono quelli di prova: se usi spesso
categorie "personali" (es. "Regali" per gli acquisti su Amazon), il modello indovina meno. Ed è proprio per
questo che la misura sulle tue scelte esiste. Il confronto tra i due modelli, invece, di solito resta: `gemma3:4b`
sopra.
</details>

### Esercizio 3: il caso "non so" nella misura

Nella misura dentro l'app non ci sono casi in cui la risposta giusta è "non so" (le tue scelte sono tutte
categorie). Un "non so" conta come errore? Guarda `ModelEvaluation.run` e la percentuale nella pagina.
Ti sembra giusto?

<details>
<summary>Soluzione</summary>

`unsure` è contato a parte, e la percentuale è `right / done`: un "non so" abbassa la percentuale come un
errore, ma non finisce tra gli errori mostrati. Per i suggerimenti (7b.3) però le due cose pesano diversamente:
un "non so" non fa niente, un errore ti fa perdere tempo. Un indicatore migliore potrebbe essere anche la
"precisione sulle risposte date", `right / (right + wrong)`: prova ad aggiungerla alla pagina (in `precision`
di `ai-settings-panel.ts`).
</details>
