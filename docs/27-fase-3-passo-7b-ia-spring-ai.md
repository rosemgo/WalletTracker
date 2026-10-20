# 27. Fase 3, passo 7b: l'intelligenza artificiale con Spring AI e Ollama

**Obiettivo:** un modello di intelligenza artificiale che **suggerisce** la categoria dei movimenti degli
esercenti mai visti (quelli che i suggerimenti "dalla tua storia" del passo 7a non coprono). Il modello gira
**sul tuo computer** con Ollama, e il backend gli parla con **Spring AI**, la libreria di Spring per l'IA usata
anche nelle aziende. Nel passo 7c lo stesso codice userà anche un servizio cloud.

Il passo è diviso in parti, ognuna con i suoi test:

| Parte | Cosa | Stato |
|---|---|---|
| **7b.1** | Spring AI nel progetto; nelle Impostazioni: IA accesa/spenta, stato di Ollama, "Scarica il modello" | fatta (questa guida) |
| 7b.2 | il modello che sceglie la categoria (`ChatClient`, prompt, risposta strutturata) e la misura della precisione | da fare |
| 7b.3 | i suggerimenti dell'IA nel riquadro dei Movimenti, "Chiedi all'IA", accetta / no | da fare |

> Prerequisiti: passo 7a (`docs/26`) e `git pull`. Al primo avvio Flyway applica la migrazione **V18**.

---

## Parte A: i concetti

### 1. Come usano l'IA le app (anche quelle per smartphone)

Un'app come Yazio (fotografi il piatto, ti dice le calorie) **non** fa girare l'IA sul telefono e **non**
parla direttamente con il modello:

```
Telefono (app)  ──foto──▶  Server dell'azienda  ──foto + istruzioni──▶  Modello IA
                ◀─calorie──                    ◀──"pasta al pomodoro, ~350 g"──
```

- il **server** tiene la chiave segreta del servizio IA: messa nell'app, chiunque potrebbe estrarla;
- il modello risponde in un **formato preciso** (JSON), non a parole;
- il server usa la risposta con i **suoi** dati (le calorie vengono da un database degli alimenti).

WalletTracker è uguale: il telefono (o il browser) apre la dashboard, il **backend Spring** è il server e
parla con il modello. La dashboard non sa nemmeno che c'è un'IA.

### 2. Spring AI

Spring AI fa per i modelli di IA quello che Spring Data fa per i database: un'interfaccia comune
(`ChatClient`, `ChatModel`) e tanti "adattatori" (Ollama, OpenAI, Anthropic, Google...). Cambiare fornitore
vuol dire cambiare una dipendenza e la configurazione, non il codice.

Nel `pom.xml`:

```xml
<dependencyManagement>                          <!-- la "distinta base" (BOM): versioni compatibili tra loro -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-bom</artifactId>
        <version>${spring-ai.version}</version>  <!-- 2.0.1: la generazione per Spring Boot 4 -->
        <type>pom</type>
        <scope>import</scope>
    </dependency>
</dependencyManagement>
...
<dependency>                                    <!-- l'adattatore per Ollama, senza versione: la dà la BOM -->
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-ollama</artifactId>
</dependency>
```

Lo **starter** porta la **configurazione automatica**: Spring crea da solo un `OllamaApi` (il client HTTP di
Ollama) e un `OllamaChatModel`, con l'indirizzo scritto in `application.yml`:

```yaml
spring:
  ai:
    ollama:
      base-url: ${OLLAMA_URL:http://localhost:11434}
```

Prima di usarla abbiamo verificato che Spring AI 2.0.1 si risolva, compili e lasci **avviare**
l'applicazione con il nostro Spring Boot 4.1 (i test che caricano tutto il contesto passano). Spring AI porta
con sé anche le librerie "reattive" (`spring-webflux`, `reactor-core`): l'app resta una normale applicazione
Spring MVC, perché quando ci sono entrambe Spring Boot sceglie MVC.

### 3. Configurazione dell'installazione e scelta dell'utente

