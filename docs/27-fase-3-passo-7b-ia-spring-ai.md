# 27. Fase 3, passo 7b: l'intelligenza artificiale con Ollama e Spring AI

**Obiettivo del passo 7b:** un modello di intelligenza artificiale che **suggerisce** la categoria dei
movimenti degli esercenti mai visti (quelli che i suggerimenti "dalla tua storia" del passo 7a non coprono).
Il modello gira **sul tuo computer** con **Ollama**; il backend gli parla con **Spring AI**, la libreria di
Spring per l'IA usata anche nelle aziende. Nel passo 7c lo stesso codice userà anche un servizio cloud.

Questa guida è scritta per **rifare tutto con le tue mani**. Ogni passo dice che cosa fare, mostra il codice
vero del progetto, spiega perché, e dice come verificare che funziona prima di andare avanti.

| Parte | Cosa | Stato |
|---|---|---|
| **7b.1** | Ollama a mano; Spring AI nel progetto; IA accesa/spenta; scaricare il modello dall'app | fatta: questa guida |
| 7b.2 | il modello che sceglie la categoria (`ChatClient`, prompt, risposta strutturata) e la misura della precisione | fatta: `docs/28` |
| 7b.3 | i suggerimenti dell'IA nei Movimenti, "Chiedi all'IA", accetta / no | fatta: `docs/29` |

> Prerequisiti: passo 7a (`docs/26`), Docker avviato, e `git pull`. Al primo avvio Flyway applica la **V18**.

**Indice**

- Parte A: i concetti (come usano l'IA le app, che cos'è Ollama, che cos'è Spring AI, tre modi di parlare a Ollama)
- Parte B: Ollama a mano, dal terminale
- Parte C: Spring AI nel progetto, passo per passo
- Parte D: l'IA accesa o spenta (database, servizio, API)
- Parte E: scaricare il modello dall'app (lavoro in background)
- Parte F: la pagina Impostazioni (Angular)
- Parte G: i test
- Parte H: sicurezza
- Parte I: esercizi guidati, con le soluzioni

---

## Parte A: i concetti

### A1. Come usano l'IA le app, anche quelle per smartphone

Un'app come Yazio (fotografi il piatto, ti dice le calorie) **non** fa girare l'IA sul telefono e **non**
parla direttamente con il modello:

```
Telefono (app)  ──foto──▶  Server dell'azienda  ──foto + istruzioni──▶  Modello IA
                ◀─calorie──                    ◀──"pasta al pomodoro, ~350 g"──
```

1. l'app manda la foto al **server** dell'azienda;
2. il server tiene la **chiave segreta** del servizio IA (messa nell'app, chiunque potrebbe estrarla e usarla
   a spese dell'azienda) e chiede al modello di rispondere in un **formato preciso** (JSON);
3. il server usa la risposta con i **suoi** dati: le calorie vengono da un database degli alimenti, non da
   quello che "inventa" il modello.

WalletTracker funziona allo stesso modo: il browser (anche quello del telefono, nella Fase 4) apre la
dashboard, il **backend Spring** fa da server e parla con il modello. La dashboard non sa nemmeno che c'è
un'IA. E il nostro problema è più semplice di quello di Yazio: classificare un testo breve ("MAR GROUP SPA
FOGLIANISE") è molto più facile che riconoscere un piatto in una foto, e basta un modello piccolo.

### A2. Che cos'è Ollama

Ollama è un programma che **scarica e fa girare modelli di IA sul tuo computer**. Ha due facce:

- un **server HTTP** (porta 11434) con un'API JSON: è quella che usa il nostro backend;
- un comando, `ollama`, per usarlo dal terminale (`ollama pull`, `ollama run`, `ollama list`...).

I modelli si scaricano da un registro (`registry.ollama.ai`), come le immagini Docker da Docker Hub, e hanno
nomi come `qwen2.5:3b`: il nome del modello e, dopo i due punti, la variante (qui 3 miliardi di parametri).
Senza variante (`llama3.2`), Ollama usa `:latest`.

Due cose da sapere sulle risorse:

- **mentre lavora**, il modello sta in memoria: un modello piccolo come `qwen2.5:3b` (circa 2 GB scaricato)
  vuole qualche GB di RAM. Funziona anche senza scheda video, solo più lentamente. (Questa guida usa
  `qwen2.5:3b` negli esempi; dopo le misure del passo 7b.2 il modello consigliato è `gemma3:4b`, `docs/28`);
- **quando è inattivo** consuma quasi niente: Ollama carica il modello alla prima richiesta e lo toglie dalla
  memoria dopo qualche minuto senza richieste (di serie 5 minuti; si cambia con il parametro `keep_alive`).

### A3. Che cos'è Spring AI

Spring AI fa per i modelli di IA quello che **Spring Data** fa per i database: un'interfaccia comune e tanti
"adattatori".

| Spring Data | Spring AI |
|---|---|
| `JpaRepository`, query | `ChatClient`, `ChatModel`, prompt |
| driver PostgreSQL, MySQL... | adattatori Ollama, OpenAI, Anthropic, Google... |
| cambi database: cambi driver e configurazione | cambi fornitore: cambi starter e configurazione |

In più dà gli strumenti che servono in ogni progetto con l'IA: le **risposte strutturate** (il modello
risponde con un oggetto Java, non con testo da interpretare), i tentativi automatici in caso di errore, le
metriche, la memoria delle conversazioni. Per un'azienda conta soprattutto la prima riga: il codice non è
legato a un fornitore.

### A4. Tre modi di parlare con Ollama, a confronto

La stessa domanda, "quali modelli hai?", in tre modi:

**1) `curl`, dal terminale** (lo usiamo per capire e per verificare):

