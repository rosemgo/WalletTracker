# 19. Fase 3, passo 2: le categorie

**Obiettivo:** sapere **dove** vanno i soldi, non solo quanti.
1. Nella pagina **Movimenti** ogni movimento ha la sua categoria, con un pulsante **"modifica"**. Mentre
   scrivi, la dashboard suggerisce le categorie già usate.
2. Dopo una correzione (del tipo o della categoria) compare **"crea una regola"**. Apre la pagina Regole
   con il modulo già compilato e la prova già fatta: così la stessa correzione vale per tutti i
   movimenti simili, anche quelli futuri.
3. I **rimborsi**: un'entrata segnata come **"Rimborso di una spesa"** riduce le spese della sua categoria.
4. Nel **Riepilogo** c'è la sezione **"Spese per categoria"**, dalla più alta.

> Prerequisiti: passo 1 completato e `git pull` fatto. Al primo avvio Flyway applica la migrazione **V9**.

---

> **Aggiornamento (passo 5b, `docs/23`).** Le categorie non sono più testo libero: ora c'è un **elenco**
> (pagina Categorie) con un menu a discesa, e il database garantisce che ogni categoria usata sia
> nell'elenco. Quello che segue sul "testo libero" e sui suggerimenti con `<datalist>` racconta la prima
> versione; il resto (chi vince, le regole dalle correzioni, i rimborsi) vale ancora.

## Parte A: i concetti

### 1. Tipo e categoria: due domande diverse

| | Domanda | Esempi | Chi lo decide |
|---|---|---|---|
| **tipo** | *che cosa è* questo movimento? | spesa, entrata, trasferimento tra i tuoi conti | il motore (abbinamenti, regole), o tu a mano |
| **categoria** | *per che cosa* sono i soldi? | Spesa alimentare, Affitto, Abbonamenti | le regole, o tu a mano |

Il tipo decide **se** un movimento conta nel riepilogo. La categoria decide **dove** compare tra le spese.
Una spesa senza categoria conta lo stesso, sotto "Senza categoria".

### 2. Chi vince: la scelta manuale

È lo stesso principio del tipo (`docs/07`): **la correzione manuale vince sempre**.

| Situazione | Categoria del movimento |
|---|---|
| hai scelto una categoria a mano | **la tua**, anche se una regola ne propone un'altra |
| nessuna scelta a mano, una regola corrisponde | quella della regola |
| niente di tutto questo | nessuna ("senza categoria") |

Per tornare alla categoria della regola, svuota il campo e premi "Salva".

Nel database ci sono due colonne, come per il tipo:
- `manual_category` (nuova, migrazione V9): la tua scelta, oppure `NULL`;
- `category`: il **risultato finale**, cioè quello che la dashboard mostra e il riepilogo somma.

La classificazione le combina in `BankTransaction.classify(...)`:

```java
this.category = manualCategory != null ? manualCategory : category;
```

### 3. Categorie come testo libero

Le categorie **non hanno una tabella**: sono testo libero, sia nelle regole sia nei movimenti. È la scelta
più semplice e funziona per chiunque: ognuno usa le sue parole. Il rischio è creare doppioni ("Spesa"
e "spesa"), quindi la dashboard **suggerisce** le categorie già usate mentre scrivi.

Il suggerimento usa un elemento HTML standard, `<datalist>`: il campo resta libero, ma il browser mostra
un menu con i valori dell'elenco che corrispondono a quello che stai scrivendo.

```html
<input list="categories" />
<datalist id="categories">
  <option value="Abbonamenti"></option>
  <option value="Spesa alimentare"></option>
</datalist>
```

Se un giorno servirà rinominare una categoria in tutti i movimenti, aggiungeremo una funzione apposta.

### 4. Dalla correzione alla regola

Correggere i movimenti uno per uno funziona, ma il mese prossimo arriverà un altro Netflix da correggere.
Il flusso pensato è questo:
1. **correggi** un movimento (categoria "Abbonamenti");
2. premi **"crea una regola"**;
3. la pagina **Regole** si apre con il modulo già compilato:
   - testo da cercare: la controparte (`NETFLIX.COM`), o la causale se manca;
   - tipo e categoria: quelli del movimento;
   - "solo uscite" o "solo entrate", in base al segno dell'importo;
4. la **prova** parte da sola e ti mostra a quali movimenti si applicherebbe;
5. se serve **accorci** il testo (ad esempio da "ESSELUNGA SPA VIA ROMA 12" a "ESSELUNGA"), poi
   **salvi**.

Il punto 4 è il più importante: **la regola non si salva da sola**. Il motore non deve indovinare, quindi
decidi tu, dopo aver visto il risultato.

**Come passano i dati da una pagina all'altra?** Nell'indirizzo:

