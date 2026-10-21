# 29. Fase 3, passo 7b.3: "Chiedi all'IA", i suggerimenti dell'intelligenza artificiale

**Obiettivo:** portare l'IA dove serve, nella pagina **Movimenti**. Gli esercenti senza categoria che non hai mai
categorizzato ricevono un suggerimento dal modello, con il segno **"IA"**, nello stesso riquadro dei
suggerimenti "dalla tua storia" (passo 7a). Come sempre: **suggerisce soltanto**, decidi tu con "Accetta" o
"no".

Gli ingredienti ci sono già tutti:

| Pezzo | Da dove viene | Che cosa fa qui |
|---|---|---|
| `CategoryModel` | 7b.2 (`docs/28`) | fa **una** domanda al modello |
| le regole della domanda (testo senza cifre, categorie del gruppo, esempi) | 7b.2, `EvaluationCases` | riusate così come sono |
| il lavoro in background con lo stato e il polling | 7b.1 (`ModelDownload`), 7b.2 (`ModelEvaluation`) | lo stesso schema, terza volta |
| il riquadro dei suggerimenti, "Accetta" e "no" | 7a (`docs/26`) | ci aggiungiamo i suggerimenti dell'IA |

Di nuovo ci sono: **quali** esercenti chiedere, **dove salvare** le risposte, e **come unirle** ai suggerimenti
dalla storia.

> Prerequisiti: `docs/28`, `git pull`. Al primo avvio Flyway applica la **V20**. Per provarlo: Ollama avviato,
> IA accesa in Impostazioni con un modello scaricato (`gemma3:4b`).

**Indice**

- Parte A: come funziona, visto dall'utente
- Parte B: la catena delle chiamate, dal clic alla risposta
- Parte C: quali esercenti chiedere (`AiQuestions`)
- Parte D: dove salvare le risposte (V20, `AiCategoryAnswer`)
- Parte E: le domande in background (`AiAnswerJob`)
- Parte F: i controlli prima di partire, e l'API (`AiSuggestionService`)
- Parte G: unire IA e storia (`HistorySuggester`)
- Parte H: il riquadro nella pagina Movimenti (Angular)
- Parte I: i test, e la prova dal vivo
- Parte J: sicurezza
- Parte K: esercizi guidati, con le soluzioni

---

## Parte A: come funziona, visto dall'utente

1. Apri **Movimenti**. Con l'IA accesa, nel riquadro "Suggerimenti di categoria" leggi ad esempio "5 esercenti
   senza categoria non sono ancora stati chiesti all'IA (gemma3:4b)" e il pulsante **"Chiedi all'IA"**.
2. Clic: "L'IA (gemma3:4b) sta guardando gli esercenti: 2 di 5...", con la barra. Ogni esercente richiede
   qualche secondo.
3. Alla fine: "L'IA ha guardato 5 esercenti: 4 suggerimenti, per 1 non sa." Nell'elenco compaiono i
   suggerimenti nuovi, con il segno **IA** e la scritta "suggerita dall'IA (gemma3:4b): controlla prima di
   accettare".
4. **Accetta** dà la categoria a tutti i movimenti senza categoria di quell'esercente; **no** non la propone più.
   Sono gli stessi pulsanti, e lo stesso codice, dei suggerimenti dalla storia.

Le scelte di progetto, e perché:

| Scelta | Perché |
|---|---|
| parte solo con un **clic** | il modello usa CPU e memoria: decidi tu quando. Niente lavoro nascosto dopo ogni sincronizzazione |
| solo gli esercenti che **non hai mai scelto** | per quelli che hai scelto ci sono i suggerimenti dalla tua storia, più affidabili di qualunque modello. E se hai scelto categorie diverse (Amazon: una volta spesa, una volta elettronica), non sarà il modello a decidere |
| al massimo **20** esercenti per clic | con ~5 secondi a domanda, meno di due minuti. Prima quelli con più movimenti: si sistema di più con meno domande |
| le risposte si **salvano** | ogni risposta costa secondi di calcolo: un esercente si chiede una volta sola, anche quando la risposta è "non so" |
| prima i suggerimenti dalla **storia**, poi quelli dell'IA | i primi vengono da te |
| IA spenta → i suggerimenti dell'IA **spariscono** | le risposte restano nel database, ma "spento" vuol dire spento |

---

## Parte B: la catena delle chiamate, dal clic alla risposta

Due momenti separati, come per la misura (`docs/28`, Parte H2): il clic **fa partire** il lavoro e la risposta
HTTP torna subito; il lavoro gira su un thread suo; la pagina chiede ogni 2 secondi a che punto è.