```bash
curl http://localhost:11434/api/tags
```
```json
{"models":[]}
```

**2) `RestClient` di Spring, scrivendo noi le classi** (come abbiamo fatto per Enable Banking, `docs/09`):

```java
record OllamaModel(String name) {}
record OllamaTags(List<OllamaModel> models) {}

RestClient ollama = RestClient.create("http://localhost:11434");
List<String> names = ollama.get().uri("/api/tags").retrieve().body(OllamaTags.class)
        .models().stream().map(OllamaModel::name).toList();
```

**3) Spring AI**, che le classi le ha già (`OllamaApi`, creato da Spring):

```java
List<String> names = ollamaApi.listModels().models().stream().map(OllamaApi.Model::name).toList();
```

| | `curl` | `RestClient` (scritto da noi) | Spring AI |
|---|---|---|---|
| per che cosa | provare, capire | integrare un'API qualsiasi | integrare un modello di IA |
| che cosa scrivi | il JSON a mano | le classi della richiesta e della risposta | quasi niente |
| che cosa vedi | tutto, byte per byte | tutto quello che hai scritto | solo quello che la libreria ti mostra |
| cambiare fornitore (Ollama → cloud) | riscrivi tutto | riscrivi le classi e le chiamate | cambi starter e configurazione |
| rischio | — | errori nelle classi, che scrivi tu | dettagli nascosti dalla libreria (Parte E4) |

Per l'elenco dei modelli la differenza è piccola. Diventa grande nel passo 7b.2, quando chiederemo al modello
di **scegliere una categoria**. Ecco un'anteprima con `RestClient`:

```java
// Noi: la richiesta di /api/chat con lo schema JSON della risposta, e la risposta, il cui contenuto è a sua
// volta un testo JSON da leggere una seconda volta.
var request = Map.of("model", "qwen2.5:3b", "stream", false,
        "format", Map.of("type", "object", "properties", Map.of("categoryId", Map.of("type", "integer"))),
        "messages", List.of(Map.of("role", "user", "content", "Di che categoria è CONAD ROMA?")));
var response = ollama.post().uri("/api/chat").body(request).retrieve().body(Map.class);
// ...poi estrarre message.content e leggerlo con Jackson
```

e con Spring AI:

```java
record Answer(Long categoryId) {}

Answer answer = chatClient.prompt()
        .user("Di che categoria è CONAD ROMA?")
        .call()
        .entity(Answer.class, spec -> spec.useProviderStructuredOutput()); // lo schema lo genera dal record
```

Nel 7b.2 (`docs/28`) li abbiamo scritti tutti e due per confronto, poi abbiamo tenuto solo Spring AI. Il
confronto ha mostrato dal vivo l'ultima riga della tabella: un dettaglio che Spring AI nascondeva (`docs/28`,
Parte E).

---

## Parte B: Ollama a mano, dal terminale

Prima di scrivere codice, si prova il servizio a mano: così sai che cosa aspettarti e, quando qualcosa non va,
sai se il problema è nel servizio o nel codice. Tutte le risposte qui sotto sono vere (Ollama 0.40.6).

### B1. Avviarlo

```bash
docker run -d --name ollama -p 127.0.0.1:11434:11434 -v ollama:/root/.ollama ollama/ollama
```

| Pezzo | Significato |
|---|---|
| `-d` | in background ("detached") |
| `--name ollama` | un nome, per i comandi successivi (`docker stop ollama`...) |
| `-p 127.0.0.1:11434:11434` | la porta, **solo** per questo computer: dagli altri dispositivi non si raggiunge |
| `-v ollama:/root/.ollama` | un "volume": i modelli scaricati restano anche se il container viene ricreato |
| `ollama/ollama` | l'immagine ufficiale (è grande, diversi GB: contiene anche le librerie per le schede video) |

Verifica:

```bash
curl http://localhost:11434/
```
```
Ollama is running
```
```bash
curl http://localhost:11434/api/version
```
```json
{"version":"0.40.6"}
```