```
/regole?pattern=NETFLIX.COM&resultType=EXPENSE&amountSign=NEGATIVE&category=Abbonamenti
```

La pagina Regole li legge all'avvio, come la pagina di ritorno dalla banca legge `code` e `state`
(`docs/13`). **Attenzione alla sicurezza:** un indirizzo lo può scrivere chiunque, anche un sito che ti
manda un link. Per questo la pagina:
- accetta come tipo e "entrate/uscite" **solo i valori che conosce**, e ignora gli altri;
- taglia i testi alla lunghezza massima;
- **non salva niente** da sola.

E comunque l'ultima parola resta al backend, che valida ogni regola (`RuleRequest`). Inoltre Angular
scrive i valori nella pagina come **testo** e mai come HTML: un `<script>` nell'indirizzo resta la
scritta "&lt;script&gt;" e non viene eseguito. Un test verifica che i valori sconosciuti vengano ignorati.

### 5. I rimborsi

Hai comprato delle scarpe a 120 €, poi ne hai restituito un paio e Zalando ti ha rimborsato 40 €. Che
cosa vogliamo vedere?
- **non** 40 € di "entrate": non è un guadagno;
- **80 €** spesi in Abbigliamento.

Come si fa:
1. nella pagina Movimenti, sul rimborso, scegli il tipo **"Rimborso di una spesa"**;
2. dagli la stessa categoria della spesa (Abbigliamento).

Che cosa succede sotto: "Rimborso di una spesa" è il tipo `EXPENSE` con un **importo positivo**. Nel
backend le spese si sommano con il segno e poi si cambia segno: −120 + 40 = −80, cioè 80 € di spesa. Non
è servito nessun tipo nuovo: il calcolo del passo 1 funzionava già così. Mancava solo un modo chiaro per
dirlo dalla dashboard: nel menu del tipo, per le entrate, la voce "Spesa" si chiama "Rimborso di una
spesa".

Se i rimborsi di un negozio sono frequenti, puoi creare una regola: testo "ZALANDO", **solo entrate**,
tipo Spesa, categoria Abbigliamento.

---

## Approfondimento: un rimborso, dal clic al numero

Seguiamo il rimborso Zalando di 40 € passo per passo, per vedere che cosa fa il frontend, che cosa fa il
backend e che cosa finisce nel database.

### 1. Il rimborso arriva dalla banca

La sincronizzazione importa il movimento così com'è: **+40,00**, controparte "ZALANDO". Nessuna regola
corrisponde, quindi il motore arriva all'ultima risorsa: importo positivo → `INCOME` (entrata).

Il motore **non può sapere** che è un rimborso: +40 € da Zalando potrebbe anche essere un buono o un
errore. Indovinare sarebbe pericoloso (ricordi la carta di credito, `docs/10`). Quindi lo dici tu.

| Colonna | Valore |
|---|---|
| `amount` | 40.00 |
| `type` | INCOME |
| `manual_type` | NULL |
| `category` / `manual_category` | NULL / NULL |

### 2. Il frontend: solo un'etichetta diversa

Nella pagina Movimenti il menu del tipo ha un'opzione per ogni valore di `TransactionType`. L'unica
differenza per le entrate è **il testo** di un'opzione:

```ts
protected typeLabel(transaction: Transaction, type: TransactionType): string {
  return type === 'EXPENSE' && transaction.amount > 0
    ? 'Rimborso di una spesa'
    : this.typeLabels[type];
}
```

```html
<option [value]="type" ...>{{ typeLabel(t, type) }}</option>
```

Il **valore** dell'opzione resta `EXPENSE`. Cambia solo la scritta che vedi, perché "Spesa" su
un'entrata di +40 € confonderebbe. Quando la scegli, parte la stessa richiesta di qualsiasi correzione
del tipo:

```
PUT /api/transactions/123/manual-type      {"type": "EXPENSE"}
```

### 3. Il backend: una correzione manuale come le altre