Due cose diverse, in due posti diversi:

| Cosa | Dove | Chi la decide |
|---|---|---|
| dove si trova Ollama (`http://localhost:11434`) | `application.yml` (variabile `OLLAMA_URL`) | chi installa |
| se usare l'IA, e con quale modello | tabella `ai_settings` (V18), pagina Impostazioni | l'utente |

`ai_settings` ha **una sola riga** (`CHECK (id = 1)`), come le credenziali di Enable Banking (V7), e la
migrazione la crea subito con l'IA **spenta**.

### 4. Ollama: chi lo avvia, e quanto costa

Ollama è un programma che scarica e fa girare modelli di IA sul tuo computer, con un'API HTTP. Con Docker:

```bash
docker run -d -p 127.0.0.1:11434:11434 -v ollama:/root/.ollama --name ollama ollama/ollama
```

- `-p 127.0.0.1:11434:11434`: raggiungibile solo da questo computer;
- `-v ollama:/root/.ollama`: i modelli scaricati restano anche se il container viene ricreato.

Quando è **inattivo** consuma pochissimo: carica il modello in memoria quando arriva una richiesta e lo
toglie dopo qualche minuto senza richieste. I GB di memoria servono solo mentre lavora. Nella Fase 4 sarà un
servizio di Docker Compose con un **profilo** (`docker compose --profile ia up`): chi non lo vuole non lo
scarica nemmeno.

**L'app non avvia Ollama da sola**, e non è una dimenticanza: per farlo il backend dovrebbe controllare Docker,
cioè in pratica il computer intero. Se qualcuno trovasse un difetto nell'app, avrebbe in mano tutta la
macchina. A ogni parte diamo solo i permessi che le servono: l'app chiede a Ollama se c'è; accenderlo è una
scelta di chi installa.

### 5. Il modello di partenza

`qwen2.5:3b`: piccolo (circa 2 GB), funziona anche senza scheda video, se la cava con l'italiano e con le
risposte in JSON. Si cambia dalle Impostazioni: nel 7b.2 misureremo la precisione sui tuoi dati, ed è così
che si sceglie un modello (con i numeri, non a sensazione).

---

## Parte B: il codice

### Il backend (pacchetto `ai`)

| Classe | Che cosa fa |
|---|---|
| `AiSettings`, `AiSettingsRepository`, `AiProvider` | la riga di `ai_settings`: `NONE` (spento) o `OLLAMA`, e il modello |
| `AiService` | le impostazioni e lo **stato**: Ollama risponde? quali modelli ha? c'è quello scelto? |
| `ModelDownload` | lo scaricamento del modello, **in background** |
| `AiController` | `GET /api/ai`, `PUT /api/ai`, `POST /api/ai/model/download` (202) |

