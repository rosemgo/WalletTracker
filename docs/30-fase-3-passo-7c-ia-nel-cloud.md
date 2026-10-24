# 30. Fase 3, passo 7c: l'IA nel cloud, con Google Gemini

**Obiettivo:** oltre al modello sul tuo computer (Ollama, passi 7b), poter usare un **servizio cloud**: Google
Gemini. La **chiave** del servizio la inserisce l'utente dalla pagina Impostazioni, e viene salvata **cifrata**.
Prima di inviare qualcosa serve il suo **consenso**, uno per servizio. Tutto il resto (la domanda,
"Misura la precisione", "Chiedi all'IA") funziona con qualunque fornitore, senza essere riscritto: è il motivo per
cui abbiamo scelto Spring AI.

Per chi è utile il cloud: chi ha un computer con meno di 8 GB di memoria, o lento (sul tuo PC gemma3:4b in Docker
impiegava 80 secondi a domanda), o chi non vuole installare Ollama.

| Parte | Cosa |
|---|---|
| A | i concetti: locale o cloud, la chiave, il consenso, come fanno le app vere |
| B | i due servizi a mano, con `curl` |
| C | le librerie: perché non gli "starter" |
| D | il database: V22 |
| E | la chiave cifrata |
| F | `AiChatClients`: un ChatClient per ogni fornitore |
| G | la stessa domanda a tutti: `CategoryModel` e `AiTarget` |
| H | quando qualcosa va storto: errori e nuovi tentativi |
| I | il consenso e i controlli: `AiService` |
| J | la pagina: `CloudAiSection` |
| K | i test, e le prove dal vivo |
| L | provalo tu, con le tue chiavi |
| M | sicurezza |
| N | esercizi guidati, con le soluzioni |
| O | aggiornamento: Mistral tolto, solo Gemini (V23) |

> **Aggiornamento: Mistral AI è stato tolto (Parte O).** Il passo 7c è nato con due servizi, Mistral e Gemini, e le
> parti B-K raccontano quella versione: ogni volta che leggi "i due servizi", oggi nel codice c'è solo Gemini. Mistral
> non dà più chiavi API con il piano gratuito, e un servizio cloud (Gemini) più uno locale (Ollama) bastano. La
> versione con Mistral resta nella storia di git (commit `7f2df93`), e aggiungerlo di nuovo è l'Esercizio 1.
>
> Prerequisiti: `docs/27`, `docs/28`, `docs/29`, e `git pull`. Al primo avvio Flyway applica la **V22** e la **V23**.
>
> Prezzi, limiti dei piani gratuiti e regole sull'uso dei dati **cambiano**: in questa guida non li scriviamo come
> se fossero fissi. Leggili sul sito del fornitore prima di creare la chiave.

---

## Parte A: i concetti

### A1. Locale o cloud

| | Ollama (sul tuo computer) | Servizio cloud (Mistral, Gemini) |
|---|---|---|
| dove gira il modello | sul tuo computer | sui server del fornitore |
| che cosa serve | Ollama, un modello scaricato, ~3 GB di memoria libera | una chiave del servizio e internet |
| quanto è veloce | dipende dal tuo computer (5 s o 80 s a domanda) | di solito pochi secondi, non dipende dal tuo computer (lo misuri tu, Parte L) |
| quanto costa | niente (la corrente) | piano gratuito con limiti, o pochi centesimi per centinaia di domande |
| i dati | non escono di casa | escono: il testo degli esercenti va al fornitore |
| quanto è "bravo" | un modello piccolo (gemma3:4b: 76-82% sui nostri esercenti di prova) | modelli più grandi: di solito sbagliano meno |

Nessuna delle due è "la migliore": dipende dal computer e da quanto ti importa che i dati restino a casa. Per
questo l'app le offre tutte e due, e la misura (`docs/28`) ti dice quale va meglio **sui tuoi** movimenti.

### A2. Che cos'è una chiave API

Una **chiave API** è una password per i programmi: un testo lungo (es. `AIzaSyD...` per Google) che il programma
manda con ogni richiesta, per dire "sono io, addebitalo al mio account". Tre conseguenze:

- chi ha la tua chiave **usa il tuo account** (e il tuo credito, se il piano è a pagamento): va trattata come una
  password;
- per questo nel database è **cifrata** (Parte E) e la pagina **non la mostra mai**, nemmeno a te: vedi solo le
  ultime 4 lettere, per riconoscerla;
- se pensi che qualcuno l'abbia vista, la **revochi** dal sito del fornitore e ne crei una nuova.

### A3. I due servizi

- **Mistral AI**: un'azienda francese, quindi un servizio europeo. Ha modelli piccoli ed economici, adatti a un
  compito semplice come il nostro (`mistral-small-latest`).
- **Google Gemini**: il servizio di IA di Google. La chiave si crea in pochi clic con un account Google
  (`aistudio.google.com`). **Attenzione:** con il piano gratuito Google può usare i dati inviati per migliorare i
  suoi prodotti; leggi le condizioni prima di accettare.

Perché due? Perché un'app vera lascia scegliere: ognuno ha già un account da qualche parte, e le condizioni
cambiano. E perché vedere **due** fornitori con lo **stesso** codice mostra che cosa fa Spring AI meglio di ogni
spiegazione.

### A4. Il consenso

Con Ollama i dati non escono dal computer. Con un servizio cloud sì: l'utente lo deve sapere **prima**, e dire di sì.

- **Che cosa parte** (le regole di `docs/28`, Parte A4, non cambiano): il testo dell'esercente **senza le parole con
  cifre**, se è una spesa o un'entrata, i nomi delle categorie e, come esempi, alcune tue scelte su altri esercenti;
- **che cosa non parte**: importi, date, IBAN, numeri di carta, il tuo nome;
- **un consenso per servizio**: dire sì a Mistral non vuol dire dire sì a Google. Il database ricorda **quando** l'hai
  dato (`mistral_consent_at`, `gemini_consent_at`);
- senza consenso il backend **non manda niente**: lo controlla prima di ogni domanda (Parte I), e un test lo prova.

### A5. Come fanno le app "vere"

In `docs/27` (A1) abbiamo visto Yazio: la chiave del servizio IA è **dell'azienda**, sta sul **suo** server, e
l'azienda paga per tutti. WalletTracker è diverso: lo installi tu, non c'è un'azienda che paga. Quindi ogni utente usa
**la sua** chiave: si chiama "bring your own key" (porta la tua chiave), ed è il modello di molti programmi
self-hosted. Le regole restano quelle di Yazio: la chiave sta **solo sul server** (il nostro backend), mai nel
browser, e la dashboard non sa nemmeno parlare con Mistral o Google.

---

## Parte B: i due servizi a mano, con `curl`

Come sempre, prima il servizio a mano. Questi comandi li puoi lanciare con **la tua** chiave (al posto di
`LA_TUA_CHIAVE`); sono le stesse richieste che fa il backend (le abbiamo viste nei test, Parte K).

**Mistral** (la stessa forma di OpenAI: `messages`, e la risposta in `choices[0].message.content`):

