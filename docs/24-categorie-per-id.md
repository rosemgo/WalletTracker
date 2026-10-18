# 24. Le categorie collegate per id (la soluzione "da manuale")

**Obiettivo:** movimenti, regole e budget non salvano più il **nome** della categoria ma il suo **id**. Il
nome sta in un posto solo, la tabella `category`. Per chi usa la dashboard non cambia niente: stessi menu,
stesse pagine. Cambia come sono collegati i dati.

> Prerequisiti: passo 5b (`docs/23`) e `git pull`. Al primo avvio Flyway applica la migrazione **V13**.

---

## Parte A: i concetti

### 1. Prima e dopo

**Prima (V12):** il nome ripetuto in ogni riga, tenuto allineato dal database.

```
category                      bank_transaction
id | name                     id  | amount | category
 8 | Shopping                 102 | -18,00 | Shopping      ← il nome, ripetuto
```

**Dopo (V13):** solo il numero; il nome si legge dalla tabella `category`.

```
category                      bank_transaction
id | name                     id  | amount | category_id
 8 | Shopping                 102 | -18,00 | 8             ← il riferimento
```

| | Nome (V12) | Id (V13) |
|---|---|---|
| dove sta il nome | in ogni movimento, regola, budget | **solo** in `category` |
| rinominare una categoria | il database aggiorna tutte le righe che la usano (`ON UPDATE CASCADE`) | cambia **una riga**: l'id non cambia |
| spazio occupato | un testo in ogni riga | un numero (8 byte) |
| un riferimento esterno (backup, altra app) dopo una rinomina | si rompe | resta valido |
| per mostrare il nome | è già lì | serve un `JOIN` con `category` |

È la **normalizzazione**: ogni informazione in un solo posto. Il prezzo è l'ultima riga della tabella:
per mostrare un nome bisogna "unire" due tabelle. Vediamo come lo facciamo senza rallentare niente.

### 2. In Java: `@ManyToOne`

Il campo di un movimento non è più una `String` ma un oggetto `Category`:

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "category_id")
private Category category;
```

- **`@ManyToOne`**: molti movimenti possono avere la stessa categoria. Hibernate sa che nella tabella c'è
  la colonna `category_id` (`@JoinColumn`) e che quel numero è l'id di una riga di `category`.
- **`FetchType.LAZY`** ("pigro"): quando Hibernate carica un movimento, la categoria **non** la legge
  subito. Al suo posto mette un segnaposto (un *proxy*) che conosce solo l'id. La legge davvero solo se
  qualcuno ne chiede il nome (`getName()`).

Perché pigro? Il contrario, `EAGER`, legge sempre anche la categoria. Sembra comodo, ma in una lista di
mille movimenti Hibernate farebbe una query in più per ogni categoria diversa: è il **problema N+1**
(`docs/09`), e sarebbe nascosto, perché nel codice non si vede.

### 3. Caricare la categoria quando serve: `JOIN FETCH` e `@EntityGraph`

"Pigro" ha un rischio opposto: se una pagina mostra cinquanta movimenti, ognuno con il nome della
categoria, i segnaposto si "svegliano" uno per uno, e si torna all'N+1. La regola è: **dove il nome serve
per tanti oggetti insieme, si carica nella stessa query**. Due modi:

**`JOIN FETCH`**, nelle query scritte a mano (l'elenco dei movimenti):

```java
@Query(value = """
        SELECT t FROM BankTransaction t
        LEFT JOIN FETCH t.category
        LEFT JOIN FETCH t.manualCategory
        WHERE ...
        """,
        countQuery = """
        SELECT COUNT(t) FROM BankTransaction t WHERE ...
        """)