### B2. Scaricare un modello

Dal terminale:

```bash
docker exec -it ollama ollama pull qwen2.5:3b
docker exec -it ollama ollama list
```

(`docker exec -it ollama ...` esegue il comando `ollama` dentro il container.)

Oppure con l'API, che è quello che farà il nostro backend:

```bash
curl http://localhost:11434/api/pull -d '{"model": "qwen2.5:3b"}'
```

La risposta non è un JSON solo: è uno **stream**, una riga JSON per ogni passo, che arriva man mano:

```json
{"status":"pulling manifest"}
{"status":"downloading","digest":"sha256:...","total":1929903136,"completed":241172480}
...
{"status":"success"}
```

E se qualcosa va storto? Ecco una risposta vera, con internet bloccato:

```json
{"status":"pulling manifest"}
{"error":"pull model manifest: Get \"https://registry.ollama.ai/v2/library/qwen2.5/manifests/3b\": Forbidden"}
```

Attenzione: è comunque una risposta **HTTP 200**. L'errore è un messaggio dello stream come gli altri.
Ricordatelo: nella Parte E4 questo dettaglio ci ha fatto trovare un errore.

### B3. Parlargli

Dal terminale, una domanda al volo:

```bash
docker exec -it ollama ollama run qwen2.5:3b "Di che categoria è una spesa da ESSELUNGA?"
```

Con l'API, `/api/chat`, che è quella che userà Spring AI:

```bash
curl http://localhost:11434/api/chat -d '{
  "model": "qwen2.5:3b",
  "stream": false,
  "messages": [{"role": "user", "content": "Di che categoria è una spesa da ESSELUNGA?"}]
}'
```

- `messages`: la conversazione; `role` è `system` (le istruzioni), `user` (la domanda) o `assistant` (le
  risposte del modello);
- `stream: false`: una risposta sola, alla fine, invece di un pezzo di testo alla volta.

La risposta contiene il testo in `message.content`, insieme a qualche dato sui tempi (secondo la
documentazione di Ollama; nell'ambiente in cui è stata scritta questa guida non si potevano scaricare
modelli, quindi questa risposta non è stata vista dal vivo). Senza il modello, invece, la risposta vera è:

```json
{"error":"model 'qwen2.5:3b' not found"}
```

### B4. Che cosa sta usando la memoria

```bash
curl http://localhost:11434/api/ps
```
```json
{"models":[]}
```

`/api/ps` dice quali modelli sono **caricati in memoria** adesso. Subito dopo una domanda ci sarà il modello;
dopo qualche minuto senza domande, di nuovo `[]`. È la prova che Ollama inattivo non tiene occupata la RAM.

---

## Parte C: Spring AI nel progetto, passo per passo

### C1. Scegliere la versione

Spring AI ha generazioni diverse per versioni diverse di Spring Boot. Noi usiamo Spring Boot **4.1**, quindi
serve Spring AI **2.x** (la 1.x è per Spring Boot 3). Le versioni pubblicate sono su Maven Central:
`https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml`. Abbiamo
scelto **2.0.1**, l'ultima stabile: niente `-M` (milestone) o `-RC` (release candidate) in un progetto vero.

### C2. Le dipendenze nel `pom.xml`

```xml
<properties>
    <java.version>21</java.version>
    <spring-ai.version>2.0.1</spring-ai.version>          <!-- la versione, in un posto solo -->
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    ...
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-ollama</artifactId>   <!-- niente <version>: la dà la BOM -->
    </dependency>
</dependencies>
```

- **BOM** ("bill of materials", distinta base): un elenco di versioni di **tutti** i moduli di Spring AI,
  provate insieme. Con `<scope>import</scope>` dentro `<dependencyManagement>` diciamo a Maven "prendi le
  versioni da lì"; poi, nelle dipendenze, la versione non si scrive. È lo stesso meccanismo con cui Spring
  Boot (il `parent` del nostro pom) ci dà le versioni di Hibernate, Jackson e degli altri;
- **starter**: una dipendenza che porta con sé tutto il necessario (qui il client di Ollama, `ChatClient`,
  la configurazione automatica).

Verifica: Maven scarica e mostra che cosa è arrivato.

```bash
cd backend
./mvnw dependency:tree -Dincludes=org.springframework.ai
```
```
[INFO] \- org.springframework.ai:spring-ai-starter-model-ollama:jar:2.0.1:compile
[INFO]    +- org.springframework.ai:spring-ai-autoconfigure-model-ollama:jar:2.0.1:compile
[INFO]    +- org.springframework.ai:spring-ai-ollama:jar:2.0.1:compile
[INFO]    +- org.springframework.ai:spring-ai-client-chat:jar:2.0.1:compile
...
```