```bash
curl https://api.mistral.ai/v1/chat/completions \
  -H "Authorization: Bearer LA_TUA_CHIAVE" -H "Content-Type: application/json" -d '{
  "model": "mistral-small-latest",
  "temperature": 0,
  "response_format": {"type": "json_schema", "json_schema": {"name": "answer", "strict": true,
    "schema": {"type": "object", "properties": {"categoryId": {"type": "integer"}},
               "required": ["categoryId"], "additionalProperties": false}}},
  "messages": [
    {"role": "system", "content": "You classify transactions ... answer with its number in categoryId."},
    {"role": "user", "content": "Categories:\n0 I don'"'"'t know\n1 Spesa alimentare\n2 Bar e ristoranti\n\nTransaction: expense\nMerchant: ESSELUNGA"}
  ]}'
```

- la chiave va nell'header **`Authorization: Bearer ...`** ("portatore": chi porta questa chiave è autorizzato);
- lo schema della risposta va in **`response_format`** (con Ollama era `format`).

**Gemini** (una forma tutta sua):

```bash
curl "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent" \
  -H "x-goog-api-key: LA_TUA_CHIAVE" -H "Content-Type: application/json" -d '{
  "systemInstruction": {"parts": [{"text": "You classify transactions ... answer with its number in categoryId."}]},
  "contents": [{"role": "user", "parts": [{"text": "Categories:\n0 I don'"'"'t know\n1 Spesa alimentare\n...\nMerchant: ESSELUNGA"}]}],
  "generationConfig": {"temperature": 0, "responseMimeType": "application/json",
    "responseJsonSchema": {"type": "object", "properties": {"categoryId": {"type": "integer"}}, "required": ["categoryId"]}}
}'
```

- il modello è **nell'indirizzo** (`models/gemini-flash-latest:generateContent`), non nel corpo;
- la chiave va nell'header **`x-goog-api-key`**;
- i messaggi si chiamano `contents` e hanno `parts`; le istruzioni di sistema stanno a parte (`systemInstruction`);
- la risposta è in `candidates[0].content.parts[0].text`.

Tre servizi (con Ollama), tre forme diverse per la **stessa** domanda. Senza Spring AI dovremmo scrivere e
mantenere tutte e tre. Con Spring AI, nel nostro codice c'è una domanda sola (Parte G).

