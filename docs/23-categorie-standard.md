# 23. Fase 3, passo 5b: l'elenco delle categorie

**Obiettivo:** le categorie non sono più testo libero, ma un **elenco** con un menu a discesa, come nelle app
delle banche. WalletTracker parte da un elenco pronto (Spesa alimentare, Bar e ristoranti, Trasporti e
auto...) che ognuno può **rinominare, ampliare e accorciare** dalla nuova pagina **Categorie**.

- Nella pagina **Movimenti**, "modifica" apre un menu: per primo il gruppo di categorie che si addice al
  movimento, poi gli altri.
- Nella pagina **Regole**, il campo Categoria è un menu, ordinato secondo il tipo scelto nella regola.
- Nella pagina **Budget**, il menu mostra solo le categorie di spesa.
- **Rinominare** una categoria aggiorna da solo movimenti, regole e budget.
- Le categorie che avevi già scritto **restano**: la migrazione le aggiunge all'elenco.

> Prerequisiti: passo 5 completato e `git pull` fatto. Al primo avvio Flyway applica la migrazione
> **V12**.

---

> **Aggiornamento (`docs/24`).** Movimenti, regole e budget ora salvano l'**id** della categoria, non più il
> nome: è la soluzione "da manuale" descritta nella sezione 4 come alternativa. Le parti di questa guida su
> `ON UPDATE CASCADE`, sulle chiavi esterne sul nome e su `resolve(...)` raccontano la versione V12; il
> resto (l'elenco, i gruppi, i menu, la migrazione delle categorie già scritte) vale ancora.

## Parte A: i concetti

### 1. Perché un elenco

Con il testo libero (passo 2) bastava una lettera diversa per avere due categorie: "Spesa", "spesa" e
"Spesa alimentare" erano tre righe diverse nelle "Spese per categoria", e un budget su una non vedeva le
altre. I suggerimenti mentre scrivi aiutavano, ma non impedivano l'errore.

Con un elenco:
- **non si sbaglia**: si sceglie, non si scrive;
- **si parte subito**: chi installa WalletTracker trova le categorie pronte, senza doverle inventare;
- **si adatta**: l'elenco sta nel database, non nel codice. Come le regole di partenza, è un punto di
  partenza che ognuno cambia (il principio di `docs/03`: motore uguale per tutti, configurazione di
  ognuno).

### 2. Tipo, categoria e gruppo: tre cose diverse

| | Domanda | Esempi | A che cosa serve |
|---|---|---|---|
| **tipo** | *che cos'è* il movimento? | Spesa, Entrata, Trasferimento interno, Cedole e dividendi | decide **se** conta nel riepilogo e nei budget |
| **categoria** | *per che cosa* sono i soldi? | Spesa alimentare, Bar e ristoranti, Stipendio e pensione | decide **dove** compare tra le spese |
| **gruppo** (della categoria) | a quali movimenti si addice la categoria? | Spese, Entrate, Investimenti, Altro | **ordina** i menu: per prime le categorie giuste |

Un esempio: la spesa all'Esselunga ha **tipo** Spesa e **categoria** Spesa alimentare. La categoria
Spesa alimentare sta nel **gruppo** Spese. Quando correggi la categoria di quel movimento, il menu mostra
per prime le "Categorie di spesa". Per uno stipendio mostrerebbe per prime le "Categorie di entrata".

Il gruppo **non vieta niente**: le altre categorie sono comunque nel menu, più in basso. Il collegamento
tra tipo e gruppo è un piccolo calcolo, uguale nel backend (`CategoryKind.of`) e nel frontend
(`kindOf`):

| Tipo del movimento | Gruppo messo per primo |
|---|---|
| Spesa (anche "Rimborso di una spesa") | Categorie di spesa |
| Entrata | Categorie di entrata |
| Cedole e dividendi, Tasse investimenti, Acquisto/Vendita titoli, Versamento/Prelievo investimenti | Categorie di investimento |
| tutto il resto (trasferimenti, saldo carta, ignorati, da verificare) | Altre categorie |

### 3. L'elenco di partenza

È partito dalla lista delle categorie di N26. Abbiamo **unito i doppioni** e **lasciato fuori ciò che è già
un tipo**:

| Dalla lista proposta | Che cosa è diventato | Perché |
|---|---|---|
| "Entrate / Stipendio" e "Stipendio" | **Stipendio e pensione** | erano la stessa cosa |
| "Istruzione e lavoro" ed "Educazione" | **Istruzione e formazione** (e **Spese di lavoro** a parte) | un corso e un pranzo di lavoro sono cose diverse |
| "Cash (Prelievi ATM)" | **Contanti e prelievi** | |
| "Spese aziendali" | **Spese di lavoro** | vale anche per chi non ha un'azienda |
| "Risparmio e investimenti" | **nessuna categoria**: è il **tipo** "Versamento investimenti" | come categoria avrebbe creato proprio il conflitto da evitare: un versamento su Fineco non è una spesa, e il tipo lo sa già |
| — | **Costi bancari**, **Altre spese**, **Altre entrate**, ... | per non lasciare movimenti senza un posto |
| — | **Cedole, Dividendi, Ritenute, ...** | le usavano già le regole di partenza degli investimenti |

L'elenco completo è nella migrazione `V12__categorie.sql` e nella pagina Categorie.

**I contanti.** Un prelievo al bancomat è una spesa? Tecnicamente i soldi passano dal conto al
portafoglio. Ma poi li spendi, e WalletTracker quelle spese non le vede. Per questo, come fanno le app
delle banche, il prelievo è una **spesa** con categoria "Contanti e prelievi". Se preferisci non contarlo,
correggi il tipo in "Ignorato" o crea una regola.

### 4. L'integrità la garantisce il database

Movimenti, regole e budget salvano il **nome** della categoria, come prima. La novità è una **chiave
esterna** (*foreign key*) sul nome:

```sql
ALTER TABLE bank_transaction ADD CONSTRAINT fk_transaction_category
    FOREIGN KEY (category) REFERENCES category (name) ON UPDATE CASCADE ON DELETE SET NULL;
```

Si legge così:
- **`FOREIGN KEY ... REFERENCES category (name)`**: la categoria di un movimento **deve** essere un nome
  della tabella `category`. Il database rifiuta qualsiasi altro valore, anche se il codice Java sbagliasse.
  Lo abbiamo visto subito: i test che usavano categorie inventate ("A", "Viaggi") hanno smesso di
  funzionare finché non li abbiamo corretti;
- **`ON UPDATE CASCADE`**: se il nome della categoria cambia, il database lo cambia **da solo** in tutte le
  righe che lo usano. Rinominare "Shopping" in "Acquisti" è un solo `UPDATE category`, e movimenti,
  regole e budget seguono. Nel codice Java non c'è una riga per farlo: un test lo verifica;
- **`ON DELETE SET NULL`**: eliminando una categoria, i movimenti e le regole che la usavano restano
  **senza categoria**;
- per i **budget** non c'è `ON DELETE`: il database **rifiuta** di eliminare una categoria che ha un
  budget. Il backend lo controlla prima, per rispondere con un messaggio chiaro (409: "C'è un budget per
  ...: eliminalo prima dalla pagina Budget").

**Due indici unici** sul nome: `UNIQUE (name)` serve alla chiave esterna; `UNIQUE (lower(name))` impedisce
i quasi-doppioni "Spesa" e "spesa", perché confronta i nomi tutti in minuscolo.

**Perché il nome e non l'id?** La soluzione "da manuale" sarebbe salvare l'**id** della categoria
(`category_id`) in movimenti, regole e budget. Funzionerebbe altrettanto bene, ma richiedeva di cambiare
mezzo progetto (entità, query, API, frontend) per lo stesso risultato. Con una chiave esterna sul nome e
`ON UPDATE CASCADE` otteniamo la stessa integrità con una migrazione e poco codice. È un compromesso
consapevole: in un database molto grande un nome ripetuto in milioni di righe occupa più spazio di un
numero, ma per un'app personale non conta.

### 5. Le categorie che avevi già scritto

La migrazione V12 non perde niente:
1. aggiunge l'elenco di partenza;
2. aggiunge all'elenco **ogni categoria già usata** in regole, movimenti e budget che non c'è. Il gruppo
   viene dal tipo della regola che la usa (una categoria di una regola di entrate diventa "di entrata"),
   altrimenti è una spesa. Tra "Viaggi" e "viaggi" ne tiene una sola, quella con l'iniziale maiuscola;
3. sistema le maiuscole: "spesa alimentare" scritta a mano diventa "Spesa alimentare" dell'elenco;
4. solo alla fine crea le chiavi esterne. A quel punto ogni categoria usata è nell'elenco, quindi nessun
   vincolo può fallire.

Questa migrazione l'abbiamo provata su un database "vecchio" (alla V11) riempito con categorie scritte
in tutti i modi: minuscole, doppioni, nomi non presenti nell'elenco. Tutto è stato conservato.

Dopo la migrazione, nella pagina Categorie trovi in fondo ai gruppi le tue categorie (senza "di
partenza"): puoi rinominarle, spostarle di gruppo o eliminarle.

---

## Parte B: il backend

| File | Cosa fa |
|---|---|
| `db/migration/V12__categorie.sql` | la tabella, l'elenco di partenza, il recupero delle categorie già usate, le chiavi esterne |
| `category/Category.java`, `CategoryKind.java`, `CategoryRepository.java` | l'entità, i gruppi, l'accesso alla tabella |
| `category/CategoryService.java` | elenco con gli utilizzi, aggiunta, rinomina, eliminazione, `resolve(...)` |
| `category/CategoryController.java`, `CategoryDtos.java` | `GET/POST /api/categories`, `PUT/DELETE /api/categories/{id}` |
| `TransactionService`, `RuleService`, `BudgetService` | usano `resolve(...)` prima di salvare una categoria |
| `classification/CategoryController.java` | **eliminato**: era l'elenco "testo libero" del passo 2 |

### `resolve`: il nome ufficiale

```java
public String resolve(String name)   // "spesa alimentare" → "Spesa alimentare"
```

Movimenti, regole e budget passano da qui prima di salvare. Cerca la categoria senza badare alle
maiuscole (`findByNameIgnoreCase`) e restituisce il nome **dell'elenco**. Se non c'è risponde **400** con
un messaggio che dice dove aggiungerla. Senza questo controllo, una categoria sconosciuta arriverebbe
fino al database, che la rifiuterebbe con un errore 500 incomprensibile. **Il controllo nel codice dà il
messaggio chiaro, il vincolo nel database è l'ultima difesa**: la stessa coppia vista per i budget doppi.

### `flush()` dopo la rinomina

```java
category.update(name, request.kind());
categoryRepository.flush(); // manda subito l'UPDATE: il CASCADE nel database parte adesso
```

Hibernate di solito aspetta la fine della transazione per mandare gli `UPDATE` al database. Qui subito
dopo rileggiamo l'elenco con gli utilizzi, che contano i movimenti per nome: senza `flush()`
conterebbero ancora il nome vecchio.

### I test

| Test | Cosa verifica |
|---|---|
| `CategoryControllerTest.theStarterListIsGroupedAndOrdered` | l'elenco di partenza, per gruppo e nell'ordine dei menu |
| `aCategoryIsAddedOnceIgnoringCase` | "Animali domestici" sì, "animali DOMESTICI" → 409; nome vuoto o gruppo sconosciuto → 400 |
| `renamingACategoryUpdatesMovementsRulesAndBudgetsByItself` | la rinomina arriva da sola in movimento, regola e budget (`ON UPDATE CASCADE`) |
| `deletingACategoryLeavesMovementsWithoutItButNotIfItHasABudget` | 409 se c'è un budget; senza budget, il movimento resta senza categoria (`ON DELETE SET NULL`) |
| test dei passi precedenti | ora usano le categorie dell'elenco; in più una categoria inventata riceve 400 (movimenti, budget) |

**Una lezione dai test.** Nella prima versione il test della rinomina rinominava "Shopping", una categoria
di partenza, e non la rimetteva a posto. I test successivi che usavano "Shopping" fallivano, ma solo se
giravano **dopo** quello. Ora il test crea una sua categoria. Regola generale: **un test non deve cambiare
i dati che usano gli altri**, oppure deve rimetterli com'erano.

---

## Parte C: il frontend

| File | Cosa fa |
|---|---|
| `api/category-groups.ts` | i nomi dei gruppi, `kindOf(tipo)`, `categoryGroups(...)` per i menu |
| `categories/categories-page.*` | la pagina Categorie |
| `transactions/transactions-page.*` | "modifica" apre un menu; sceglierne una voce salva subito |
| `rules/rules-page.*` | il campo Categoria è un menu, ordinato dal tipo scelto |
| `budget/budget-page.*` | il menu mostra solo le categorie di spesa |
| `api/models.ts`, `api/wallet-api.ts` | `Category`, e le API che restituiscono oggetti invece di semplici nomi |

### `<optgroup>`: i gruppi nel menu

```html
<select>
  <option value="">— nessuna categoria —</option>
  <optgroup label="Categorie di spesa">
    <option value="Spesa alimentare">Spesa alimentare</option>
    ...
  </optgroup>
  <optgroup label="Categorie di entrata"> ... </optgroup>
</select>
```

`<optgroup>` è un elemento HTML standard: il browser mostra il titolo del gruppo, non selezionabile, con
le sue voci sotto. L'ordine dei gruppi lo decide `categoryGroups(categorie, gruppoDaMettereInCima)`.

### Un signal che segue un campo del modulo: `toSignal`

Nella pagina Regole il menu delle categorie si riordina quando cambi il tipo ("allora il tipo è..."). Il
tipo sta in un Reactive Form, che avvisa dei cambiamenti con un Observable (`valueChanges`). Per usarlo in
un `computed` lo trasformiamo in un signal:

```ts
private readonly resultType = toSignal(this.form.controls.resultType.valueChanges, {
  initialValue: this.form.controls.resultType.value,
});

protected readonly categoryGroups = computed(() =>
  categoryGroups(this.categories(), kindOf(this.resultType())),
);
```

`toSignal` è il ponte tra i due mondi di Angular: gli **Observable** (RxJS, il modo "vecchio" e ancora
usato da HttpClient e dai moduli) e i **signal** (il modo nuovo).

### TypeScript ha trovato i test da aggiornare

L'API `categories()` prima restituiva `string[]`, ora `Category[]`. Appena cambiato il tipo, la
compilazione dei test si è fermata indicando le tre righe con le categorie finte ancora scritte come
stringhe. Senza TypeScript lo avremmo scoperto solo eseguendo i test, o peggio, usando l'app.

### I test

| Test | Cosa verifica |
|---|---|
| `category groups` (2) | gruppo giusto in cima, gruppi vuoti nascosti; `kindOf` come nel backend |
| `CategoriesPage` (4) | elenco per gruppo con gli utilizzi; aggiunta; rinomina che mantiene il gruppo; conferma prima di eliminare e messaggio del backend se c'è un budget |
| `TransactionsPage` | il menu parte dalla categoria attuale, gruppi nell'ordine giusto, scegliere salva, "nessuna" toglie la scelta |
| `BudgetPage` | solo le categorie di spesa nel menu |

---

## Parte D: provalo

1. `git pull`, riavvia il backend (Flyway applica la V12) e la dashboard.
2. Apri **Categorie**: in fondo ai gruppi trovi anche le categorie che avevi scritto tu. Rinominale o
   eliminale come preferisci. Ad esempio, se avevi "Abbonamenti", puoi tenerla o rinominarla.
3. **Movimenti**: premi "modifica" su una spesa e poi su un'entrata, e guarda l'ordine dei gruppi nel
   menu.
4. Rinomina una categoria usata da un budget e apri la pagina **Budget**: il budget ha già il nome nuovo.

Test:

```
cd backend;  .\mvnw.cmd test            # 124 test
cd frontend; npm test -- --watch=false  # 63 test
```

### Esercizi

1. Aggiungi all'elenco di partenza una categoria "Animali domestici". Dove la scrivi? Attenzione: la V12 è
   già stata applicata, e **una migrazione applicata non si modifica mai**. Che cosa devi creare?
2. Prova a eliminare dalla pagina Categorie una categoria con un budget. Poi leggi in `CategoryService`
   chi decide il messaggio, e nella V12 quale vincolo impedirebbe comunque l'eliminazione.
3. In `psql`, prova `UPDATE category SET name = 'Spesa' WHERE name = 'Spesa alimentare';` e poi guarda
   `SELECT DISTINCT category FROM bank_transaction;`. Chi ha cambiato i movimenti?