Una cosa da notare: lo starter porta anche `spring-webflux` e `reactor-core`, le librerie "reattive" (le
useremo per lo scaricamento, Parte E). L'app resta una normale applicazione Spring MVC: quando ci sono
entrambe, Spring Boot sceglie MVC.

### C3. La configurazione

In `application.yml`:

```yaml
spring:
  ai:
    ollama:
      base-url: ${OLLAMA_URL:http://localhost:11434}
```

`${OLLAMA_URL:http://localhost:11434}`: la variabile d'ambiente `OLLAMA_URL` se c'è, altrimenti
`localhost`. Con Docker Compose (Fase 4) sarà l'indirizzo del container di Ollama.

Il **modello** qui non lo scriviamo: lo sceglie l'utente dalle Impostazioni (Parte D) e lo passeremo a ogni
richiesta.

### C4. Che cosa crea Spring da solo

La **configurazione automatica** dello starter crea dei *bean* pronti da iniettare:

| Bean | A che cosa serve | Quando lo usiamo |
|---|---|---|
| `OllamaApi` | il client HTTP di Ollama: `listModels()`, `pullModel()`, `chat()`... | 7b.1 (stato, scaricamento) |
| `OllamaChatModel` | il "modello di chat" di Spring AI, sopra `OllamaApi` | 7b.2 |
| `ChatClient.Builder` | per costruire un `ChatClient`, l'interfaccia comoda per i prompt | 7b.2 |

Si usano come qualsiasi bean, con il costruttore:

```java
public AiService(AiSettingsRepository settingsRepository, OllamaApi ollamaApi, ModelDownload modelDownload) {
```

All'avvio Spring AI **non** contatta Ollama, quindi l'app parte anche se Ollama è spento. Verifica: i test
che caricano tutto il contesto (`WalletTrackerApplicationTests`) passano, e l'app si avvia.

---

## Parte D: l'IA accesa o spenta

### D1. Dove sta la scelta

Due informazioni diverse, in due posti diversi:

| Cosa | Dove | Chi la decide |
|---|---|---|
| dove si trova Ollama | `application.yml` (`OLLAMA_URL`) | chi installa |
| se usare l'IA, e con quale modello | tabella `ai_settings`, pagina Impostazioni | l'utente |

### D2. La migrazione V18

```sql
CREATE TABLE ai_settings (
    id           INT          PRIMARY KEY CHECK (id = 1),
    provider     VARCHAR(20)  NOT NULL DEFAULT 'NONE' CHECK (provider IN ('NONE', 'OLLAMA')),
    ollama_model VARCHAR(100) NOT NULL DEFAULT 'qwen2.5:3b',
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO ai_settings (id) VALUES (1);
```

- `CHECK (id = 1)`: **una sola riga** possibile, come per le credenziali di Enable Banking (V7). Una seconda
  riga per sbaglio il database la rifiuta;
- `CHECK (provider IN (...))`: anche se un giorno un bug provasse a scrivere un valore strano, il database
  lo rifiuta. Nel 7c aggiungeremo `'CLOUD'` con una nuova migrazione;
- l'`INSERT` crea subito la riga con i valori predefiniti: l'IA **spenta**. Così il codice non deve mai
  gestire "riga mancante".

### D3. L'entità

```java
@Entity
@Table(name = "ai_settings")
public class AiSettings {

    static final int ID = 1;                     // l'unica riga

    @Id
    private Integer id;                          // niente @GeneratedValue: l'id è sempre 1

    @Enumerated(EnumType.STRING)                 // nel database "OLLAMA", non 1 (vedi docs/05)
    @Column(name = "provider", nullable = false)
    private AiProvider provider;
    ...
    public void change(AiProvider newProvider, String newOllamaModel) { ... }
}
```

con `AiProvider { NONE, OLLAMA }` e un `AiSettingsRepository extends JpaRepository<AiSettings, Integer>`.

### D4. Il servizio: Ollama risponde?

```java
@Transactional(readOnly = true)
public Status status() {
    AiSettings settings = settings();
    List<String> installed = installedModels();          // null = Ollama non risponde
    boolean reachable = installed != null;
    List<String> models = reachable ? installed : List.of();
    return new Status(settings.getProvider(), settings.getOllamaModel(), reachable, models,
            isInstalled(models, settings.getOllamaModel()), modelDownload.state());
}

private List<String> installedModels() {
    try {
        return ollamaApi.listModels().models().stream().map(OllamaApi.Model::name).toList();
    } catch (RuntimeException e) {                       // connessione rifiutata: è spento
        return null;
    }
}

static boolean isInstalled(List<String> installed, String model) {
    String wanted = model.contains(":") ? model : model + ":latest";   // "llama3.2" = "llama3.2:latest"
    return installed.contains(wanted);
}
```