Una risposta **vera** di Google, con una chiave inventata (l'abbiamo vista provando, Parte K):

```json
{"error": {"code": 400, "message": "API key not valid. Please pass a valid API key.",
  "status": "INVALID_ARGUMENT", "details": [{"@type": "type.googleapis.com/google.rpc.ErrorInfo",
  "reason": "API_KEY_INVALID", "domain": "googleapis.com"}]}}
```

Nota il **400**, non 401: ci torneremo nella Parte H.

---

## Parte C: le librerie, e perché non gli "starter"

Per Ollama abbiamo usato lo **starter** (`spring-ai-starter-model-ollama`, `docs/27` C2): una dipendenza che
porta la libreria **e** la configurazione automatica. All'avvio Spring crea da solo `OllamaApi`, `OllamaChatModel`
e `ChatClient.Builder`, leggendo l'indirizzo da `application.yml`.

Per Mistral e Gemini non va bene, per due motivi:

1. **la chiave non c'è all'avvio.** Gli starter la vogliono nella configurazione
   (`spring.ai.mistralai.api-key=...`): è pensata per un'azienda che ha **una** chiave. Da noi la chiave la inserisce
   l'utente, dopo, dalla pagina; e sta nel database, cifrata. All'avvio non c'è niente da configurare;
2. **più modelli insieme confondono la configurazione automatica.** Con tre starter ci sarebbero tre `ChatModel`, e
   Spring non saprebbe quale mettere nel `ChatClient.Builder` che usiamo per Ollama.

Quindi usiamo le **librerie semplici**, senza configurazione automatica, e costruiamo noi i modelli quando servono
(Parte F). Nel `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-mistral-ai</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-google-genai</artifactId>
</dependency>
```

Senza versione: la decide il BOM di Spring AI (`docs/27` C2), così tutte le librerie di Spring AI vanno d'accordo.

Le due librerie sono fatte in modo diverso, e lo vedremo:
- **Mistral**: Spring AI fa le chiamate HTTP da sé (`MistralAiApi`, con `RestClient`, come `OllamaApi`);
- **Gemini**: Spring AI usa la **libreria ufficiale di Google** (`com.google.genai.Client`), che ha le sue regole
  (ad esempio riprova da sola dopo un errore, Parte H).

---

## Parte D: il database, V22

`backend/src/main/resources/db/migration/V22__ia_nel_cloud.sql`:

```sql
ALTER TABLE ai_settings DROP CONSTRAINT ai_settings_provider_check;
ALTER TABLE ai_settings ADD CONSTRAINT ai_settings_provider_check
    CHECK (provider IN ('NONE', 'OLLAMA', 'MISTRAL', 'GEMINI'));

ALTER TABLE ai_settings ADD COLUMN mistral_api_key    TEXT;
ALTER TABLE ai_settings ADD COLUMN mistral_model      VARCHAR(100) NOT NULL DEFAULT 'mistral-small-latest';
ALTER TABLE ai_settings ADD COLUMN mistral_consent_at TIMESTAMPTZ;
ALTER TABLE ai_settings ADD COLUMN gemini_api_key     TEXT;
ALTER TABLE ai_settings ADD COLUMN gemini_model       VARCHAR(100) NOT NULL DEFAULT 'gemini-flash-latest';
ALTER TABLE ai_settings ADD COLUMN gemini_consent_at  TIMESTAMPTZ;

ALTER TABLE ai_settings ADD CONSTRAINT ck_ai_settings_cloud_ready CHECK (
    (provider <> 'MISTRAL' OR (mistral_api_key IS NOT NULL AND mistral_consent_at IS NOT NULL))
    AND (provider <> 'GEMINI' OR (gemini_api_key IS NOT NULL AND gemini_consent_at IS NOT NULL))
);
```

Riga per riga:

- **il vincolo vecchio va sostituito.** In V18 avevamo scritto `CHECK (provider IN ('NONE', 'OLLAMA'))` senza dargli
  un nome: PostgreSQL gliene dà uno da solo, `<tabella>_<colonna>_check`, cioè `ai_settings_provider_check`. V18
  non si modifica (è già applicata, Flyway ne controlla il checksum): una migrazione nuova toglie il vincolo e ne
  mette uno con i valori nuovi. Lezione: **dai sempre un nome ai vincoli** (`CONSTRAINT nome CHECK ...`), così chi
  viene dopo non deve indovinarlo;
- **le chiavi sono `TEXT`**: conterranno il valore cifrato ("v1:...", Parte E), più lungo della chiave;
- **i modelli hanno un valore predefinito**: `mistral-small-latest` e `gemini-flash-latest`. Sono "alias" che il
  fornitore aggiorna da solo all'ultima versione, così non diventano vecchi. Dalla pagina si possono cambiare;
- **`ck_ai_settings_cloud_ready`**: un servizio cloud si può scegliere solo con chiave **e** consenso. Lo controlla
  il codice (Parte I), e il database lo ripete. Si legge così: "il fornitore non è MISTRAL, oppure ci sono la chiave
  e il consenso di Mistral". È la stessa forma "condizionale" del vincolo degli abbonamenti (`docs/22`).

L'entità `AiSettings` ha i campi nuovi e qualche metodo con un nome che dice che cosa fa: `choose(provider)`,
`consent(provider, quando)`, `apiKey(provider, cifrata)`, `encryptedApiKey(provider)`, `hasConsent(provider)`,
`model(provider)`, `target()`. Un dettaglio: `apiKey(provider, null)` (togliere la chiave) **spegne l'IA** se quel
servizio era quello scelto. Altrimenti il database rifiuterebbe la riga (vincolo qui sopra), e giustamente: senza
chiave non funzionerebbe.

---

## Parte E: la chiave cifrata

### E1. La stessa cifratura di Enable Banking

La chiave privata di Enable Banking è già salvata cifrata (`docs/17`, `SecretCipher`): AES-256-GCM, con la chiave di
cifratura in un file **fuori** dal database (`.data/encryption.key`). Chi ruba solo il database (o un backup) vede
testo illeggibile. Riusiamo la stessa classe:

```java
// AiService.saveApiKey
settings().apiKey(cloud, cipher.encrypt(apiKey.trim(), AiChatClients.keyPurpose(cloud)));
```

```java
static String keyPurpose(AiProvider cloud) {
    return "ai." + cloud.name().toLowerCase() + ".api_key";     // "ai.mistral.api_key", "ai.gemini.api_key"
}
```

L'**etichetta** (`purpose`, il "dato associato" di GCM, `docs/17`) è diversa per ogni servizio: un valore cifrato
come chiave di Mistral non si può copiare nella colonna di Gemini e usare lì. La decifratura fallirebbe.

### E2. Una chiave non torna mai indietro

La risposta di `GET /api/ai` per un servizio cloud è:

```java
public record CloudStatus(String model, boolean keySet, String keyHint, boolean consent) {}
```

Niente chiave: solo **se** è salvata e le ultime 4 lettere (`"…XYZ9"`). Per calcolarle il backend decifra la chiave
e ne prende la fine. Se la chiave di cifratura è cambiata (hai perso `.data/encryption.key`), la decifratura fallisce
e il suggerimento diventa "illeggibile: inseriscila di nuovo".

### E3. Che cosa si accetta come chiave

```java
public record KeyChange(@NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{10,300}") String apiKey) {}
```

Le chiavi di Mistral e Google sono fatte di lettere, cifre, `-` e `_`. Accettiamo solo quelle: niente spazi
(succede, copiando male), niente caratteri strani. Una chiave non valida riceve 400, e la pagina dice "Questa non
sembra una chiave: controlla di averla copiata intera".

Le API delle chiavi:

| | |
|---|---|
| `PUT /api/ai/keys/{provider}` | `{"apiKey": "..."}`: salva (cifrata) |
| `DELETE /api/ai/keys/{provider}` | la toglie (e spegne l'IA se era quel servizio) |

`{provider}` diventa direttamente un `AiProvider` (`@PathVariable AiProvider provider`): Spring converte "MISTRAL"
nel valore dell'enum, e un valore sconosciuto ("BOH") riceve 400. `OLLAMA` riceve 400 dal servizio: non ha chiave.

---

## Parte F: `AiChatClients`, un ChatClient per ogni fornitore

`backend/src/main/java/it/wallettracker/ai/AiChatClients.java` è il cuore del passo. Il suo compito: dato un
fornitore, dare il `ChatClient` giusto.

### F1. Ollama: quello di prima

Per Ollama non cambia niente: il `ChatClient.Builder` lo crea Spring all'avvio (lo starter), e lo usiamo così com'è.

### F2. Mistral e Gemini: costruiti quando servono

```java
public ChatClient client(AiProvider provider) {
    if (provider == AiProvider.OLLAMA) {
        return ollama;
    }
    ...
    String encryptedKey = settingsRepository.findById(AiSettings.ID).orElseThrow().encryptedApiKey(provider);
    ...
    Built current = built.get(provider);
    if (current == null || !current.encryptedKey().equals(encryptedKey)) {
        // La prima volta, o la chiave è cambiata: costruiamo il modello con la chiave decifrata.
        String apiKey = cipher.decrypt(encryptedKey, keyPurpose(provider));
        current = new Built(encryptedKey, ChatClient.builder(chatModel(provider, apiKey)).build());
        built.put(provider, current);
    }
    return current.client();
}
```

- **costruito la prima volta che serve**, non all'avvio: all'avvio la chiave può non esserci;
- **tenuto da parte** (`built`, una `ConcurrentHashMap`: una mappa che più thread possono usare insieme, perché la
  misura e "Chiedi all'IA" girano su thread loro). Costruire un modello a ogni domanda sarebbe uno spreco;
- **ricostruito se la chiave cambia**: confrontiamo la chiave **cifrata** salvata con quella con cui avevamo costruito.
  Confrontare il valore cifrato evita di decifrare la chiave a ogni domanda. La chiave in chiaro, invece, resta nella
  memoria del programma dentro il modello costruito: deve, per mandarla con ogni richiesta. Quello che la cifratura
  protegge è il database e i suoi backup, non la memoria del backend in funzione.

### F3. I due modelli, costruiti a mano

```java
private ChatModel chatModel(AiProvider provider, String apiKey) {
    return switch (provider) {
        case MISTRAL -> MistralAiChatModel.builder()
                .mistralAiApi(MistralAiApi.builder().baseUrl(mistralBaseUrl).apiKey(apiKey).build())
                .options(MistralAiChatOptions.builder().build())
                .retryTemplate(retry(provider, retryDelay))
                .build();
        case GEMINI -> {
            Client.Builder client = Client.builder().apiKey(apiKey);
            if (!geminiBaseUrl.isBlank()) {
                client.httpOptions(HttpOptions.builder().baseUrl(geminiBaseUrl).build());
            }
            yield GoogleGenAiChatModel.builder()
                    .genAiClient(client.build())
                    .options(GoogleGenAiChatOptions.builder().build())
                    .retryTemplate(retry(provider, retryDelay))
                    .build();
        }
        default -> throw new IllegalArgumentException("Non è un servizio cloud: " + provider);
    };
}
```

È quello che lo starter di Ollama fa da solo, scritto a mano. Gli strati sono gli stessi (li avevamo visti nei test,
`docs/28`): **l'HTTP** (`MistralAiApi`, o il `Client` di Google) → **il modello di Spring AI** (`MistralAiChatModel`,
`GoogleGenAiChatModel`, entrambi un `ChatModel`) → **il `ChatClient`**, uguale per tutti.

- `switch` con `->` e `yield`: lo `switch` di Java moderno **restituisce un valore**. Con `->` e un'espressione basta
  scriverla; con un blocco `{ ... }` il valore si restituisce con `yield`;
- **gli indirizzi** vengono da `application.yml` (`wallettracker.ai.mistral.base-url`, `...gemini.base-url`). Nell'app
  sono quelli veri (per Gemini, vuoto = quello scelto dalla libreria di Google); i test li puntano a un server finto
  (Parte K).

### F4. Le opzioni di ogni domanda

```java
public static ChatOptions.Builder<?> options(AiTarget target) {
    return switch (target.provider()) {
        case OLLAMA -> OllamaChatOptions.builder().model(target.model()).temperature(0.0);
        case MISTRAL -> MistralAiChatOptions.builder().model(target.model()).temperature(0.0);
        case GEMINI -> GoogleGenAiChatOptions.builder().model(target.model()).temperature(0.0);
        case NONE -> throw new IllegalStateException("L'IA è spenta");
    };
}
```

Ogni fornitore ha la sua classe di opzioni (con le sue opzioni particolari), ma tutte sono un
`ChatOptions.Builder`: è quello che vuole `ChatClient.prompt().options(...)`. Modello e temperatura vanno con ogni
domanda: cambiare modello dalla pagina non richiede di ricostruire niente.

`ChatOptions.Builder<?>`: il `?` ("un tipo qualsiasi") dice che il metodo restituisce il builder di **un** tipo di
opzioni, senza dire quale. A chi lo usa basta sapere che è un builder di opzioni.

---

## Parte G: la stessa domanda a tutti, `CategoryModel` e `AiTarget`

### G1. `AiTarget`: a chi fare la domanda

```java
public record AiTarget(AiProvider provider, String model) {
    public static AiTarget ollama(String model) { ... }
    public String label() { ... }     // "gemma3:4b", oppure "Mistral AI · mistral-small-latest"
}
```

Prima la misura e "Chiedi all'IA" ricevevano solo il nome del modello ("gemma3:4b"): bastava, c'era solo Ollama.
Ora serve anche **il fornitore**. Un record di due campi lo dice in un colpo solo, e viaggia intatto dalla pagina
fino a `CategoryModel`. La chiave non c'è: la legge `AiChatClients` quando serve, così non gira per il programma più
del necessario.

### G2. `CategoryModel`: una domanda, qualunque fornitore

```java
public Optional<Long> suggest(CategoryQuestion question, AiTarget target) {
    return ask(chatClients.client(target.provider()), AiChatClients.options(target), question);
}

static Optional<Long> ask(ChatClient chatClient, ChatOptions.Builder<?> options, CategoryQuestion question) {
    CategoryPrompt.Answer answer = chatClient.prompt()
            .options(options)
            .system(CategoryPrompt.SYSTEM)
            .user(CategoryPrompt.user(question))
            .call()
            .entity(CategoryPrompt.Answer.class, spec -> spec.useProviderStructuredOutput());
    return CategoryPrompt.validated(answer, question);
}
```

Confrontala con quella del 7b.2 (`docs/28`, Parte D): **è la stessa**. Cambia solo da dove arrivano il `ChatClient` e
le opzioni. Il prompt, lo schema della risposta, il controllo dell'id: tutto uguale per i tre fornitori.

E `useProviderStructuredOutput()` fa la cosa giusta per ognuno: lo schema JSON generato dal record `Answer` finisce
in `format` per Ollama, in `response_format` per Mistral, in `responseJsonSchema` per Gemini (lo vediamo nelle
richieste vere, Parte K). È esattamente quello che, senza Spring AI, avremmo dovuto scrivere tre volte (Parte B).

`ask` è `static`: i test la chiamano con un `ChatClient` loro, collegato a un servizio finto.

Chi usa `CategoryModel` (la misura, "Chiedi all'IA", la prova della connessione) passa un `AiTarget` invece del
nome del modello: è l'unico cambiamento in `ModelEvaluation` e `AiAnswerJob`.

---

## Parte H: quando qualcosa va storto, errori e nuovi tentativi

### H1. Messaggi che dicono che cosa fare: `AiErrors`

Le due librerie lanciano eccezioni diverse, con messaggi lunghi (a volte tutto il JSON della risposta). Ma il codice
HTTP c'è sempre nel testo, e basta quello:

| Codice | Significato | Messaggio |
|---|---|---|
| 401, 403 | chiave sbagliata o senza permessi | "La chiave di ... non è valida, o non ha i permessi: controllala o creane una nuova..." |
| 404 | modello inesistente | "Il modello non esiste per ...: controlla il nome nelle Impostazioni." |
| 429 | troppe richieste | "... risponde "troppe richieste": hai raggiunto il limite del tuo piano... Riprova più tardi" |
| 500, 503 | problema del servizio | "... ha un problema temporaneo: riprova tra qualche minuto." |
| nessuna rete | | "Non riesco a raggiungere ...: controlla la connessione a internet." |

```java
private static final Pattern HTTP_CODE = Pattern.compile("(?<!\\d)(400|401|403|404|429|500|503)(?!\\d)");
```

`(?<!\d)` e `(?!\d)` vogliono dire "senza una cifra prima" e "senza una cifra dopo": così "429" in "4290 ms" o in un
id "14012" non conta (c'è un test apposta). L'eccezione si guarda insieme alle sue **cause**
(`t = t.getCause()`): spesso l'errore vero è dentro un'eccezione che lo avvolge.

### H2. La sorpresa di Google: 400, non 401

Provando con Google **vero** e una chiave inventata, il messaggio diceva "Google Gemini ha rifiutato la domanda
(richiesta non valida)". Sbagliato: il problema era la chiave. Google, per una chiave sbagliata, risponde **400**
("richiesta non valida", Parte B), con il motivo `API_KEY_INVALID` dentro il messaggio. Ora `AiErrors` cerca prima
quelle parole:

```java
private static final Pattern INVALID_KEY = Pattern.compile("API_KEY_INVALID|API key not valid|Invalid API key",
        Pattern.CASE_INSENSITIVE);
```

e il test `CloudChatTest` usa la risposta **vera** di Google, non una che ci eravamo immaginati. Lezione (la terza
volta, dopo `docs/27` E4 ed E5): **un servizio finto è fatto delle tue ipotesi**. Una prova con il servizio vero, anche
solo con una chiave sbagliata, le mette alla prova.

### H3. Riprovare, ma solo quando serve

Alcuni errori passano da soli: il servizio è sovraccarico, la rete ha un singhiozzo, o (nei piani gratuiti) hai
superato il limite **al minuto**. Per questi conviene aspettare un po' e riprovare. Per altri no: una chiave
sbagliata non diventa giusta riprovando.

Quello che abbiamo scoperto nei test, mandando "troppe richieste" (429) ai due servizi:

| | Che cosa fa la libreria con un 429 |
|---|---|
| Mistral (Spring AI) | **niente**: lo considera un errore definitivo, una sola richiesta |
| Gemini (libreria di Google) | **riprova da sola**, fino a 5 volte, aspettando sempre di più (nei test: mezzo minuto o più) |

Per un piano gratuito, che ha un limite al minuto, il comportamento di Google è quello giusto. Allora per Mistral lo
aggiungiamo noi, e per Gemini no (riprovare anche noi moltiplicherebbe i tentativi: 3 × 5 = 15):

```java
static RetryTemplate retry(AiProvider provider, Duration firstDelay) {
    return new RetryTemplate(RetryPolicy.builder()
            .predicate(e -> e instanceof TransientAiException
                    || (provider == AiProvider.MISTRAL && String.valueOf(e.getMessage()).contains("429")))
            .maxRetries(2)
            .delay(firstDelay)
            .multiplier(2)
            .build());
}
```

- `RetryTemplate` e `RetryPolicy` sono di Spring Framework 7: "esegui questo, e se fallisce riprova secondo queste
  regole". Spring AI le usa dentro i suoi modelli: noi passiamo le nostre con `.retryTemplate(...)`;
- `predicate`: **quali** errori si riprovano. `TransientAiException` è l'eccezione di Spring AI per gli errori
  "passeggeri" (500, 503, rete);
- `maxRetries(2)`, `delay(5 s)`, `multiplier(2)`: due nuovi tentativi, dopo 5 e 10 secondi;
- `firstDelay` è un parametro: nei test vale 10 millisecondi, altrimenti un test aspetterebbe davvero 15 secondi.

Se dopo i tentativi l'errore resta, `AiAnswerJob` si ferma con il messaggio di `AiErrors`. Le risposte già avute
restano salvate (`docs/29`): il prossimo "Chiedi all'IA" riparte dagli esercenti mancanti.

---

## Parte I: il consenso e i controlli, `AiService`

### I1. Scegliere un servizio cloud

```java
public Status change(AiProvider provider, String ollamaModel, String mistralModel, String geminiModel,
        boolean consent) {
    AiSettings settings = settings();
    settings.changeModels(...);
    if (provider.cloud()) {
        if (settings.encryptedApiKey(provider) == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Inserisci prima la chiave di " + provider.label + ".");
        }
        if (!settings.hasConsent(provider) && !consent) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Per usare " + provider.label
                    + " serve il tuo consenso all'invio dei testi degli esercenti.");
        }
        settings.consent(provider, Instant.now());
    }
    settings.choose(provider);
    return status();
}
```

- l'ordine dei controlli è quello in cui l'utente fa le cose: prima la chiave, poi il consenso;
- il consenso arriva con `"consent": true` **la prima volta**; poi resta salvato (con la data) e non serve ridarlo.
  `settings.consent(...)` non cambia la data se c'è già: conta il primo sì;
- 409 (Conflict): la richiesta è giusta, ma lo stato attuale non la permette (`docs/28` H3).

`AiProvider` ha due aggiunte piccole e comode: `label` ("Mistral AI", per i messaggi) e `cloud()` ("i dati escono dal
computer?").

### I2. Prima di ogni domanda: `check`

```java
public void check(AiTarget target) {
    switch (target.provider()) {
        case NONE -> throw ...("I suggerimenti con l'IA sono spenti: accendili in Impostazioni.");
        case OLLAMA -> { /* Ollama risponde? il modello è scaricato? */ }
        case MISTRAL, GEMINI -> { /* la chiave c'è? il consenso c'è? */ }
    }
}
```

Lo usano tutti quelli che fanno domande: la misura, "Chiedi all'IA" (`AiSuggestionService`, che prima aveva i suoi
controlli solo per Ollama) e la prova della connessione. **Un posto solo** per le regole: se domani se ne aggiunge una,
vale ovunque. Il test `withoutConsentNothingIsSentToTheCloud` controlla che, senza consenso, `CategoryModel` non
venga **mai** chiamato (`verifyNoInteractions`).

### I3. "Prova la connessione"

```java
public ConnectionTest testConnection(AiProvider provider) {
    AiTarget target = new AiTarget(provider, settings().model(provider));
    check(target);
    CategoryQuestion question = new CategoryQuestion("ESSELUNGA", true, ...categorie di spesa..., List.of());
    long start = System.nanoTime();
    try {
        Optional<Long> answer = categoryModel.suggest(question, target);
        ...
        return new ConnectionTest(true, name, seconds(start), null);
    } catch (RuntimeException e) {
        return new ConnectionTest(false, null, seconds(start), AiErrors.describe(e, provider));
    }
}
```

Una domanda **vera** e piccola: di che categoria è ESSELUNGA? In un colpo solo dice se la chiave va, se il nome del
modello esiste, quanto tempo ci vuole, e se la risposta ha senso. Un errore non è un errore HTTP della nostra API (è
una risposta normale, con `ok: false` e il motivo): la prova è andata a buon fine, è il servizio che ha detto no.

Il metodo **non** è `@Transactional`: la domanda può durare secondi, e non serve tenere aperta una transazione del
database mentre si aspetta.

---

## Parte J: la pagina, `CloudAiSection`

### J1. Un componente per un servizio, usato due volte

`frontend/src/app/ai/cloud-ai-section.ts`. Tutta la parte di un servizio cloud (chiave, modello, consenso, prova) è un
componente a sé. Il pannello dell'IA lo usa due volte, cambiando solo `provider`:

```html
@if (provider() === 'MISTRAL') {
  <app-cloud-ai-section
    provider="MISTRAL"
    [cloud]="s.mistral"
    [(aiModel)]="mistralModel"
    [(consent)]="consent"
    (statusChange)="show($event, false)"
  />
}
```

Il pannello era già lungo: metterci dentro due volte le stesse cose l'avrebbe raddoppiato. Un componente piccolo, con
un compito solo, si legge e si prova da solo (`cloud-ai-section.spec.ts`).

### J2. Come parlano il pannello e il componente

Tre modi, e qui ci sono tutti e tre:

| Nel componente | Nel pannello | Direzione | Per che cosa |
|---|---|---|---|
| `provider = input.required<AiCloudProvider>()` | `provider="MISTRAL"` | pannello → componente | quale servizio |
| `cloud = input.required<AiCloudStatus>()` | `[cloud]="s.mistral"` | pannello → componente | il suo stato |
| `aiModel = model.required<string>()` | `[(aiModel)]="mistralModel"` | **nei due sensi** | il nome del modello |
| `consent = model(false)` | `[(consent)]="consent"` | **nei due sensi** | la casella del consenso |
| `statusChange = output<AiStatus>()` | `(statusChange)="show($event, false)"` | componente → pannello | lo stato nuovo, dopo aver salvato la chiave |

- `input`: un valore che arriva dal pannello. `provider="MISTRAL"` senza parentesi quadre passa il testo così com'è;
  `[cloud]="..."` con le parentesi passa il valore di un'espressione;
- `model`: un `input` che il componente **può anche cambiare**. Quando l'utente scrive nel campo del modello, il
  componente fa `aiModel.set(...)`, e il segnale `mistralModel` del pannello cambia con lui. Le parentesi `[( )]` (la
  "banana in a box", come la chiamano in Angular) sono il **two-way binding**: `[aiModel]` più `(aiModelChange)`.
  Il pannello poi usa `mistralModel()` e `consent()` quando l'utente preme "Salva";
- `output`: un evento, come `accepted` dei suggerimenti (`docs/26`).

### J3. Il campo della chiave

```html
<input type="password" autocomplete="off" maxlength="300" [value]="key()" (input)="key.set(...)" />
```

- `type="password"`: i caratteri non si vedono (qualcuno potrebbe guardare lo schermo);
- `autocomplete="off"`: il browser non propone di ricordarla;
- dopo il salvataggio il campo si **svuota** (`this.key.set('')`): la chiave non resta nella pagina. Da lì in poi la
  pagina sa solo "✓ Chiave salvata (…XYZ9)", con "cambia" e "togli".

### J4. Il resto

- **"Come avere la chiave"**: un `<details>` (si apre con un clic) con i passi e il link al sito. I passi cambiano
  poco; le condizioni (prezzi, uso dei dati) cambiano spesso, e per quelle rimandiamo al sito;
- **"Che cosa viene inviato"**: l'elenco della Parte A4, sempre visibile, **prima** della casella del consenso;
- **la casella del consenso** compare finché il consenso non è salvato; poi "✓ Hai accettato l'invio di questi dati a
  ...";
- **"Prova la connessione"** si attiva solo con chiave e consenso salvati: prima, il backend risponderebbe comunque
  409;
- **"Misura la precisione"** ora elenca anche i servizi cloud pronti. Un menu ha un valore solo, quindi fornitore e
  modello stanno in un testo: `"MISTRAL|mistral-small-latest"`, diviso con `split('|')` quando parte la misura.

---

## Parte K: i test, e le prove dal vivo

### K1. Le librerie vere contro servizi finti: `CloudChatTest`

Lo stesso trucco di `ModelDownloadTest` (`docs/27`, E5): un piccolo server HTTP del JDK che risponde come Mistral o
come Google, e le **librerie vere** che ci parlano. Le impostazioni sono finte (Mockito), ma la chiave è cifrata con un
`SecretCipher` vero: così il test passa per tutto il percorso, decifratura compresa.

Il server finto registra ogni richiesta. Ecco che cosa riceve davvero (stampato dal test):

**Mistral** (`POST /v1/chat/completions`, `Authorization: Bearer mistral-key-123456`):

```json
{"model":"mistral-small-latest","messages":[{"content":"You classify transactions ...","role":"system"},
 {"content":"Categories:\n0 I don't know\n12 Spesa alimentare\n15 Ristoranti\n\nTransaction: expense\nMerchant: ESSELUNGA MILANO","role":"user"}],
 "temperature":0.0,"top_p":1.0,"presence_penalty":0.0,"frequency_penalty":0.0,"stream":false,"safe_prompt":false,
 "response_format":{"json_schema":{"name":"custom_schema","schema":{"$schema":"https://json-schema.org/draft/2020-12/schema",
 "type":"object","properties":{"categoryId":{"type":"integer","format":"int64"}},"required":["categoryId"],
 "additionalProperties":false},"strict":true},"type":"json_schema"}}
```

**Gemini** (`POST /v1beta/models/gemini-flash-latest:generateContent`, `x-goog-api-key: gemini-key-654321`):

```json
{"contents":[{"parts":[{"text":"Categories:\n0 I don't know\n12 Spesa alimentare\n..."}],"role":"user"}],
 "systemInstruction":{"parts":[{"text":"You classify transactions ..."}],"role":"user"},
 "generationConfig":{"temperature":0.0,"topP":1.0,"responseMimeType":"application/json",
 "responseJsonSchema":{"properties":{"categoryId":{"format":"int64","type":"integer"}},"required":["categoryId"],"type":"object"}}}
```

Confrontale con le richieste a mano della Parte B: sono quelle. Lo stesso prompt, lo stesso schema, la chiave
**decifrata** nel posto giusto per ognuno. Le prove:

- `mistralGetsTheKeyTheModelAndTheSchema`, `geminiGetsTheKeyTheModelAndTheSchema`: le richieste qui sopra, e la
  risposta letta (`categoryId: 12`);
- `aWrongKeyGivesAMessageThatSaysWhatToDo`: 401 da Mistral, 400 `API_KEY_INVALID` da Google (la risposta vera), e una
  sola richiesta (una chiave sbagliata non si riprova);
- `tooManyRequestsIsRetriedAndThenExplained`: 429 da Mistral, tre richieste, poi il messaggio;
- `afterATooManyRequestsTheNextTryCanWork`: 429 la prima volta, risposta giusta la seconda;
- `aNewKeyIsUsedRightAway`: cambi la chiave, la richiesta dopo usa quella nuova (il modello viene ricostruito).

### K2. Gli altri test

- `AiErrorsTest` (5): i codici, il 400 di Google, le cause, i numeri che non sono codici, la rete;
- `AiCloudControllerTest` (8): la chiave salvata **cifrata** (nel database c'è "v1:...", mai la chiave) e **mai
  restituita** (la risposta di `GET /api/ai` non contiene né la chiave né il valore cifrato); chiave e consenso
  necessari, e il consenso per servizio; togliere la chiave spegne l'IA; i modelli; i 400; la prova della connessione
  (giusta e sbagliata); senza consenso niente parte;
- frontend, `cloud-ai-section.spec.ts` (7): la spiegazione, l'avviso di Gemini, la chiave salvata e il campo svuotato,
  le ultime 4 lettere e "togli", una chiave con caratteri sbagliati, la casella del consenso, la prova;
- frontend, `ai-settings-panel.spec.ts` (2 in più): scegliere Mistral e salvare (modelli e consenso), misurare un
  servizio cloud.

### K3. Le prove dal vivo

Nel browser, con il backend vero:

1. **Gemini, con Google vero e una chiave inventata**: la chiave salvata (si vede "…1234"), "Salva" senza consenso
   rifiutato con il motivo, con il consenso "accesi: Google Gemini". "Prova la connessione" in 1,3 secondi: prima
   diceva "richiesta non valida" (Parte H2), dopo la correzione "La chiave di Google Gemini non è valida...". Lo
   stesso messaggio compare in "Chiedi all'IA", nella pagina Movimenti;
2. **Mistral**: dall'ambiente in cui è stato scritto il codice `api.mistral.ai` non era raggiungibile (rete
   limitata). Il risultato: "Non riesco a raggiungere Mistral AI: controlla la connessione a internet". Giusto anche
   questo, ma la prova con una chiave **vera** la fai tu (Parte L);
3. nel database: la chiave è "v1:...", e c'è la data del consenso.

Quello che **nessuno** ha ancora provato è una risposta vera con una chiave vera: lo farai tu. Se qualcosa non va, il
messaggio di "Prova la connessione" dirà che cosa (chiave, modello, limite, rete).

---

## Parte L: provalo tu, con la tua chiave

1. `git pull`, riavvia il backend (Flyway applica la V22 e la V23) e la dashboard.
2. Apri **aistudio.google.com**, entra con il tuo account Google, premi "Get API key" e poi "Create API key".
   Leggi le condizioni: con il piano gratuito Google può usare i dati inviati per migliorare i suoi prodotti.
3. In **Impostazioni**, "Suggerimenti" → "Nel cloud: Google Gemini". Incolla la chiave, "Salva la chiave": deve
   comparire "✓ Chiave salvata (…xxxx)" con le ultime 4 lettere della tua.
4. Leggi "Che cosa viene inviato", spunta la casella, premi **Salva**.
5. **Prova la connessione**: deve rispondere "ESSELUNGA → Spesa alimentare" in un secondo o due. Se no, il messaggio
   dice perché.
6. **Misura la precisione** con "Google Gemini · gemini-flash-latest" sui tuoi esercenti, e confrontala con
   gemma3:4b: quale indovina di più? Quale è più veloce?
7. Prova **"Chiedi all'IA"** nella pagina Movimenti.
8. Dopo qualche giorno, guarda su aistudio.google.com quanto hai consumato.

Test:

```
cd backend;  .\mvnw.cmd test
cd frontend; npm test -- --watch=false
```

---

## Parte M: sicurezza

| Rischio | Difesa |
|---|---|
| la chiave rubata dal database o da un backup | cifrata (AES-256-GCM), la chiave di cifratura fuori dal database (`docs/17`) |
| la chiave vista nella pagina o in un log | mai restituita (solo le ultime 4 lettere), campo password svuotato dopo il salvataggio, mai scritta nei log |
| la chiave di un servizio usata per un altro | un'etichetta di cifratura per servizio (`ai.gemini.api_key`; un servizio nuovo avrebbe la sua) |
| dati inviati senza saperlo | consenso esplicito, per servizio, con la data; spiegato che cosa parte **prima** della casella; controllato prima di **ogni** domanda (e dal database) |
| troppi dati al fornitore | le regole del 7b.2: testo senza cifre, niente importi, date, IBAN, nome |
| una chiave "strana" (spazi, caratteri di controllo) | `@Pattern [A-Za-z0-9._-]{10,300}` |
| la chiave nel browser | mai: la dashboard parla solo con il nostro backend, che parla con il servizio (Parte A5) |

---

## Parte N: esercizi guidati

### Esercizio 1: un secondo servizio

Aggiungi **OpenAI** (o di nuovo Mistral, se un giorno ti servisse), con lo stesso schema. Elenca tutti i punti da toccare, poi falli. Quanti test
devi scrivere da zero?

<details>
<summary>Soluzione</summary>

1. `pom.xml`: `spring-ai-openai` (la libreria, non lo starter);
2. una migrazione (V24): il vincolo `ai_settings_provider_check` con `'OPENAI'`, le colonne `openai_api_key`,
   `openai_model`, `openai_consent_at`, e il vincolo `ck_ai_settings_cloud_ready` rifatto;
3. `AiProvider`: `OPENAI("OpenAI")`, e `cloud()` lo comprende;
4. `AiSettings`: i tre campi, e un caso in più negli `switch`;
5. `AiChatClients`: un caso in `chatModel` (`OpenAiChatModel` con `OpenAiApi`) e in `options`
   (`OpenAiChatOptions`); `application.yml`: l'indirizzo;
6. `AiService`: `CloudStatus` di OpenAI nello `Status`;
7. frontend: il tipo, `CLOUD_INFO`, l'opzione nel menu, un `<app-cloud-ai-section provider="OPENAI">`;
8. test: due casi in `CloudChatTest` (la risposta di OpenAI ha la forma `choices[0].message.content`).

Per Mistral non devi nemmeno inventare: è esattamente quello che la Parte O ha tolto. `git show 7f2df93:<file>` ti
mostra ogni file com'era con Mistral.

`CategoryModel`, `ModelEvaluation`, `AiAnswerJob`, `AiSuggestionService`, il prompt: **non si toccano**. È il
vantaggio dell'astrazione: un fornitore nuovo è un "adattatore" in più, non un programma nuovo.
</details>

### Esercizio 2: quanto costa

Con un piano a pagamento si paga a "token" (pezzi di parola), separati tra quelli della domanda e quelli della
risposta. Nella risposta di Gemini c'è `usageMetadata` (`promptTokenCount`, `candidatesTokenCount`, Parte K1). Come faresti a
mostrare nella pagina quanti token ha usato l'ultimo "Chiedi all'IA"?

<details>
<summary>Soluzione</summary>

Con `ChatClient` si può chiedere la risposta completa invece del solo oggetto: `.call().responseEntity(Answer.class)`
restituisce sia l'`Answer` sia la `ChatResponse`, e `chatResponse.getMetadata().getUsage()` ha `getPromptTokens()` e
`getCompletionTokens()`. `CategoryModel.suggest` dovrebbe restituirli (ad esempio in un record con la categoria e i
token), `AiAnswerJob` li somma nello `State`, e la pagina li mostra. Il prezzo per milione di token lo trovi sul sito
del fornitore: moltiplicando hai il costo. Per il nostro compito (una domanda di ~150 token e una risposta di ~10)
sono cifre molto piccole.
</details>

### Esercizio 3: ritirare il consenso

Oggi il consenso, una volta dato, resta. Aggiungi "ritira il consenso": cancella la data e, se quel servizio era
quello scelto, spegne l'IA. Perché è importante poterlo fare?

<details>
<summary>Soluzione</summary>

In `AiSettings` un metodo `withdrawConsent(cloud)` che mette `null` nella data e, come `apiKey(cloud, null)`, spegne
l'IA se `provider == cloud` (altrimenti il vincolo `ck_ai_settings_cloud_ready` rifiuterebbe la riga). In
`AiController`: `DELETE /api/ai/consent/{provider}`. Nella pagina: accanto a "✓ Hai accettato...", un link "ritira".
È importante perché un consenso vale solo se si può anche ritirare: chi ha detto sì deve poter cambiare idea, con la
stessa facilità con cui ha detto sì (è anche il principio del GDPR).
</details>

---

## Parte O: aggiornamento, Mistral tolto (V23)

### O1. Perché

Provando a creare la chiave, la console di Mistral ("Studio") ha mostrato: *"Passa a un piano a pagamento per
utilizzare le tue chiavi API. La tua organizzazione è sul piano Free."* Con il piano gratuito le chiavi API non si
attivano. Google invece dà ancora una chiave gratuita, con dei limiti. E per il nostro compito un servizio cloud
basta: chi ha un computer adatto usa Ollama (i dati restano a casa), gli altri Gemini.

È anche una lezione: i piani gratuiti dei servizi cloud **cambiano** (per questo la guida non scrive prezzi e limiti
come se fossero fissi). Un'app che dipende da un fornitore deve poterlo cambiare senza essere riscritta.

### O2. Che cosa abbiamo toccato

È l'Esercizio 1 al contrario, e si vede di nuovo il vantaggio dell'astrazione: togliere un fornitore tocca solo gli
"adattatori".

| Dove | Che cosa |
|---|---|
| `pom.xml` | via la dipendenza `spring-ai-mistral-ai` |
| `application.yml` | via `wallettracker.ai.mistral.base-url` |
| `V23__solo_gemini.sql` | nuova migrazione (O3) |
| `AiProvider` | via `MISTRAL`; `cloud()` è solo `GEMINI` |
| `AiSettings` | via i tre campi di Mistral e i casi negli `switch`; `changeModels(ollama, gemini)` |
| `AiChatClients` | via il caso Mistral in `chatModel` e `options`, e il suo indirizzo nei costruttori; `retry` non ha più il caso del 429 (lo aggiungevamo solo per Mistral: per Gemini i tentativi li fa la libreria di Google) |
| `AiService`, `AiController` | via `mistral` dallo `Status` e `mistralModel` dalla richiesta |
| frontend | via `'MISTRAL'` dai tipi, da `CLOUD_INFO`, dal menu e dal pannello |
| test | `CloudChatTest`, `AiCloudControllerTest`, `AiErrorsTest` usano Gemini |

**Non toccati**: `CategoryModel`, `CategoryPrompt`, `ModelEvaluation`, `AiAnswerJob`, `AiSuggestionService`,
`AiErrors` (solo un commento), `CloudAiSection` (il componente è scritto per un servizio qualunque).

### O3. La migrazione V23

La V22 è già applicata nel tuo database: **non si modifica** (Flyway controlla il checksum, `docs/28`). Si scrive
una migrazione nuova che la "annulla" in parte:

```sql
UPDATE ai_settings SET provider = 'NONE' WHERE provider = 'MISTRAL';

ALTER TABLE ai_settings DROP CONSTRAINT ck_ai_settings_cloud_ready;
ALTER TABLE ai_settings ADD CONSTRAINT ck_ai_settings_cloud_ready CHECK (
    provider <> 'GEMINI' OR (gemini_api_key IS NOT NULL AND gemini_consent_at IS NOT NULL));

ALTER TABLE ai_settings DROP CONSTRAINT ai_settings_provider_check;
ALTER TABLE ai_settings ADD CONSTRAINT ai_settings_provider_check CHECK (provider IN ('NONE', 'OLLAMA', 'GEMINI'));

ALTER TABLE ai_settings DROP COLUMN mistral_api_key;
ALTER TABLE ai_settings DROP COLUMN mistral_model;
ALTER TABLE ai_settings DROP COLUMN mistral_consent_at;
```

L'**ordine** conta:

1. prima l'`UPDATE`: chi aveva scelto Mistral torna a "spento". Se lo facessimo dopo il nuovo vincolo, la riga con
   `MISTRAL` lo violerebbe e la migrazione fallirebbe;
2. poi il vincolo "chiave e consenso", rifatto senza Mistral. Va tolto **prima** delle colonne, perché le nomina;
3. infine le colonne. Insieme a `mistral_api_key` se ne va anche l'eventuale chiave salvata: un segreto che non
   serve più è meglio non tenerlo.

L'abbiamo provata anche su un database "vecchio": applicate le V1-V22, scelto Mistral con chiave e consenso, poi la
V23. Risultato: `provider = NONE`, le colonne di Mistral sparite, i vincoli nuovi al loro posto.

### O4. Una cosa scoperta nei test

Prima il 429 ("troppe richieste") lo provavamo con Mistral. Provandolo con Gemini si è visto che cosa fa la libreria
di Google: riceve **5** richieste (la prima e 4 nuovi tentativi), aspettando sempre di più tra l'una e l'altra; il test
durava 17 secondi. E per gli errori "passeggeri" riprovava anche il nostro `RetryTemplate`: due livelli di tentativi,
uno dentro l'altro, senza che nel log restasse traccia. La Parte O6 lo sistema.

### O5. Risultati

- backend: tutti i test passano (1 saltato, la prova dal vivo), e dopo la O6 il test del 429 dura pochi secondi
  invece di 17;
- frontend: 116 test passano;
- la V23 applicata su un database con Mistral scelto: va.

### O6. "102 secondi": quanto ci mette, e quale modello risponde

La prima "Prova la connessione" con Gemini ha risposto giusto, ma in **102,6 secondi**. Per capire di chi è il tempo
abbiamo fatto la stessa domanda **senza l'app**, da PowerShell, con una sola richiesta:

```powershell
$k = Read-Host "Chiave"      # incollala qui: così non finisce nella cronologia né negli screenshot
$body = '{"contents":[{"parts":[{"text":"Di che categoria di spesa è ESSELUNGA? Rispondi con una parola."}]}]}'
$sw = [Diagnostics.Stopwatch]::StartNew()
$r = Invoke-RestMethod -Method Post -ContentType "application/json" -Headers @{ "x-goog-api-key" = $k } `
     -Uri "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent" -Body $body
$sw.Elapsed.TotalSeconds
$r.modelVersion
$r.usageMetadata
```

Risultato: **81 secondi** anche così, `modelVersion` = `gemini-3.8-flash`, `thoughtsTokenCount` = 246. Che cosa ci dice:

- il tempo **non** è dell'app: una richiesta diretta, senza i nostri tentativi, ci mette lo stesso. È l'attesa dal
  lato di Google (con il piano gratuito le richieste possono aspettare in coda) o della rete;
- **non** è il ragionamento: 246 token un modello Flash li genera in un paio di secondi;
- dietro l'alias `gemini-flash-latest` c'è, oggi, `gemini-3.8-flash`: lo dice Google nella risposta.

Per separare rete e Google, `curl.exe` (incluso in Windows) misura le fasi della richiesta:

```powershell
curl.exe -s -o NUL -H "x-goog-api-key: $k" -H "Content-Type: application/json" -d $body `
  -w "connessione %{time_connect}s, prima risposta %{time_starttransfer}s, totale %{time_total}s`n" `
  "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"
```

Se "connessione" è grande, il problema è la rete (firewall, antivirus, IPv6); se è piccola e "prima risposta" è
grande, è Google che ci mette a rispondere. Prova anche `gemini-flash-lite-latest`, e in orari diversi.

**Che cosa abbiamo cambiato nell'app:**

1. **Il modello vero e il ragionamento, nella pagina.** `CategoryModel` ora usa `.responseEntity(...)` invece di
   `.entity(...)`: insieme alla risposta "tradotta" in oggetto Java ci dà la `ChatResponse` completa. Da lì
   `getMetadata().getModel()` è il `modelVersion` di Google, e `GoogleGenAiUsage.getThoughtsTokenCount()` i token di
   ragionamento. "Prova la connessione" li mostra: *"Ha risposto il modello gemini-3.8-flash. Prima di rispondere ha
   "ragionato" con 246 token."* Il risultato è un record, `CategoryModel.Reply`; `suggest(...)` (usato da misura e
   "Chiedi all'IA") resta com'era e prende solo la categoria.
2. **Un solo livello di tentativi, e nel log.** Alla libreria di Google diciamo di fare un tentativo solo
   (`HttpRetryOptions.builder().attempts(1)`); i nuovi tentativi li fa il nostro `RetryTemplate`, per 429, 500, 503
   e per gli errori "passeggeri" di Spring AI: al massimo 3 richieste, con 5 e 10 secondi di attesa. Un
   `RetryListener` scrive ogni tentativo non riuscito nel log del backend:

   ```
   WARN  AiChatClients : Google Gemini: tentativo 1 non riuscito: Google Gemini risponde "troppe richieste"...
   ```

   Così, se un giorno vedi 100 secondi, nel terminale del backend c'è scritto se e perché ha riprovato. E ogni
   "Prova la connessione" riuscita scrive una riga con il tempo, il modello e i token. Il codice HTTP lo trova
   `AiErrors.httpCode(...)`, lo stesso metodo con cui `AiErrors` sceglie il messaggio.
3. **Il ragionamento non lo limitiamo.** Si potrebbe chiedere a Gemini di ragionare meno (`thinkingLevel`), ma
   abbiamo scelto di no: meglio una risposta più precisa che una più veloce, e come abbiamo visto il tempo non
   dipende dal ragionamento.

**Perché il nome del modello è un campo libero.** Google ha molti modelli e ne cambia l'elenco ogni pochi mesi:
scritto nel codice, invecchierebbe subito. Il nome va a Google così com'è. Un **alias** (`gemini-flash-latest`,
`gemini-flash-lite-latest`) lo sposta Google sul modello più recente; un **nome fisso** (es. `gemini-2.5-flash`)
resta lo stesso finché Google non lo ritira, ed è meglio per confrontare le misure nel tempo. Un nome sbagliato dà
404, e la pagina dice "Il modello non esiste".

**Il Lite.** La stessa domanda con `gemini-flash-lite-latest` (oggi `gemini-3.5-flash-lite`): **4,3 secondi**,
connessione 0,03 s, nessun token di ragionamento, risposta giusta. Con `gemini-flash-latest`, rifatta con `curl.exe`:
connessione 0,08 s, **prima risposta dopo 366 secondi**. La rete è velocissima, l'attesa è tutta nella coda di
Google per il piano gratuito (`"serviceTier": "standard"`), soprattutto su un modello appena uscito.

Per questo sotto il campo "Modello" la pagina ora suggerisce i due nomi, con che cosa cambia (`CLOUD_INFO.models` in
`cloud-ai-section.ts`): *gemini-flash-lite-latest*, veloce; *gemini-flash-latest*, ragiona e può essere più preciso
ma può impiegare minuti. Un clic sul nome lo mette nel campo. Quale indovina di più sui tuoi esercenti lo dice
"Misura la precisione".

**Sicurezza:** una chiave mostrata in uno screenshot o incollata in una chat va considerata compromessa. Si cancella
su aistudio.google.com (API keys) e se ne crea una nuova, poi "cambia" nelle Impostazioni. Per questo nel comando
sopra c'è `Read-Host`: la chiave non resta scritta da nessuna parte.