```
[Browser] Movimenti → clic su "Chiedi all'IA"
   SuggestionsPanel.askAi()                          suggestions-panel.ts
   WalletApi.askAi()                                 → POST /api/ai/suggestions
      │
[Backend] AiController.askForSuggestions()
   AiSuggestionService.start()
      1. IA accesa? Ollama risponde? modello scaricato?        (altrimenti 409, con il motivo)
      2. AiQuestions.build(...)                                → al massimo 20 domande
         (esercenti senza categoria, mai scelti da te, mai chiesti)
      3. AiAnswerJob.start(model, domande)                    → stato "in corso", lavoro a un altro thread
   ← risponde SUBITO 202 con lo stato

[Backend, thread in background] AiAnswerJob.run()
   per ogni domanda:
      CategoryModel.suggest(domanda, model)                   → Spring AI → Ollama (qualche secondo)
      AiCategoryAnswerRepository.save(...)                    → una riga in ai_category_answer (V20)
      aggiorna lo stato (done, found)

[Browser, intanto] ogni 2 secondi GET /api/ai/suggestions    (polling)
   AiSuggestionService.status()  → "2 di 5"...
   quando ha finito: GET /api/category-suggestions
      SuggestionService.suggestions()
         HistorySuggester.suggest(scelte, movimenti senza categoria, "no", risposte dell'IA)
      → l'elenco con i suggerimenti nuovi (segno "IA")

[Browser] clic su "Accetta"                                  → POST /api/category-suggestions/accept
   SuggestionService.accept(esercente, categoria)            (lo stesso del passo 7a)
```

Per seguirla nel codice: parti da `AiController.askForSuggestions` (in
`backend/src/main/java/it/wallettracker/ai/AiController.java`) e segui le chiamate, poi da
`SuggestionService.suggestions`.

---

## Parte C: quali esercenti chiedere (`AiQuestions`)

`backend/src/main/java/it/wallettracker/ai/AiQuestions.java`. Una classe "pura": riceve liste, restituisce una
lista, niente database. Per questo si prova con test semplici e veloci (`AiQuestionsTest`).

```java
public record Ask(String merchantKey, CategoryQuestion question, int transactions) {}

public static List<Ask> build(List<BankTransaction> uncategorized, List<BankTransaction> manual,
        List<Category> categories, List<Set<String>> ownerNames, Collection<String> alreadyAsked, int limit)
```

I passi:

1. **gli esercenti che hai già scelto**: la `MerchantKey` (`docs/26`) di ogni movimento con una categoria
   scelta a mano. Vanno saltati;
2. **quelli già chiesti**: `alreadyAsked`, le chiavi salvate (Parte D). Saltati anche questi;
3. **i movimenti senza categoria, per esercente**: solo spese ed entrate (un trasferimento o un acquisto di
   titoli non hanno bisogno di una categoria di spesa);
4. **gli esempi**: le tue scelte più recenti, al massimo 10. Gli stessi per tutte le domande: nessuna delle tue
   scelte riguarda questi esercenti (li abbiamo saltati al punto 1), quindi non c'è il rischio di "suggerire la
   risposta" che c'era nella misura;
5. **l'ordine**: prima gli esercenti con più movimenti; a parità, in ordine alfabetico (così il risultato è
   sempre lo stesso, e i test lo possono controllare);
6. **la domanda**, per ognuno, dal movimento più recente: il testo senza cifre, spesa o entrata, le categorie del
   gruppo giusto.

I punti 4 e 6 non sono nuovi: sono le regole della misura. Per non scriverle due volte, `EvaluationCases` ora
espone `latestChoices` (le tue scelte, una per esercente) e le usano tutte e due. È una piccola
**rifattorizzazione**: si sposta del codice dove lo possono usare in due, senza cambiare che cosa fa (lo
garantiscono i test della misura, rimasti verdi).

---

## Parte D: dove salvare le risposte (V20, `AiCategoryAnswer`)

### D1. La tabella

```sql
CREATE TABLE ai_category_answer (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    merchant_key TEXT         NOT NULL,
    category_id  BIGINT,
    model        VARCHAR(100) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_ai_category_answer UNIQUE (merchant_key),
    CONSTRAINT fk_ai_category_answer_category FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE CASCADE
);
```

- **una riga per esercente** (`UNIQUE (merchant_key)`): la stessa chiave dei "no" (V15) e degli abbonamenti;
- **`category_id` può essere NULL**: vuol dire "il modello non sa". Si salva anche questo, altrimenti a ogni clic
  richiederemmo gli stessi esercenti senza speranza (con temperatura 0 la risposta sarebbe la stessa);