Page<BankTransaction> search(...);
```

- `JOIN FETCH` dice: "prendi i movimenti e, nella stessa query SQL, anche le loro categorie";
- `LEFT`: anche i movimenti **senza** categoria. Con un `JOIN` normale sparirebbero dall'elenco;
- `countQuery`: per le pagine ("Pagina 1 di 5") Spring Data fa anche una query di conteggio. La saprebbe
  ricavare da sola dalla query principale, togliendo i `FETCH`. L'abbiamo provato: senza `countQuery` i
  test passano lo stesso. Ma resterebbero i `LEFT JOIN`, inutili per contare. Scritta da noi è più
  semplice, e si legge che cosa fa.

  (In una prima versione di questa guida c'era scritto che Spring Data "non ci riesce". Era falso: prima
  di scriverlo andava provato, e provandolo è venuto fuori.)

**`@EntityGraph`**, per i metodi di cui Spring Data scrive la query da solo (l'elenco delle regole, i
budget):

```java
@EntityGraph(attributePaths = "category")
List<ClassificationRule> findAllByOrderByPriorityAscIdAsc();
```

Ottiene lo stesso effetto (un `JOIN` in più nella query) senza scrivere la query.

**Lo abbiamo verificato.** Con il registro delle query SQL acceso, ogni pagina fa un numero **fisso** di
query, qualunque sia il numero di movimenti, regole o budget:

| Richiesta | Query |
|---|---|
| `GET /api/transactions` (elenco movimenti) | 1 (più quella di conteggio, se le pagine sono più di una) |
| `GET /api/rules` | 2 (regole con categoria, utilizzi) |
| `GET /api/budgets` | 3 (budget con categoria, spese del mese, spese dei 3 mesi prima) |
| `GET /api/categories` | 2 |
| `GET /api/summary/monthly` | 2 |

### 4. Le API: id per scrivere, id e nome per leggere

- **Per scrivere** si manda l'id: `PUT /api/transactions/449/manual-category {"categoryId": 8}`, e
  `categoryId` nelle regole e nei budget.
- **Per leggere** arrivano entrambi: `"categoryId": 8, "category": "Shopping"`. L'id serve ai menu (sapere
  quale voce è selezionata), il nome serve da mostrare. Così la dashboard non deve cercare il nome da sola.

Un id che non esiste riceve **400**: "La categoria 999999 non esiste: forse è stata eliminata. Ricarica la
pagina." Succede se elimini una categoria in una finestra del browser e poi la scegli in un'altra rimasta
aperta. Il controllo è in `CategoryService.require(id)`; il database resta l'ultima difesa con la chiave
esterna.

---

## Parte B: la migrazione V13

`V13__categorie_per_id.sql` fa, per ognuna delle quattro colonne (categoria e categoria manuale dei
movimenti, categoria delle regole, categoria dei budget):

1. **aggiunge** la colonna nuova (`category_id`), vuota;
2. la **riempie** cercando l'id del nome:
   ```sql
   UPDATE bank_transaction t SET category_id = c.id FROM category c WHERE c.name = t.category;
   ```
   È un `UPDATE ... FROM`: per ogni movimento trova la categoria con lo stesso nome e ne copia l'id. V12
   garantisce che ogni nome usato sia nell'elenco, quindi nessun movimento resta senza;
3. **toglie** la colonna vecchia: con lei spariscono la sua chiave esterna di V12 e il vincolo
   `UNIQUE (category, currency)` dei budget;
4. **aggiunge** le chiavi esterne nuove verso `category(id)`, con le stesse regole di V12 (`ON DELETE SET
   NULL` per movimenti e regole; per i budget nessuna, quindi non si elimina una categoria con un budget).
   `ON UPDATE CASCADE` non serve più: un id non cambia mai.

In più crea gli **indici** sulle colonne nuove. PostgreSQL non li crea da solo per le chiavi esterne, e
servono quando si elimina una categoria: il database deve trovare in fretta chi la usa.

Flyway esegue tutta la migrazione in **una transazione**: se un passo fallisse, il database tornerebbe
esattamente com'era.

### Come l'abbiamo verificata

Su un database portato alla V12 e riempito con dati di prova (movimenti con categoria e categoria manuale,
regole dell'utente e di partenza, budget in due valute, una categoria creata dall'utente), abbiamo fatto
una "fotografia" prima della migrazione, con il **nome** collegato a ogni riga, e una dopo, con il nome
**ricavato dall'id**. Poi le abbiamo confrontate con `EXCEPT` (le righe che stanno in una e non nell'altra):

```
differenze        | 0
righe controllate | 25
```

Nessun collegamento perso o sbagliato.

---

## Parte C: che cosa è cambiato nel codice

**Backend**

| File | Cambio |
|---|---|
| `BankTransaction`, `ClassificationRule`, `Budget` | `String category` → `Category category` con `@ManyToOne(fetch = LAZY)` |
| `BankTransactionRepository` | `JOIN FETCH` + `countQuery` nell'elenco; somme per categoria con `LEFT JOIN t.category c` (id e nome) |
| `ClassificationRuleRepository`, `BudgetRepository` | `@EntityGraph(attributePaths = "category")` |
| `CategoryService` | `require(id)` al posto di `resolve(nome)`; la rinomina non ha più bisogno del `flush()` |
| `TransactionDto`, `RuleDto`, `BudgetDto`, `CategoryExpense` | `categoryId` e `category` (il nome) |
| `RuleRequest`, `BudgetRequest`, `ManualCategoryChange` | `categoryId` al posto del nome |
| `ClassificationService` | il risultato della classificazione porta una `Category` |

**Frontend**

| File | Cambio |
|---|---|
| `api/models.ts`, `api/wallet-api.ts` | `categoryId` nei movimenti, nelle regole, nei budget; `correctCategory(id, categoryId)` |
| Movimenti | il menu ha come valore l'id; "crea una regola" passa `categoryId` nell'indirizzo |
| Regole, Budget | i menu usano `[ngValue]` |

### `[value]` e `[ngValue]`

In HTML il valore di un'opzione è **sempre testo**: `<option value="8">` dà "8", non il numero 8. Nella
pagina Movimenti, dove il menu non è un Reactive Form, lo trasformiamo noi con `Number(value)`.

Nei Reactive Forms (Regole, Budget) Angular offre di meglio:

```html
<select formControlName="categoryId">
  <option [ngValue]="null">— nessuna —</option>
  <option [ngValue]="category.id">{{ category.name }}</option>
