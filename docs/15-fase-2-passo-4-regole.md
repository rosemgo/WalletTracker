# 15. Fase 2, passo 4: le regole dalla dashboard

**Obiettivo:** gestire le **regole di classificazione** dalla dashboard, senza SQL:
- **vederle** in parole semplici ("inizia con «Div.su» · solo entrate → Cedole e dividendi"), con quante
  volte è stata usata ognuna;
- **crearne** di nuove, **modificarle**, **disattivarle**, **cancellarle**;
- **provarle prima di salvarle**: "questa regola corrisponderebbe a 12 movimenti, ecco i più recenti".

> Prerequisiti: passo 3 completato e `git pull` fatto. Per la parte Angular è utile la guida
> `docs/14` (capitoli 5–8).

Il piano della Fase 2 è cambiato leggermente: la **configurazione guidata** delle credenziali Enable
Banking, prevista insieme alle regole, diventa il **passo 5**. Due passi più piccoli si studiano meglio.

---

## Parte A: i concetti

### 1. Le regole, ripasso

Una regola dice: *"se il testo del movimento (causale + controparte) **contiene** / **inizia con**
queste parole, ed eventualmente è un'**uscita** / un'**entrata**, allora il tipo è X e la categoria è Y"*.

Come si confronta il testo:

- maiuscole e minuscole non contano;
- **nemmeno il numero di spazi**: molte banche scrivono i pagamenti con carta in campi a larghezza fissa,
  riempiti di spazi (`MAR GROUP SPA   FOGLIANISE    ITA`), ma il browser ne mostra uno solo. Una regola
  scritta copiando quello che si vede (`MAR GROUP SPA FOGLIANISE`) deve trovare il movimento. Anche
  tabulazioni, a capo e spazi "non separabili" contano come un solo spazio (`ClassificationRule.normalizeSpaces`);