`TransactionService.correctType` salva `manual_type = EXPENSE` e riclassifica. Nel motore la correzione
manuale vince (passo 2 dell'ordine in `ClassificationService`), quindi il tipo diventa `EXPENSE`. Poi dai
la categoria con "modifica" (`PUT .../manual-category {"category": "Abbigliamento"}`).

| Colonna | Valore |
|---|---|
| `amount` | **40.00** (non cambia mai: è il dato della banca) |
| `type` | **EXPENSE** |
| `manual_type` | EXPENSE |
| `category` / `manual_category` | Abbigliamento / Abbigliamento |

**Nel backend la parola "rimborso" non esiste.** Non c'è un tipo `REFUND` né un `if` speciale: c'è solo
una spesa con l'importo positivo.

### 4. Il calcolo: la somma con il segno fa tutto

Il riepilogo, le spese per categoria e i budget usano la stessa idea: il database **somma gli importi con
il loro segno**, poi Java cambia segno per avere una spesa positiva.

```sql
SELECT category, SUM(amount) ... WHERE type = 'EXPENSE' GROUP BY category
```

| Movimento | `amount` |
|---|---|
| scarpe | −120.00 |
| rimborso | +40.00 |
| **SUM** | **−80.00** → `negate()` → **80 € spesi in Abbigliamento** |

Se il rimborso fosse rimasto `INCOME`, la stessa query non lo vedrebbe (filtra `type = 'EXPENSE'`): le
spese sarebbero 120 € e le entrate gonfiate di 40 €.

### 5. Perché non un tipo "Rimborso" a parte?

Avremmo potuto aggiungere `REFUND` all'enum. Ma ogni parte del programma che conta le spese (riepilogo,
categorie, budget, e quelle future) avrebbe dovuto ricordarsi di sottrarlo: basta dimenticarlo in un
punto per avere un numero sbagliato. Con il segno, la matematica è giusta **da sola**, ovunque.

È anche per questo che una regola può gestire i rimborsi in automatico: testo "ZALANDO", **solo
entrate**, tipo Spesa, categoria Abbigliamento.

---

## Parte B: il backend

| File | Cosa cambia |
|---|---|
| `db/migration/V9__categoria_manuale.sql` | la colonna `manual_category` |
| `transaction/BankTransaction.java` | il campo `manualCategory`; in `classify(...)` la scelta manuale vince |
| `transaction/TransactionController.java`, `TransactionService.java` | `PUT /api/transactions/{id}/manual-category` |
| `transaction/TransactionDto.java` | il campo `manualCategory` nel JSON |
| `classification/CategoryController.java` | `GET /api/categories`: le categorie usate in regole e movimenti |
| `transaction/BankTransactionRepository.java` | le query `findDistinctCategories` e `sumExpensesByCategory` |
| `summary/SummaryService.java`, `CurrencySummary.java` | `expensesByCategory` nel riepilogo |

### Le nuove API

```
PUT /api/transactions/449/manual-category      {"category": "Abbonamenti"}
PUT /api/transactions/449/manual-category      {"category": null}    ← toglie la scelta
GET /api/categories                            ["Abbonamenti", "Capital gain", "Cedole", ...]
GET /api/summary/monthly                       ... "expensesByCategory": [
                                                     {"category": "Abbigliamento", "amount": 80.00, "movements": 2},
                                                     {"category": null, "amount": 817.50, "movements": 6} ]
```

Alcuni dettagli da notare:

- **Una stringa vuota vale come `null`** e gli spazi all'inizio e alla fine si tolgono (`trim()`): con
  o senza spazi intorno, "Spesa" è la stessa categoria. Al massimo 100 caratteri, come nelle regole
  (`@Size(max = 100)` con `@Valid`: oltre, 400 Bad Request).
- **Dopo una scelta si riclassifica tutto**, come per il tipo. Serve quando togli la scelta manuale: la
  categoria deve tornare a essere quella della regola, e a deciderlo è la classificazione.
- **`SELECT DISTINCT`** restituisce ogni categoria una volta sola. L'elenco di regole e movimenti finisce
  in un `TreeSet`, un insieme ordinato e senza doppioni, con un **`Collator`** italiano: confronta le
  parole come un dizionario. Senza, "Viaggi" verrebbe prima di "auto", perché nei codici dei caratteri
  tutte le maiuscole vengono prima delle minuscole.
- **Le spese per categoria** usano la stessa idea del passo 1: il database somma con `GROUP BY` e Java
  ordina dalla spesa più alta.

### I test

| Test | Cosa verifica |
|---|---|
| `aManualCategoryWinsOverTheRuleAndCanBeRemoved` | la scelta manuale vince sulla regola "Canone del conto", resiste a una riclassificazione, si toglie con una stringa vuota e torna "Costi bancari" |
| `categoriesFromRulesAndMovementsAreSuggestedInAlphabeticalOrder` | ordine da dizionario ("abbigliamento" prima di "Capital gain"), nessun doppione |
| `expensesAreGroupedByCategoryAndRefundsReduceTheirCategory` | 120 di scarpe − 40 di rimborso = 80 in Abbigliamento; spese senza categoria; ordine dalla più alta |
| `wrongRequestsAreRejected` | in più: una categoria di 101 caratteri → 400 |

---

## Parte C: il frontend

| File | Cosa cambia |
|---|---|
| `transactions/transactions-page.*` | categoria con "modifica", suggerimenti, "Rimborso di una spesa", "crea una regola" |
| `rules/rules-page.*` | il modulo si compila dall'indirizzo e la prova parte da sola; suggerimenti nel campo Categoria |
| `summary/summary-page.*` | la sezione "Spese per categoria" |
| `api/models.ts`, `api/wallet-api.ts` | `manualCategory`, `expensesByCategory`, `correctCategory()`, `categories()` |