Per sapere se Ollama è acceso non serve un'operazione apposta: gli chiediamo l'elenco dei modelli (lo stesso
`/api/tags` della Parte B). Se risponde è acceso, e intanto sappiamo se il modello scelto c'è.

### D5. Le API

```java
@RestController
@RequestMapping("/api/ai")
public class AiController {

    @GetMapping                                           // lo stato
    public AiService.Status status() { ... }

    @PutMapping                                           // cambia la scelta
    public AiService.Status change(@Valid @RequestBody SettingsChange change) { ... }

    @PostMapping("/model/download")
    @ResponseStatus(HttpStatus.ACCEPTED)                  // 202: "ricevuto, ci sto lavorando"
    public AiService.Status download() { ... }

    public record SettingsChange(
            @NotNull AiProvider provider,
            @NotNull @Pattern(regexp = "[A-Za-z0-9._:/-]{1,100}") String ollamaModel) {
    }
}
```

Il nome del modello è **validato**: solo i caratteri che servono ai nomi di Ollama ("qwen2.5:3b",
"hf.co/utente/modello:Q4_K_M"). Un `"qwen; rm -rf /"` riceve 400.

Verifica, con il backend avviato:

```bash
curl http://localhost:8080/api/ai
```
```json
{"provider":"NONE","ollamaModel":"qwen2.5:3b","ollamaReachable":true,"installedModels":[],"modelInstalled":false,"download":null}
```
```bash
curl -X PUT http://localhost:8080/api/ai -H 'Content-Type: application/json' \
     -d '{"provider": "OLLAMA", "ollamaModel": "qwen2.5:3b"}'
```

---

## Parte E: scaricare il modello dall'app

### E1. Perché in background

Scaricare un modello dura **minuti**. Una richiesta HTTP non può restare aperta tanto (il browser, un proxy o
l'utente la interromperebbero). Lo schema classico:

1. `POST /api/ai/model/download` fa **partire** il lavoro e risponde subito **202 Accepted**;
2. il lavoro continua su un altro thread;
3. la pagina chiede ogni tanto `GET /api/ai` per vedere a che punto è (**polling**).

### E2. Il `Flux`: uno stream di messaggi

Ricordi la Parte B2? `/api/pull` manda una riga JSON per ogni passo. Spring AI la trasforma in un `Flux`:

```java
Flux<ProgressResponse> progress = ollamaApi.pullModel(new PullModelRequest(model));
```

Un `Flux` (dalla libreria Reactor) è **una sequenza di valori che arrivano nel tempo**, come una `List` i cui
elementi non ci sono ancora. Non si legge con un `for`: si dice che cosa fare quando arrivano, con
`subscribe`, che prende tre "callback":

```java
ollamaApi.pullModel(new PullModelRequest(model)).subscribe(
        progress -> state.set(progressOf(model, progress)),     // 1. a ogni messaggio
        error -> state.set(new State(model, false, "errore", null, null, error.getMessage())),  // 2. errore
        () -> state.set(finished(model)));                       // 3. fine
```

`subscribe` **non aspetta**: restituisce subito, e i messaggi arrivano dopo, su un altro thread. È proprio
quello che serve: la richiesta HTTP finisce, lo scaricamento continua.

### E3. Lo stato condiviso tra due thread

```java
private final AtomicReference<State> state = new AtomicReference<>();

public State state() {               // chiamato dal thread delle richieste HTTP (GET /api/ai)
    return state.get();
}
```

Il thread dello scaricamento **scrive** lo stato, quello delle richieste lo **legge**. Con una variabile normale
potresti leggere un valore vecchio, o uno scritto a metà. `AtomicReference` garantisce che chi legge veda
sempre l'ultimo valore completo. Lo stato è un `record` (immutabile): ogni aggiornamento è un oggetto nuovo,
non una modifica.

```java
public synchronized void start(String model) {
    State current = state.get();
    if (current != null && current.running()) {
        return; // ce n'è già uno: non ne facciamo partire un secondo
    }
    ...
}
```

`synchronized`: se l'utente preme "Scarica" due volte di fila, le due richieste arrivano su due thread. Solo
uno alla volta entra nel metodo, quindi il secondo vede lo scaricamento già in corso e non ne avvia un altro.

### E4. Una lezione imparata provando dal vivo

La prima versione, alla fine dello stream, scriveva "success". I test (con un Ollama finto) passavano. Con
Ollama vero e internet bloccato è successo questo:

1. Ollama manda `{"error": "pull model manifest: ... Forbidden"}`: un messaggio **normale** dello stream,
   con HTTP 200 (Parte B2);
2. il record di Spring AI per l'avanzamento, `ProgressResponse(status, digest, total, completed)`, **non ha
   il campo `error`**: quel messaggio diventa un avanzamento vuoto;
3. lo stream finisce senza errori, e la callback 3 scrive "success". Ma il modello non c'è.

I test passavano perché il finto Ollama faceva quello che **pensavamo** facesse Ollama. La correzione: alla
fine non ci fidiamo, e chiediamo a Ollama se il modello adesso c'è.

```java
private State finished(String model) {
    try {
        List<String> installed = ollamaApi.listModels().models().stream().map(OllamaApi.Model::name).toList();
        if (AiService.isInstalled(installed, model)) {
            return new State(model, false, "success", null, null, null);
        }
    } catch (RuntimeException e) { ... }
    return new State(model, false, "errore", null, null, "Ollama ha finito senza scaricare il modello. Di solito "
            + "il nome è sbagliato, oppure Ollama non riesce a raggiungere internet (registry.ollama.ai)");
}
```

Due lezioni, valide ben oltre l'IA:

- **un'astrazione comoda può nascondere dettagli che contano** (è l'ultima riga della tabella della Parte
  A4). Con `RestClient` e le classi scritte da noi, il campo `error` l'avremmo visto;