- le modalità sono tre:

  | Modalità | Vuol dire | `MAR GROUP SPA FOGLIANISE ITA` è trovato da... |
  |---|---|---|
  | **contiene** | queste parole, in quest'ordine e una dopo l'altra (anche pezzi di parola) | `GROUP SPA`, `FOGLIAN`; **non** da `MAR GROUP FOGLIANISE` (in mezzo c'è `SPA`) |
  | **contiene tutte le parole** | tutte queste parole, **intere**, in qualsiasi ordine, anche con altre in mezzo | `MAR GROUP FOGLIANISE`, `foglianise mar`; **non** da `FOGLIAN` (non è una parola intera) |
  | **inizia con** | il testo comincia con queste parole | `MAR GROUP` |

  Per "contiene tutte le parole" una parola è un gruppo di lettere e cifre: punteggiatura e simboli
  separano (`PAYPAL *TEMU` ha le parole `paypal` e `temu`). Le parole intere evitano le sorprese: `SPA`
  trova `MAR GROUP SPA`, ma non `SPAZIO GIOCHI`. Una regola senza nessuna parola (es. `***`) viene
  rifiutata: "tutte le parole di un elenco vuoto" sarebbe vero per qualsiasi movimento.

Nel motore di classificazione (`docs/03`, sezione 4) le regole sono il **sesto** controllo. Prima vengono:

1. conto escluso;
2. correzione manuale;
3. importo zero;
4. trasferimento abbinato;
5. IBAN di un tuo conto nel testo.

Quindi una regola **non** cambia un movimento che hai corretto a mano, né un trasferimento tra i tuoi conti.

Le regole si provano in ordine di **priorità** (il numero più basso per primo), e **la prima che
corrisponde vince**. Le regole di partenza hanno priorità da 10 a 50; quelle nuove partono da **100**.
Se vuoi che una tua regola venga provata prima di una di partenza, dalle un numero più basso.

| | Regole di partenza | Regole tue |
|---|---|---|
| chi le crea | la migrazione `V4` (le trovi segnate "regola di partenza") | tu, dalla dashboard |
| modificare | sì | sì |
| disattivare | sì | sì |
| cancellare | **no**: si disattivano (così puoi sempre riattivarle) | sì |

### 2. Il CRUD di una risorsa REST

Le API delle regole sono il classico **CRUD** (*Create, Read, Update, Delete*):

| Operazione | Richiesta | Risposta |
|---|---|---|
| elenco | `GET /api/rules` | `200 OK` + l'elenco |
| crea | `POST /api/rules` + JSON della regola | **`201 Created`** + la regola creata |
| modifica | `PUT /api/rules/{id}` + JSON della regola | `200 OK` + la regola aggiornata |
| cancella | `DELETE /api/rules/{id}` | **`204 No Content`** (nessun corpo) |
| prova | `POST /api/rules/preview` + JSON della regola | `200 OK` + quanti movimenti corrispondono ed esempi |

Due codici nuovi: **201** ("creato") e **204** ("fatto, e non c'è niente da restituire"). Sono i codici
che un client si aspetta da un'API REST ben fatta.

### 3. Validare due volte: nel browser e nel backend

I vincoli di una regola (nome obbligatorio, testo di almeno 2 caratteri, priorità tra 0 e 10000...) sono
controllati **due volte**:

- **nel browser** (Angular): per avvisare **subito** l'utente, con un messaggio sotto il campo, senza
  aspettare il server;
- **nel backend** (Bean Validation): perché il backend **non deve mai fidarsi** del client. Chiunque può
  chiamare le API senza passare dalla dashboard, ad esempio con `Invoke-RestMethod`.

La regola d'oro: **la validazione nel frontend è per la comodità, quella nel backend è per la sicurezza.**

---

## Parte B: il backend

### `ClassificationRule`: due metodi nuovi

```java
public void update(String name, int priority, boolean enabled, MatchMode matchMode, String pattern,
        AmountSign amountSign, TransactionType resultType, String category) { ... }

public void setEnabled(boolean enabled) { ... }
```

Più i getter mancanti (`getMatchMode`, `getPattern`, `getAmountSign`), che servono per mostrare la regola.

### `RuleRequest`: i dati in arrivo, con i vincoli

```java
public record RuleRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Min(0) @Max(10_000) Integer priority,
        boolean enabled,
        @NotNull MatchMode matchMode,
        @NotBlank @Size(min = 2, max = 200) String pattern,
        @NotNull AmountSign amountSign,
        @NotNull TransactionType resultType,
        @Size(max = 100) String category) { ... }
```

Le annotazioni di **Bean Validation** descrivono i vincoli. Con `@Valid` nel controller, Spring li
controlla prima di chiamare il metodo e, se uno non è rispettato, risponde `400 Bad Request`. Perché
almeno 2 caratteri nel testo? Una regola che "contiene «a»" corrisponderebbe a quasi tutti i movimenti.

`priority` è un `Integer` e non un `int`: con `int`, un campo mancante nel JSON diventerebbe `0` in
silenzio; con `Integer` diventa `null`, e `@NotNull` lo rifiuta.

### `RuleService`: la logica

| Metodo | Cosa fa |
|---|---|
| `rules()` | tutte le regole in ordine di priorità, con quante volte è stata usata ognuna |
| `create(request)` | salva la regola e riclassifica tutto |
| `update(id, request)` | modifica e riclassifica |
| `delete(id)` | rifiuta le regole di partenza (`400`), altrimenti cancella e riclassifica |
| `preview(request)` | prova la regola **senza salvarla** |

**Quante volte è stata usata una regola?** Ogni movimento ricorda quale regola lo ha classificato (colonna
`rule_id`). Basta contarli, raggruppando per regola:

```java
@Query("SELECT t.ruleId AS ruleId, COUNT(t) AS uses FROM BankTransaction t WHERE t.ruleId IS NOT NULL GROUP BY t.ruleId")
List<RuleUsage> countUsesByRule();

interface RuleUsage {
    Long getRuleId();
    long getUses();
}
```

È una **proiezione**: invece di entità intere, la query restituisce solo due colonne, e Spring Data
riempie l'interfaccia `RuleUsage` abbinando i nomi (`AS ruleId` → `getRuleId()`). Non scriviamo
nessuna classe che la implementa: la crea Spring, come per i repository.

**Perché `flush()` prima di `classifyAll()`?**

```java
ruleRepository.delete(rule);
ruleRepository.flush();
classificationService.classifyAll();
```

Dentro una transazione, Hibernate **rimanda** le scritture nel database: le tiene in memoria e le esegue
tutte insieme alla fine. Ma `classifyAll` rilegge le regole attive con una query: senza `flush()`
potrebbe trovare ancora la regola appena cancellata. `flush()` dice: "scrivi adesso nel database quello
che hai in sospeso" (la transazione resta aperta, e un errore annullerebbe comunque tutto).

**La prova (`preview`)** crea una `ClassificationRule` **in memoria**, senza salvarla, e la prova su
tutti i movimenti con lo stesso metodo `matches(...)` usato dal motore. Restituisce quanti movimenti
corrispondono e i 5 più recenti. Attenzione: è una prova del **solo testo**. Nella classificazione vera
alcuni di quei movimenti potrebbero essere decisi prima da un altro controllo (una correzione manuale, un
trasferimento, una regola con priorità più alta).

### `RuleController`

```java
@PostMapping
@ResponseStatus(HttpStatus.CREATED)          // 201 invece di 200
public RuleDto create(@Valid @RequestBody RuleRequest request) { ... }

@DeleteMapping("/{id}")
@ResponseStatus(HttpStatus.NO_CONTENT)       // 204, e il metodo è void
public void delete(@PathVariable Long id) { ... }
```

### I test: `RuleControllerTest` (6)

| Test | Cosa verifica |
|---|---|
| `listsTheStarterRulesWithHowManyTimesTheyWereUsed` | elenco con utilizzi (JsonPath con filtro: `$[?(@.name == 'Dividendo')].uses`) |
| `aNewRuleIsCreatedAndAppliedToTheExistingTransactions` | `201`, la regola vale subito per i movimenti esistenti |
| `disablingARuleReclassifies` | disattivando "Dividendo", il dividendo diventa un'entrata normale |
| `userRulesCanBeDeletedButStarterRulesCannot` | `400` per le regole di partenza, `204` per le tue |
| `aPreviewShowsTheMatchingTransactionsWithoutSaving` | la prova non salva nulla |
| `anIncompleteRuleIsRejected` | nome vuoto e testo di un carattere: `400` |

---

## Parte C: il frontend

### La pagina `/regole`

`src/app/rules/rules-page.ts`, `.html`, `.spec.ts`. In alto il **modulo** ("Nuova regola" o "Modifica la
regola"), sotto la **tabella** delle regole.

### I Reactive Forms

Per i moduli Angular offre i **Reactive Forms**: il modulo è descritto **nella classe**, con i suoi campi e
le regole di validazione, e il template si limita a **collegare** ogni campo HTML al suo controllo.

```ts
private readonly fb = inject(NonNullableFormBuilder);

protected readonly form = this.fb.group({
  name: ['', [Validators.required, Validators.maxLength(100)]],
  pattern: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(200)]],
  matchMode: ['CONTAINS'],
  ...
  priority: [100, [Validators.required, Validators.min(0), Validators.max(10000)]],
  enabled: [true],
});
```

- `fb.group({...})` crea un **`FormGroup`**: un modulo, con un **`FormControl`** per ogni campo;
- per ogni campo: `[valore iniziale, [validatori]]`;
- `Validators.required`, `minLength`, `min`... sono i validatori di Angular. Gli stessi vincoli di
  `RuleRequest.java`, ripetuti qui per avvisare subito;
- `NonNullableFormBuilder`: con `reset()` i campi tornano al valore iniziale invece che a `null`.

Nel template:

```html
<form [formGroup]="form" (ngSubmit)="save()">
  <input formControlName="name" />
  <select formControlName="matchMode"> ... </select>
  <input type="number" formControlName="priority" />
  <input type="checkbox" formControlName="enabled" />
  <button type="submit">Salva</button>
</form>
```

- `[formGroup]="form"` collega il `<form>` al modulo della classe;
- `formControlName="name"` collega un campo al suo controllo. Funziona **nei due sensi**: scrivendo nel
  campo cambia il valore del controllo, e cambiando il controllo (es. con `setValue`) cambia il campo;
- `(ngSubmit)` scatta premendo "Salva" o Invio;
- per usare queste direttive il componente importa **`ReactiveFormsModule`**.

**Le operazioni sul modulo che usiamo:**

| Codice | Cosa fa |
|---|---|
| `this.form.invalid` | `true` se almeno un campo non rispetta i validatori |
| `this.form.markAllAsTouched()` | segna tutti i campi come "toccati", così compaiono i messaggi di errore |
| `this.form.getRawValue()` | i valori di tutti i campi, come oggetto: è proprio il `RuleRequest` da mandare |
| `this.form.setValue({...})` | riempie tutti i campi (per "Modifica") |
| `this.form.reset(NEW_RULE)` | riporta il modulo ai valori iniziali |
| `control.invalid && control.touched` | mostrare l'errore di un campo? Solo dopo che l'utente ci è passato |

**I messaggi di errore**, nel template:

```html
@if (showError('name')) {
  <span class="error">Scrivi un nome (massimo 100 caratteri).</span>
}
```

**E le classi CSS automatiche.** Angular aggiunge da solo ai campi delle classi che descrivono il loro
stato: `ng-valid`/`ng-invalid`, `ng-touched`/`ng-untouched`, `ng-dirty`/`ng-pristine` (modificato o no).
In `styles.css` le usiamo per colorare di rosso il bordo di un campo non valido:

```css
input.ng-invalid.ng-touched {
  border-color: var(--negative);
}
```

**Reactive Forms o signals?** Per lo **stato della pagina** (l'elenco, i messaggi, la regola in modifica)
usiamo i signals, come nelle altre pagine. Per il **modulo** usiamo i Reactive Forms, lo strumento di
Angular pensato proprio per i moduli: valori, validazione, stato di ogni campo.

### Salvare: crea o modifica?

```ts
save(): void {
  if (this.form.invalid) {
    this.form.markAllAsTouched();
    return;
  }
  const request = this.form.getRawValue();
  const editing = this.editing();
  const saved$ = editing ? this.api.updateRule(editing.id, request) : this.api.createRule(request);
  saved$.subscribe({ ... });
}
```

Lo stesso modulo serve per creare e per modificare: il signal `editing` dice quale delle due. Il `$` alla
fine di `saved$` è una **convenzione** (non una regola del linguaggio) per i nomi degli Observable.

### La casella "Attiva": la destrutturazione

```ts
toggle(rule: Rule): void {
  const { id, builtin, uses, ...request } = rule;
  this.api.updateRule(id, { ...request, category: rule.category ?? '', enabled: !rule.enabled }).subscribe(...);
}
```

- `const { id, builtin, uses, ...request } = rule;` è la **destrutturazione**: crea le variabili `id`,
  `builtin` e `uses` con quei campi di `rule`, e mette **tutti gli altri** campi in un nuovo oggetto
  `request`. Così otteniamo i dati da rimandare al backend, senza i campi calcolati (`builtin`, `uses`);
- `{ ...request, enabled: !rule.enabled }` è lo **spread**: una copia di `request` con `enabled`
  sostituito. In Java servirebbe un costruttore con tutti i campi.

### I tipi: `Omit`

```ts
export interface Rule extends Omit<RuleRequest, 'category'> {
  id: number;
  category: string | null;
  builtin: boolean;
  uses: number;
}
```

`Omit<RuleRequest, 'category'>` vuol dire "tutti i campi di `RuleRequest` **tranne** `category`". Una
regola salvata ha gli stessi campi di quella inviata, più `id`, `builtin` e `uses`. In più `category` può
essere `null` (nel modulo invece è una stringa, eventualmente vuota).

### I test: `rules-page.spec.ts` (5)

| Test | Cosa verifica |
|---|---|
| *lists the rules in plain language* | "inizia con «Div.su»", "solo entrate", "Cedole e dividendi" |
| *saves a new rule with the values typed in the form* | i valori scritti nel modulo arrivano interi a `createRule` |
| *does not save or try an incomplete rule...* | un modulo vuoto non viene salvato e compare "Scrivi un nome" |
| *"Modifica" fills the form...* | il modulo si riempie, e il salvataggio aggiorna la stessa regola |
| *"Prova" shows how many transactions...* | il risultato della prova |

Per scrivere in un campo, il test fa quello che fa il browser: cambia il valore e lancia l'evento
`input`, che il Reactive Form ascolta:

```ts
input.value = 'ESSELUNGA';
input.dispatchEvent(new Event('input'));
```

---

## Parte D: cosa fare

1. **Aggiorna e lancia i test:**
   ```powershell
   git pull
   cd backend
   .\mvnw.cmd test               # 69 test
   cd ..\frontend
   npm test -- --watch=false     # 18 test
   ```
2. **Avvia** backend e dashboard, e apri **Regole** (<https://localhost:8443/regole>).
3. **Guarda le regole di partenza**, e la colonna "Usata": quali classificano davvero i tuoi movimenti?
4. **Crea una regola tua.** Cerca in **Movimenti** un esercente che compare spesso (il supermercato, il
   carburante, un abbonamento), poi in **Regole**:
   - Nome: es. "Supermercato"; *Se il testo* **contiene** «ESSELUNGA» (o il tuo); *per* **solo uscite**;
     *allora il tipo è* **Spesa**; Categoria: "Spesa alimentare";
   - premi **Prova**: quanti movimenti corrispondono? Sono quelli giusti?
   - premi **Salva**, poi guarda i movimenti: sotto la descrizione compare la categoria.
5. **Prova la priorità.** Crea una regola "contiene «PAYPAL» → Spesa, categoria Acquisti online" e una
   "contiene «PAYPAL *TEMU» → Spesa, categoria Temu". Con la stessa priorità vince la prima creata;
   abbassa la priorità della seconda (es. 90) per farla vincere sui movimenti Temu.
6. **Disattiva e riattiva** una regola di partenza dalla casella "Attiva", e guarda come cambiano i
   movimenti.

> Le **categorie** per ora sono un testo libero. Nella Fase 3 diventeranno un elenco (per i grafici e i
> budget), e i rimborsi ridurranno la spesa della loro categoria.

## Cosa hai imparato

- Il **CRUD** di una risorsa REST, con i codici **201** e **204**.
- **Bean Validation** sui record e perché si valida **sia** nel frontend **sia** nel backend.
- Le **proiezioni** di Spring Data e `flush()` dentro una transazione.
- I **Reactive Forms**: `FormGroup`, `FormControl`, `Validators`, `formControlName`, `ngSubmit`, gli stati
  `touched`/`invalid` e le classi CSS automatiche.
- In TypeScript: **destrutturazione**, **spread**, `Omit`.

## Prossimo passo

**Fase 2, passo 5:** la **configurazione guidata** di Enable Banking dalla dashboard: inserire
l'Application ID e caricare il file `.pem`, verificarli e salvarli, senza toccare il file `.env`.