</select>
```

Con `[ngValue]` il campo del modulo riceve **il valore vero**: il numero `8`, oppure `null`. Non serve
nessuna conversione, e "nessuna categoria" è davvero `null`, non una stringa vuota.

### I test

Tutti i test che usavano categorie sono stati aggiornati. Alcuni dettagli:
- nel backend, un piccolo metodo `category("Shopping")` in ogni classe di test trova la categoria di
  partenza; le richieste JSON usano il suo id;
- per controllare la categoria di un movimento **fuori** da una transazione si confronta l'**id**
  (`getCategory().getId()`), che il segnaposto conosce già. Leggere il nome (`getName()`) fuori da una
  transazione darebbe un errore (`LazyInitializationException`): il segnaposto non può più chiedere al
  database;
- il test della rinomina ora dice il contrario di prima: **cambia una sola riga**, e leggendo movimenti,
  regole e budget dalle API il nome è già quello nuovo;
- nel frontend, con `[ngValue]` il valore HTML di un'opzione non è l'id (Angular ci mette un suo codice),
  quindi i test scelgono le voci **per testo**, come farebbe una persona.

Risultato: **124 test backend e 63 frontend** verdi. In più, una prova completa nel browser con il backend
vero:
1. categoria scelta su un movimento;
2. "crea una regola" con la categoria già scelta nel menu;
3. la regola applicata all'altro movimento simile;
4. un budget creato dal menu;
5. la categoria rinominata;
6. il nome nuovo in Movimenti, Regole, Budget e Riepilogo.

Nessun errore HTTP né della pagina.

---

## Parte D: provalo

1. `git pull`, riavvia il backend (Flyway applica la V13) e la dashboard.
2. Tutto deve funzionare come prima: menu, regole, budget, riepilogo.
3. Rinomina una categoria nella pagina Categorie. Poi, in `psql`:
   ```sql
   SELECT category_id, count(*) FROM bank_transaction GROUP BY category_id;
   ```
   I movimenti hanno ancora lo stesso numero: è cambiata solo la riga in `category`.

### Esercizi

1. Togli `LEFT JOIN FETCH t.category` dalla query `search` e accendi il registro delle query
   (`--logging.level.org.hibernate.SQL=debug`). Quante query fa ora l'elenco dei movimenti? Rimettilo.
2. Cambia `FetchType.LAZY` in `EAGER` su `BankTransaction.category` e ripeti l'esercizio 1. Che cosa
   noti? Perché "comodo" non vuol dire "veloce"?
3. Togli `countQuery` dalla query `search` e accendi il registro delle query. Le pagine funzionano
   ancora? Qual è la query di conteggio che Spring Data ricava da sola, e in che cosa è diversa dalla
   nostra?