- **`model`**: chi ha risposto, da mostrare accanto al suggerimento;
- **`ON DELETE CASCADE`**: eliminando una categoria spariscono le risposte che la indicano, e quegli esercenti
  tornano tra quelli da chiedere.

Una nota sulla `MerchantKey`: se un giorno cambia il modo di calcolarla, le chiavi salvate vanno ricalcolate (come
ha fatto la V16 per abbonamenti e "no"). Il commento di `MerchantKey` ora elenca anche questa tabella.

### D2. L'entità e il repository

`AiCategoryAnswer` è un'entità come `SuggestionDismissal` (`docs/26`): `@ManyToOne` verso la categoria, che qui
può essere null. Nel repository due query:

```java
@Query("SELECT a FROM AiCategoryAnswer a JOIN FETCH a.category")
List<AiCategoryAnswer> findAllWithCategory();      // le risposte con una categoria, e la categoria (docs/24)

@Query("SELECT a.merchantKey FROM AiCategoryAnswer a")
List<String> findAllMerchantKeys();                // gli esercenti già chiesti, anche con "non so"
```

`JOIN FETCH` (`docs/24`) carica la categoria nella stessa query: senza, Hibernate farebbe una query in più per
ogni risposta. E un `JOIN` (non `LEFT JOIN`) esclude da solo le risposte "non so", che non hanno una categoria.

---

## Parte E: le domande in background (`AiAnswerJob`)

È la terza volta che usiamo questo schema, quindi solo le differenze rispetto a `ModelEvaluation`
(`docs/28`, Parte H2):

```java
private void run(String model, List<AiQuestions.Ask> asks) {
    int found = 0;
    for (int i = 0; i < asks.size(); i++) {
        AiQuestions.Ask ask = asks.get(i);
        try {
            Optional<Long> answer = categoryModel.suggest(ask.question(), model);
            Category category = answer.flatMap(categoryRepository::findById).orElse(null);
            answerRepository.save(new AiCategoryAnswer(ask.merchantKey(), category, model));
            found += category != null ? 1 : 0;
        } catch (RuntimeException e) {
            state.set(new State(model, false, i, asks.size(), found, "Il modello non ha risposto: " + e.getMessage()));
            return;
        }
        state.set(new State(model, i + 1 < asks.size(), i + 1, asks.size(), found, null));
    }
}
```

- **ogni risposta si salva subito**, non tutte alla fine: se Ollama si spegne a metà, le risposte già avute
  restano, e il prossimo clic riparte dagli esercenti mancanti (quelli salvati sono "già chiesti");
- `answer.flatMap(categoryRepository::findById)`: da "forse un id" a "forse una categoria". `flatMap` perché
  anche `findById` restituisce un `Optional`: con `map` avremmo un `Optional<Optional<Category>>`;
- `repository.save` fuori da un `@Transactional`: ogni `save` di Spring Data apre e chiude la sua transazione.
  Qui va bene così: ogni risposta è indipendente dalle altre.

---

## Parte F: i controlli prima di partire, e l'API (`AiSuggestionService`)

`start()` controlla, nell'ordine, e per ogni problema risponde **409** con un messaggio che dice che cosa fare:

| Controllo | Messaggio |
|---|---|
| IA spenta | "I suggerimenti con l'IA sono spenti: accendili in Impostazioni." |
| Ollama non risponde | "Ollama non risponde: è avviato?" |
| modello non scaricato | "Il modello gemma3:4b non è scaricato: scaricalo in Impostazioni." |
| niente da chiedere | "Non ci sono esercenti da chiedere: ..." |

Perché 409 (Conflict) e non 400 (Bad Request)? La richiesta è giusta: è lo **stato** dell'app che adesso non la
permette. Il frontend mostra il `detail` del `ProblemDetail` così com'è.

Le API, in `AiController`:

| | |
|---|---|
| `GET /api/ai/suggestions` | `{enabled, model, toAsk, job}`: l'IA è accesa? quanti esercenti si possono chiedere? a che punto è? |
| `POST /api/ai/suggestions` | fa partire le domande: 202, oppure 409 con il motivo |

Prova a mano (backend avviato, IA accesa):

```bash
curl -k https://localhost:8443/api/ai/suggestions
curl -k -X POST https://localhost:8443/api/ai/suggestions
curl -k https://localhost:8443/api/ai/suggestions         # ripetilo: "done" sale
curl -k https://localhost:8443/api/category-suggestions   # i suggerimenti, con "model"
```

---

## Parte G: unire IA e storia (`HistorySuggester`)

`HistorySuggester.suggest` (passo 7a) ha un parametro in più, le risposte dell'IA:

```java
public record AiAnswer(String merchantKey, Long categoryId, String category, String model) {}

public static List<Suggestion> suggest(List<Example> examples, List<Candidate> candidates,
        Set<Dismissal> dismissals, List<AiAnswer> aiAnswers)
```

Per ogni esercente con movimenti senza categoria:

```java
Map<Long, Integer> chosen = choices.get(entry.getKey());
if (chosen == null) {
    // Mai scelto a mano: c'è forse una risposta dell'IA.
    AiAnswer answer = aiByMerchant.get(entry.getKey());
    if (answer != null && !dismissals.contains(new Dismissal(entry.getKey(), answer.categoryId()))) {
        suggestions.add(new Suggestion(..., 0, answer.model()));
    }
    continue;
}
if (chosen.size() != 1) {
    continue; // scelte diverse: nessun suggerimento, nemmeno dall'IA
}
```

- **la storia vince**: se hai scelto l'esercente a mano, la risposta dell'IA (magari chiesta prima) non conta
  più;
- **il "no" vale anche per l'IA**: la stessa tabella dei rifiuti (V15), la stessa coppia esercente + categoria;
- il record `Suggestion` ha un campo nuovo, `model`: null per la storia, il nome del modello per l'IA. Il
  frontend lo usa per il segno "IA";
- l'ordine: prima la storia, poi l'IA (`Comparator.comparing(s -> s.model() != null)`: `false` viene prima di
  `true`), e in ogni gruppo prima gli esercenti con più movimenti.

Il vecchio `suggest` a tre parametri resta e chiama il nuovo con `List.of()`: i test del passo 7a non sono
cambiati (tranne il `null` del campo nuovo).

In `SuggestionService.suggestions()` le risposte si leggono solo con l'IA accesa:

```java
List<AiAnswer> aiAnswers = aiService.enabled() ? aiAnswerRepository.findAllWithCategory()... : List.of();
```

---

## Parte H: il riquadro nella pagina Movimenti (Angular)

`frontend/src/app/suggestions/suggestions-panel.ts`, `.html`.

- **quando si vede**: prima solo se c'erano suggerimenti. Ora anche se c'è qualcosa da chiedere all'IA: un
  `computed` che guarda le due cose:

  ```ts
  protected readonly visible = computed(() => {
    const ai = this.ai();
    return (
      this.suggestions().length > 0 ||
      (ai !== null && ai.enabled && (ai.toAsk > 0 || ai.job?.running === true))
    );
  });
  ```

- **il polling** è quello del 7b.1 (`docs/27`, F2), ogni 2 secondi; in più, quando il lavoro **passa** da "in
  corso" a "finito" (`wasRunning`), ricarica i suggerimenti e scrive com'è andata;
- **il segno "IA"** è una scritta, non solo un colore (chi non distingue i colori lo legge lo stesso), e accanto
  c'è "controlla prima di accettare";
- **la prima risposta**: mentre `done` è 0 compare "La prima risposta può richiedere di più: Ollama carica il
  modello in memoria". L'abbiamo aggiunto dopo la prova dal vivo (Parte I2).

---

## Parte I: i test, e la prova dal vivo

### I1. I test

- `AiQuestionsTest` (4 test, puri): chi si chiede e chi no; l'ordine e il limite; la domanda (testo senza cifre e
  senza nome del titolare, categorie del gruppo, esempi); trasferimenti e investimenti esclusi;
- `HistorySuggesterTest` (5 test in più): l'IA per chi non hai mai scelto; la storia vince; scelte diverse, niente
  IA; il "no" vale anche per l'IA; prima la storia; niente suggerimento senza movimenti da sistemare;
- `AiSuggestionControllerTest` (4 test, con database vero e modello finto): IA spenta; il percorso completo
  (chiedi, risposte salvate, "non so" non mostrato, suggerimenti nell'ordine giusto, accetta, niente più da
  chiedere); spegnendo l'IA i suggerimenti spariscono; modello non scaricato;
- `suggestions-panel.spec.ts` (5 test in più): il segno "IA"; il pulsante anche senza suggerimenti; il polling con
  i timer finti fino ai suggerimenti nuovi; l'errore; IA spenta, niente pulsante.

### I2. La prova dal vivo, e che cosa ci ha insegnato