**Ollama risponde?** `AiService` chiede l'elenco dei modelli (`ollamaApi.listModels()`): se risponde, è
acceso; se lancia un'eccezione (connessione rifiutata), è spento. Un modello scelto come `llama3.2` è
installato se nell'elenco c'è `llama3.2:latest` (`:latest` è l'etichetta predefinita di Ollama).

**Lo scaricamento in background.** Scaricare un modello dura minuti; una richiesta HTTP non può aspettare
tanto. `POST /api/ai/model/download` fa partire lo scaricamento e risponde subito **202 Accepted**
("ricevuto, ci sto lavorando"). Spring AI dà l'avanzamento come un `Flux`, uno *stream* di messaggi che
arrivano uno alla volta ("pulling manifest", "downloading", ...): con `subscribe` diciamo che cosa fare a ogni
messaggio, alla fine e in caso di errore, e il lavoro avviene su un altro thread. Lo stato sta in un
`AtomicReference`: un thread lo scrive, quello delle richieste lo legge, e ognuno vede sempre un valore
completo. La dashboard chiede lo stato ogni secondo finché non è finito (**polling**).

**Una lezione imparata provando dal vivo.** La prima versione, alla fine dello stream, diceva "success". Con
Ollama vero, quando lo scaricamento fallisce (nome sbagliato, internet non raggiungibile), succede questo:

1. Ollama **non** risponde con un errore HTTP: manda un messaggio normale, `{"error": "pull model manifest:
   ... Forbidden"}`, e chiude lo stream;
2. il record di Spring AI per l'avanzamento (`ProgressResponse`) **non ha il campo `error`**: il messaggio
   arriva vuoto, e lo stream finisce "regolarmente".

Risultato: "success", e nessun modello. I test con il finto Ollama passavano, perché il finto faceva quello
che *pensavamo* facesse Ollama. La correzione: alla fine dello stream non ci fidiamo, e chiediamo a Ollama se
il modello **adesso c'è**; se no, è un errore con una spiegazione utile. Un'astrazione comoda può nascondere
dettagli che contano, e c'è un test (`aStreamThatEndsWithoutTheModelIsAFailureNotASuccess`) che riproduce il
comportamento vero.

**Sicurezza.** Il nome del modello è validato (`[A-Za-z0-9._:/-]{1,100}`): niente spazi, punti e virgola o
altro che non serva. Ollama ascolta solo su `127.0.0.1`.

### Il frontend

`ai/ai-settings-panel`: un componente a sé, in fondo alla pagina Impostazioni.

- **Suggerimenti**: Spenti (di serie) / Sul mio computer (Ollama), e il nome del modello;
- **Stato**: "✓ Ollama risponde" o "✗ Ollama non risponde" con il comando per avviarlo; "✓ Il modello è
  scaricato" o il pulsante **Scarica il modello**, con l'avanzamento (percentuale e barra) e il motivo se
  fallisce. Sempre simbolo + testo, mai solo il colore;
- il polling usa `setTimeout` e si ferma quando lo scaricamento finisce, o se si lascia la pagina
  (`DestroyRef.onDestroy`).

---

## Parte C: i test

| Test | Che cosa verifica |
|---|---|
| `AiControllerTest` (6) | spento di serie; Ollama che non risponde; accendere e vedere se il modello c'è (anche `:latest`); impostazioni sbagliate → 400; lo scaricamento in background, con l'avanzamento e la fine; lo stream che finisce senza modello è un errore; lo scaricamento fallito spiega perché |
| `ai-settings-panel.spec.ts` (4) | spento di serie e accensione; Ollama che non risponde; lo scaricamento seguito con il polling fino alla fine (timer finti di Vitest); il motivo di un fallimento |

Nei test Ollama è **finto** (`@MockitoBean OllamaApi`): decidiamo noi che cosa risponde. Così la CI non ha
bisogno di Ollama né di GB di modello.

Nel browser, con **Ollama vero** (in un container): spento di serie; acceso; "✓ Ollama risponde"; "✗ Il
modello non è scaricato"; "Scarica il modello" con il motivo del fallimento (nell'ambiente di sviluppo
l'accesso a `registry.ollama.ai` era bloccato: è così che abbiamo scoperto il punto 2 sopra); con Ollama
spento, "✗ Ollama non risponde".

---

## Parte D: provalo

1. `git pull`, riavvia il backend (Flyway applica la V18) e la dashboard.
2. Avvia Ollama: `docker run -d -p 127.0.0.1:11434:11434 -v ollama:/root/.ollama --name ollama ollama/ollama`.
3. **Impostazioni** → *Suggerimenti con l'intelligenza artificiale* → "Sul mio computer (Ollama)" → **Salva**.
4. Deve comparire "✓ Ollama risponde" e "✗ Il modello qwen2.5:3b non è scaricato": premi **Scarica il
   modello** e guarda l'avanzamento (un paio di GB).
5. Alla fine: "✓ Il modello qwen2.5:3b è scaricato". Prova a parlargli dal terminale:
   `docker exec -it ollama ollama run qwen2.5:3b "Di che categoria è una spesa da ESSELUNGA?"`.
6. Ferma Ollama (`docker stop ollama`) e ricarica la pagina: "✗ Ollama non risponde".