- **verifica il risultato, non il processo**: "lo stream è finito" non vuol dire "il modello c'è".

C'è un test che riproduce il comportamento vero (`aStreamThatEndsWithoutTheModelIsAFailureNotASuccess`).

### E5. Un secondo difetto, trovato usando l'app

Scaricando `gemma3:4b` dalla pagina, la pagina mostrava insieme "✓ Il modello gemma3:4b è scaricato" e, in
rosso, "Lo scaricamento non è riuscito: Ollama ha finito senza scaricare il modello". Due messaggi
contraddittori, e veri tutti e due, perché vengono da due momenti diversi:

- "✓ scaricato" è **adesso**: a ogni apertura della pagina il backend chiede a Ollama i modelli;
- il messaggio rosso è lo **stato salvato** alla fine dello scaricamento, quando `finished()` ha controllato.

Allora perché, alla fine dello scaricamento, il controllo diceva "non c'è"? Per scoprirlo abbiamo stampato i
messaggi dello stream uno per uno: arrivavano tutti, fino a "success". Il problema era dopo, nel log:

```
WARN ModelDownload -- Dopo lo scaricamento di gemma3:1b Ollama non risponde: I/O error on GET request for
"http://127.0.0.1:11434/api/tags": block()/blockFirst()/blockLast() are blocking, which is not supported in
thread reactor-http-epoll-2
```

**Il thread sbagliato.** Spring AI legge gli stream con **Reactor Netty**: pochi thread (`reactor-http-epoll-N`)
servono tutte le connessioni, passando da una all'altra appena arrivano dati. Si chiama **event loop**, e funziona
solo se nessuno lo ferma: un thread che aspetta blocca tutte le altre connessioni. Per questo Reactor **vieta** le
chiamate bloccanti su quei thread, e lancia un'eccezione.

Le callback di `subscribe` (avanzamento, errore, fine) girano proprio lì. E `finished()` chiamava
`ollamaApi.listModels()`, che è bloccante: aspetta la risposta. L'eccezione finiva nel `catch`, e da lì nello
stesso messaggio di "modello non trovato". Risultato: **ogni scaricamento riuscito finiva in errore**.

**La correzione** è una riga:

```java
ollamaApi.pullModel(new PullModelRequest(model))
        .publishOn(Schedulers.boundedElastic())   // da qui in poi, i messaggi arrivano su un altro thread
        .subscribe(...);
```

`publishOn` fa arrivare i messaggi su un altro gruppo di thread. `Schedulers.boundedElastic()` è quello fatto
apposta per il lavoro che aspetta (chiamate HTTP bloccanti, file, database): lì `listModels()` è permesso.

In più:
- il `catch` ora ha un messaggio suo ("Lo scaricamento è finito, ma non sono riuscito a controllare se il modello
  c'è: ..."): un errore diverso deve dire una cosa diversa, altrimenti porta fuori strada (ci ha portati a pensare
  a internet, mentre il problema era un thread);
- la pagina non mostra un errore di scaricamento quando il modello c'è (è uno stato vecchio).

**Perché i test non l'avevano visto?** In `AiControllerTest` `OllamaApi` è finto (`@MockitoBean`): lo stream è un
`Flux.just(...)` e `listModels()` restituisce subito una risposta preparata, senza rete e senza bloccare niente. Il
difetto c'era solo con la rete vera. Il nuovo `ModelDownloadTest` usa un `OllamaApi` **vero** contro un Ollama
finto fatto con il piccolo server HTTP del JDK (`com.sun.net.httpserver.HttpServer`): risponde a `/api/pull` e
`/api/tags` come Ollama, e il test passa per gli stessi thread dell'app. Prima della correzione falliva, dopo passa.

La lezione: **un finto troppo semplice nasconde i problemi di "come" (thread, tempi, rete)**. Per le parti che
dipendono da come lavora una libreria, serve almeno un test con la libreria vera.