### Una variabile del template: `#categoryInput`

Nel campo della categoria c'è una novità:

```html
<input #categoryInput ... (keydown.enter)="saveCategory(t, categoryInput.value)" />
<button (click)="saveCategory(t, categoryInput.value)">Salva</button>
```

`#categoryInput` dà un **nome** all'elemento `<input>` dentro il template. I pulsanti accanto possono
leggerne il valore (`categoryInput.value`) senza bisogno di un campo nella classe o di un Reactive Form.
Per un campo singolo e temporaneo è la soluzione più semplice. Per un modulo vero, con validazioni, come
quello delle regole, restano i Reactive Forms.

`(keydown.enter)` e `(keydown.escape)` sono eventi "filtrati" da Angular: partono solo per quel tasto.
Invio salva, Esc annulla.

### Un link con i parametri: `[queryParams]`

```html
<a routerLink="/regole" [queryParams]="ruleParams(t)">crea una regola</a>
```

`ruleParams(t)` restituisce un oggetto (`{pattern: 'NETFLIX.COM', resultType: 'EXPENSE', ...}`) e il
router lo trasforma nella parte `?pattern=...&resultType=...` dell'indirizzo, con i caratteri speciali
già "codificati" (uno spazio diventa `%20`).

### Il modulo che si compila da solo

Nella pagina Regole, `prefillFromAddress()` legge i parametri con
`this.route.snapshot.queryParamMap` e usa **`patchValue`**. A differenza di `setValue` (usato da
"Modifica"), `patchValue` aggiorna **solo i campi indicati** e lascia gli altri ai valori iniziali.
Notate il controllo:

```ts
...(this.types.includes(resultType) ? { resultType } : {}),
```

Se il tipo nell'indirizzo è uno di quelli che conosciamo, il campo viene aggiornato. Altrimenti si
aggiunge `{}`, cioè niente, e il campo resta "Spesa".

### Le spese per categoria: barre orizzontali in HTML

Per una sola serie di valori non serve SVG: bastano dei `<span>` con una larghezza in percentuale
(`[style.width.%]="row.barPercent"`). La barra più lunga è la categoria più alta e le altre sono in
proporzione. Accanto a ogni barra ci sono l'importo e la percentuale delle spese totali, **scritti**:
così si leggono anche senza guardare le barre.

Oltre le prime 8, le categorie finiscono insieme in **"Altre categorie (N)"**: un elenco di 30 righe non
si legge. "Senza categoria" è in grigio corsivo, perché non è una categoria ma qualcosa da sistemare.

### I test

| Test | Cosa verifica |
|---|---|
| `TransactionsPage` (+3) | la categoria scritta si salva senza spazi, quella vuota toglie la scelta; il link "crea una regola" ha i parametri giusti; per un'entrata compare "Rimborso di una spesa" |
| `RulesPage` (+2) | modulo compilato dall'indirizzo e prova automatica, senza salvare; i valori sconosciuti vengono ignorati |
| `SummaryPage` (+2) | categorie con percentuali e barre in proporzione; le più piccole raccolte in "Altre categorie" |

Un dettaglio del test del link: invece di confrontare il testo dell'indirizzo, che dipende da come
vengono codificati i caratteri, lo trasformiamo in un oggetto con `new URL(link.href).searchParams`, e
confrontiamo quello.

---

## Parte D: provalo

1. `git pull`, riavvia il backend (Flyway applica la V9) e la dashboard.
2. **Movimenti**: scegli un movimento ricorrente (un abbonamento, il supermercato), premi "modifica",
   scrivi la categoria e premi Invio.
3. Premi **"crea una regola"**, accorcia il testo se serve, guarda la prova e salva. Torna ai Movimenti:
   i movimenti simili hanno la categoria.
4. Trova un **rimborso**, scegli "Rimborso di una spesa" e dagli la categoria della spesa.
5. **Riepilogo**: guarda "Spese per categoria". La riga "Senza categoria" ti dice quanto lavoro di
   categorie resta.

Test:

```
cd backend;  .\mvnw.cmd test            # 91 test
cd frontend; npm test -- --watch=false  # 40 test
```

### Esercizi

1. Nella pagina Movimenti, mostra il numero di categorie suggerite accanto a "modifica" (suggerimento:
   `categories().length`). Poi toglilo: serviva solo a capire i signal.
2. Prova ad aprire a mano l'indirizzo `https://localhost:8443/regole?pattern=CAFFE&resultType=INCOME`.
   Che cosa compare nel modulo? E con `resultType=CAFFE`?
3. In `SummaryPage`, cambia `TOP_CATEGORIES` da 8 a 5. Quale test fallisce, e perché?