Con Ollama vero e `gemma3:4b`, su un conto con 10 movimenti: tre categorizzati a mano (Esselunga, una pizzeria,
Enel) e gli altri no. Risultato: un suggerimento dalla storia (l'altro movimento Esselunga) e 4 dall'IA:

| Esercente | Suggerimento dell'IA |
|---|---|
| CONAD SUPERSTORE (2 movimenti, con numeri diversi: un esercente solo) | Spesa alimentare |
| FARMACIA COMUNALE | Cure sanitarie e farmacia |
| ZARA ITALIA | Shopping |
| TRENITALIA | Viaggi e vacanze (discutibile: per i pendolari è "Trasporti") |
| XYZ 12345 ABC | "non so": nessun suggerimento, e non si richiede |

Il primo giro, però, è durato **5 minuti e 13 secondi** per la prima domanda, e 5 secondi per le altre. Il
container di Ollama era appena stato ricreato, e il modello si caricava da disco "a freddo". Non si è rotto
niente, ma per cinque minuti la pagina ha mostrato "0 di 5" senza spiegazioni: chiunque avrebbe pensato che
fosse bloccata. Da qui l'avviso sulla prima risposta (Parte H). Il secondo giro, con il modello già in memoria:
29 secondi in tutto.

La lezione: **le prove dal vivo trovano problemi che i test non vedono**. Nei test il modello finto risponde in un
attimo; nella realtà la prima risposta può essere lentissima.

---

## Parte J: sicurezza

| Rischio | Difesa |
|---|---|
| lavoro pesante a sorpresa | parte solo con un clic; al massimo 20 domande; un giro alla volta |
| dati personali al modello | le stesse regole del 7b.2: solo il testo dell'esercente senza cifre, mai importi, IBAN, nome del titolare |
| una categoria inventata | il modello risponde con un numero, controllato dal backend (`docs/28`); la categoria salvata è un'entità vera del database |
| un suggerimento sbagliato preso per buono | segno "IA" e "controlla prima di accettare"; niente cambia finché non accetti |
| IA spenta ma suggerimenti ancora visibili | con l'IA spenta le risposte non si mostrano |

---

## Parte K: esercizi guidati

### Esercizio 1: "chiedi di nuovo"

Se cambi le tue categorie (ne crei una nuova, "Abbonamenti streaming"), le risposte salvate restano quelle di
prima. Aggiungi `DELETE /api/ai/suggestions`, che cancella le risposte salvate, e un link "chiedi di nuovo" nel
riquadro.

<details>
<summary>Soluzione</summary>

```java
// AiSuggestionService
@Transactional
public Status forgetAnswers() {
    answerRepository.deleteAll();
    return status();
}

// AiController
@DeleteMapping("/suggestions")
public AiSuggestionService.Status forgetAnswers() {
    return suggestionService.forgetAnswers();
}
```

Attenzione a un dettaglio: se un giro è in corso, cancellare le risposte a metà lascerebbe il lavoro in uno
stato strano. Meglio rifiutare con 409 se `answerJob.state().running()`. Nel frontend: `forgetAiAnswers()` in
`WalletApi`, e dopo la risposta `this.showAi(status)`. Nel test: chiedi, cancella, e controlla che `toAsk` torni
al numero di prima.
</details>

### Esercizio 2: perché non chiedere dopo ogni sincronizzazione?

Sarebbe comodo: i movimenti nuovi arriverebbero già con un suggerimento. Pensa a che cosa cambierebbe per chi usa
l'app: memoria, tempi, prevedibilità. Che cosa servirebbe per farlo bene?

<details>
<summary>Soluzione</summary>

La sincronizzazione gira ogni giorno da sola: il modello si caricherebbe in memoria (~3 GB) anche quando l'utente
sta facendo altro, e su un portatile si sentirebbe. Per farlo bene servirebbero almeno: un'impostazione
"chiedi da solo dopo la sincronizzazione" (spenta di serie), un limite di domande, e il giro solo per gli
esercenti nuovi. È lo stesso principio della Parte A: il lavoro pesante parte quando lo decidi tu.
</details>

### Esercizio 3: la fiducia del modello

Un modello non dice "quanto è sicuro". Ma la misura del 7b.2 ti dà un numero: con `gemma3:4b` sulle tue scelte
indovina, ad esempio, l'80% delle volte. Dove lo mostreresti all'utente, accanto ai suggerimenti dell'IA, e
perché potrebbe aiutarlo?

<details>
<summary>Soluzione</summary>

Ad esempio nel testo del riquadro: "suggerita dall'IA (gemma3:4b, ha indovinato 8 volte su 10 sulle tue
scelte)". Aiuta a calibrare la fiducia: con l'80% vale la pena accettare dopo un'occhiata; con il 50% conviene
controllare ogni suggerimento. Il numero va preso dall'ultima misura (`ModelEvaluation.state()`), e solo se è
stata fatta con lo stesso modello.
</details>