### E6. Verifica

```bash
curl -X POST http://localhost:8080/api/ai/model/download     # 202, subito
curl http://localhost:8080/api/ai                            # ripetilo: "download" cambia
```

Lo stato è in memoria: se il backend si riavvia durante lo scaricamento, alla prossima richiesta Ollama
riprende da dove era arrivato.

---

## Parte F: la pagina Impostazioni

Il pannello è un componente a sé, `ai/ai-settings-panel`, messo in fondo alla pagina Impostazioni con
`<app-ai-settings-panel />`.

### F1. Lo stato del componente

```ts
protected readonly status = signal<AiStatus | null>(null);   // quello che dice il backend
protected readonly provider = signal<AiProvider>('NONE');     // quello che l'utente sta scegliendo
protected readonly model = signal('');
```

Due cose separate: lo **stato** (com'è adesso) e il **modulo** (che cosa sta scegliendo l'utente, non
ancora salvato).

### F2. Il polling

```ts
private show(status: AiStatus, form: boolean): void {
  this.status.set(status);
  if (form) {                          // il modulo si aggiorna solo all'apertura e dopo il salvataggio,
    this.provider.set(status.provider); // non durante il polling: cancellerebbe quello che stai scrivendo
    this.model.set(status.ollamaModel);
  }
  clearTimeout(this.timer);
  if (status.download?.running) {
    this.timer = setTimeout(() => this.load(false), POLL_MS);  // tra un secondo, di nuovo
  }
}
```

Ogni risposta decide se serve un'altra richiesta: finché lo scaricamento è in corso, sì; poi il polling si
ferma da solo. E se lasci la pagina?

```ts
constructor() {
  inject(DestroyRef).onDestroy(() => clearTimeout(this.timer));
}
```

`DestroyRef.onDestroy`: quando Angular distrugge il componente, il timer viene cancellato. Senza, la pagina
continuerebbe a chiedere lo stato anche dopo essere stata chiusa.

### F3. La percentuale

```ts
protected readonly percent = computed(() => {
  const download = this.status()?.download;
  if (!download?.running || !download.total || download.completed === null) {
    return null;
  }
  return Math.floor((download.completed / download.total) * 100);
});
```

Un signal **calcolato**: si aggiorna da solo quando cambia `status`. Nel template c'è anche una barra:
`<progress [value]="percent()" max="100">`.

### F4. Il template

Una catena di `@if` per i casi possibili, ognuno con **simbolo + testo** (mai solo il colore):
"✓ Ollama risponde" / "✗ Ollama non risponde" (con il comando `docker run`); "✓ Il modello è scaricato" /
lo scaricamento in corso / "✗ non è scaricato" con il pulsante; e il motivo, se lo scaricamento è fallito.

---

## Parte G: i test

### G1. Backend: un Ollama finto

```java
@MockitoBean
OllamaApi ollamaApi;
```

`@MockitoBean` sostituisce, nel contesto Spring del test, il vero `OllamaApi` con un finto di Mockito: niente
Ollama, niente GB di modello, e la CI resta veloce. Poi decidiamo che cosa risponde:

```java
when(ollamaApi.listModels()).thenThrow(new ResourceAccessException("Connection refused"));  // spento
when(ollamaApi.listModels()).thenReturn(models("llama3.2:latest"));                        // acceso
```

Per lo scaricamento costruiamo un `Flux` a mano, con una pausa tra i messaggi, per vedere lo stato "in corso":

```java
when(ollamaApi.pullModel(any(PullModelRequest.class))).thenReturn(Flux.just(
                new ProgressResponse("pulling manifest", null, null, null),
                new ProgressResponse("downloading", "sha256:abc", 2_000L, 500L),
                new ProgressResponse("success", null, null, null))
        .delayElements(Duration.ofMillis(200)));
```

e il comportamento vero della Parte E4:

```java
Flux.just(new ProgressResponse("pulling manifest", null, null, null),
          new ProgressResponse(null, null, null, null))    // il messaggio {"error"} come lo legge Spring AI
```

| Test (`AiControllerTest`) | Che cosa verifica |
|---|---|
| `byDefaultTheAiIsOff...` | spento di serie; Ollama che non risponde |
| `theUserTurnsOllamaOn...` | accendere; il modello c'è o no (anche `:latest`) |
| `wrongSettingsAre400` | provider inesistente, nome del modello pericoloso, campo mancante |
| `theDownloadRunsInTheBackground...` | 202 subito, "in corso", poi "success" |
| `aStreamThatEndsWithoutTheModel...` | il caso vero: lo stream finisce, ma il modello non c'è → errore |
| `aFailedDownloadTellsWhy` | un errore dello stream arriva all'utente |

### G2. Frontend: i timer finti

Il polling aspetta un secondo tra una richiesta e l'altra: un test vero aspetterebbe secondi. Vitest ha i
**timer finti**:

```ts
vi.useFakeTimers();
...
button(page, 'Scarica il modello').click();
vi.advanceTimersByTime(1000);        // "è passato un secondo": parte il polling
fixture.detectChanges();
expect(page.textContent).toContain('50% (1,0 GB di 2,0 GB)');
...
expect(vi.getTimerCount()).toBe(0);  // alla fine il polling si è fermato davvero
```

e `afterEach(() => vi.useRealTimers())`, così un test che fallisce non lascia i timer finti ai test dopo (ci è
successo: un test fallito ne faceva fallire un altro per timeout).

### G3. La prova dal vivo

Con **Ollama vero** in un container: spento di serie; acceso; "✓ Ollama risponde"; "✗ Il modello non è
scaricato"; "Scarica il modello" con il motivo del fallimento (internet bloccato: così abbiamo trovato il
problema della Parte E4); con Ollama spento, "✗ Ollama non risponde".

---

## Parte H: sicurezza

- l'IA è **spenta di serie**: niente parte senza una scelta dell'utente;
- Ollama ascolta solo su `127.0.0.1`;
- **l'app non avvia Ollama da sola.** Per farlo il backend dovrebbe controllare Docker, cioè in pratica il
  computer intero: un difetto dell'app diventerebbe il controllo della macchina. A ogni parte solo i permessi
  che le servono. Accendere Ollama è una scelta di chi installa (Fase 4: un profilo di Docker Compose,
  `docker compose --profile ia up`);
- il nome del modello è validato (`@Pattern`).

---

## Parte I: esercizi guidati

Prova a farli prima di guardare la soluzione. Dopo ogni esercizio: `./mvnw test` nel backend, `npm test` nel
frontend.

### Esercizio 1: la versione di Ollama, con `RestClient`

`OllamaApi` di Spring AI non ha un metodo per `/api/version`. Aggiungi a `GET /api/ai` un campo
`ollamaVersion` (es. "0.40.6", o null se Ollama non risponde), scrivendo tu la chiamata con `RestClient`.

Suggerimenti: un `record OllamaVersion(String version)`; l'indirizzo da `@Value("${spring.ai.ollama.base-url}")`;
`RestClient.create(baseUrl).get().uri("/api/version").retrieve().body(OllamaVersion.class)`; un `try/catch`
come in `installedModels()`.

<details>
<summary>Soluzione</summary>

```java
// in AiService
record OllamaVersion(String version) {}

private final RestClient ollamaHttp;

public AiService(AiSettingsRepository settingsRepository, OllamaApi ollamaApi, ModelDownload modelDownload,
        @Value("${spring.ai.ollama.base-url}") String ollamaUrl) {
    ...
    this.ollamaHttp = RestClient.create(ollamaUrl);
}

private String ollamaVersion() {
    try {
        return ollamaHttp.get().uri("/api/version").retrieve().body(OllamaVersion.class).version();
    } catch (RuntimeException e) {
        return null;
    }
}
```

e un campo `String ollamaVersion` in più nel record `Status`, da mostrare nel pannello ("✓ Ollama 0.40.6
risponde"). Nei test con `@MockitoBean OllamaApi` la versione sarà null (nessun Ollama vero): è giusto così.
È il confronto della Parte A4 in piccolo: qui le classi le scrivi tu.
</details>

### Esercizio 2: "Elimina il modello"

Un modello occupa GB sul disco. Aggiungi `DELETE /api/ai/model` che elimina da Ollama il modello scelto, e un
link "elimina" accanto a "✓ Il modello è scaricato".

Suggerimenti: Spring AI ha `ollamaApi.deleteModel(new OllamaApi.DeleteModelRequest(model))`; nel test,
`verify(ollamaApi).deleteModel(any())`.

<details>
<summary>Soluzione</summary>

```java
// AiService
@Transactional(readOnly = true)
public Status deleteModel() {
    ollamaApi.deleteModel(new OllamaApi.DeleteModelRequest(settings().getOllamaModel()));
    return status();
}

// AiController
@DeleteMapping("/model")
public AiService.Status deleteModel() {
    return aiService.deleteModel();
}
```

Nel frontend: `deleteAiModel()` in `WalletApi` (`this.http.delete<AiStatus>('/api/ai/model')`) e una
conferma prima di eliminare, come per le categorie.
</details>

### Esercizio 3: quanto resta in memoria

Prova la Parte B4 con un modello scaricato: fai una domanda, guarda `/api/ps` subito e dopo 6 minuti. Poi
richiedi `/api/chat` con `"keep_alive": "30s"`: dopo quanto sparisce da `/api/ps`? Perché per un'app come la
nostra, che fa poche domande ogni tanto, un `keep_alive` breve ha senso?
