# 20. Fase 3, passo 3: i budget mensili

**Obiettivo:** decidere quanto vuoi spendere al massimo in una categoria ogni mese (ad esempio "Spesa
alimentare: 400 €") e vedere **come sta andando**. Nuova pagina **Budget**:
1. per ogni budget, una **barra**: quanto hai speso rispetto al budget, colorata secondo lo stato
   (**nei limiti**, **quasi esaurito**, **superato**), sempre con un'icona e un'etichetta scritta;
2. sulla barra del mese in corso, un segno **"oggi"**: quanto avresti speso finora spendendo in modo
   regolare;
3. sotto la barra, quanto **resta** (o di quanto l'hai **superato**) e la **media dei 3 mesi prima**,
   per scegliere un budget realistico;
4. le frecce **‹ ›** per guardare i mesi passati;
5. un modulo per **creare, modificare ed eliminare** i budget.

> Prerequisiti: passo 2 completato e `git pull` fatto. Al primo avvio Flyway applica la migrazione
> **V10**. I budget lavorano sulle **categorie**: più movimenti hanno la categoria giusta, più i budget
> sono utili.

---

## Parte A: i concetti

### 1. Che cosa conta in un budget

Le spese di un budget si calcolano **esattamente come nel Riepilogo**: con la stessa query
(`sumExpensesByCategory`), quindi con le stesse regole:
- contano solo i movimenti di tipo **Spesa** (`EXPENSE`) con quella categoria;
- un **rimborso** ("Rimborso di una spesa", `docs/19`) **riduce** la spesa del budget;
- trasferimenti tra i tuoi conti, saldo della carta di credito e titoli **non contano mai**;
- contano anche i movimenti **in attesa**.

Riusare la stessa query è una scelta voluta: se il budget e il riepilogo calcolassero le spese in due
modi diversi, prima o poi mostrerebbero due numeri diversi per la stessa cosa.

### 2. Gli stati

| Stato | Quando | Come si vede |
|---|---|---|
| **Nei limiti** (`OK`) | sotto l'80% del budget | ✓ verde |
| **Quasi esaurito** (`WARNING`) | dall'80% al 100% compreso | ! giallo |
| **Superato** (`OVER`) | oltre il 100% | ✕ rosso, e "superato di ..." |

Lo stato lo calcola il **backend** (`BudgetService`): è una regola del programma, non un dettaglio
grafico. Se domani ci fosse un'app per telefono, o un avviso via email, userebbero la stessa soglia
senza doverla riscrivere.

**Perché icona ed etichetta, e non solo il colore?** Circa un uomo su dodici distingue male il rosso dal
verde. E un colore da solo non dice niente a chi usa un lettore di schermo. I colori di stato inoltre
sono **riservati**: verde, giallo e rosso non vengono mai usati per le serie dei grafici, così non si
confondono con "entrate" o "spese".

### 3. Il segno "oggi": il ritmo di spesa

Il 17 ottobre hai speso 58,98 € di un budget di 50 €: è chiaro che l'hai superato. Ma se a metà mese
hai speso 300 € di 400, va bene o male? Dipende da **quando** siamo nel mese.

Il segno "oggi" risponde a questa domanda. È quanto avresti speso finora spendendo **in modo regolare**,
la stessa cifra ogni giorno:

```
atteso a oggi = budget × giorni passati / giorni del mese
              = 400 € × 17 / 31 = 219,35 €
```

- **barra prima del segno**: stai spendendo più piano del previsto;
- **barra oltre il segno**: stai spendendo più in fretta, e a fine mese rischi di superare il budget.

È un'indicazione, non una regola: l'affitto o un abbonamento annuale arrivano tutti in un giorno.
Per questo il segno non cambia lo stato, che dipende solo dalla spesa reale.

Nei mesi passati il segno non c'è (il mese è finito, "atteso" = tutto il budget).

### 4. La media dei 3 mesi prima

Quando crei un budget, il rischio è scegliere un numero di fantasia. Accanto a ogni budget c'è la spesa
media della categoria nei **3 mesi prima** di quello mostrato: se negli ultimi mesi hai speso in media
306 € al supermercato, un budget di 200 € sarà superato quasi sempre.

### 5. Le valute

Come nel Riepilogo, le valute non si sommano: un budget vale per **una categoria in una valuta**. Chi ha
conti in più valute vede il menu "Valuta" nel modulo; tutti gli altri non lo vedono e i budget sono in
euro. Nel database un vincolo `UNIQUE (category, currency)` impedisce due budget per la stessa coppia.

---

## Parte B: il backend

| File | Cosa fa |
|---|---|
| `db/migration/V10__budget.sql` | la tabella `budget`: categoria, valuta, importo mensile (`CHECK > 0`), vincolo `UNIQUE` |
| `budget/Budget.java`, `BudgetRepository.java` | l'entità e l'accesso alla tabella |
| `budget/BudgetRequest.java` | il corpo di POST/PUT, con la validazione |
| `budget/BudgetDto.java` | il budget più l'andamento del mese: spesa, resto, atteso a oggi, media, stato |
| `budget/BudgetService.java` | i calcoli, il controllo dei doppioni |
| `budget/BudgetController.java` | `GET/POST /api/budgets`, `PUT/DELETE /api/budgets/{id}` |

### L'API

```
GET    /api/budgets?month=2026-09     (senza month: il mese in corso)
POST   /api/budgets                   {"category": "Spesa", "currency": "EUR", "monthlyAmount": 400}   → 201
PUT    /api/budgets/1                 {...}
DELETE /api/budgets/1                                                                                   → 204
```

Risposta di `GET` (un elemento):

```json
{ "id": 1, "category": "Spesa", "currency": "EUR", "monthlyAmount": 400.00,
  "spent": 320.00, "remaining": 80.00, "expectedSoFar": 400.00,
  "averageLast3Months": 150.00, "status": "WARNING" }
```

### La validazione: tre difese

Un importo sbagliato viene fermato **tre volte**:

| Dove | Come | Perché |
|---|---|---|
| **frontend** | `Validators.min(0.01)`, `Validators.pattern(...)` | avvisa subito chi scrive |
| **backend** | `@DecimalMin("0.01")`, `@Digits(integer = 10, fraction = 2)`, `@Pattern(regexp = "[A-Z]{3}")` per la valuta | il frontend si può aggirare (chiunque può mandare una richiesta HTTP a mano): **l'ultima parola è del backend** |
| **database** | `CHECK (monthly_amount > 0)`, `UNIQUE (category, currency)` | anche un errore nel codice Java non può salvare un dato impossibile |

Un budget doppio riceve **409 Conflict**: "Esiste già un budget per "Spesa" in EUR.". È il codice HTTP
per "la richiesta è corretta, ma va in conflitto con lo stato attuale dei dati". Il backend lo
controlla **prima** di salvare per dare un messaggio chiaro, e il vincolo `UNIQUE` resta l'ultima
difesa.

### I calcoli

- **Spesa del mese e dei 3 mesi prima**: due chiamate alla stessa query del riepilogo, con due periodi
  diversi. Il risultato diventa una mappa `"categoria|valuta" → spesa`, e ogni budget prende la sua
  riga. In tutto: **due query**, qualunque sia il numero dei budget. Una query per budget sarebbe il
  "problema N+1" (`docs/09`).
- **`expectedSoFar`** è un metodo `static` che riceve anche la data di oggi come parametro:

  ```java
  static BigDecimal expectedSoFar(BigDecimal amount, YearMonth month, LocalDate today)
  ```

  Così il test può provare il 10 settembre, il 30 settembre o un mese futuro, senza dipendere dal giorno
  in cui gira. È un trucco comune: **ciò che cambia da solo (il tempo) si passa come parametro**.
- **`RoundingMode.HALF_UP`**: dividere dei `BigDecimal` (400 × 17 / 31) può dare infiniti decimali. Va
  scelto quanti decimali tenere (2) e come arrotondare: `HALF_UP` è l'arrotondamento "di scuola" (5 va
  su).

### I test

| Test | Cosa verifica |
|---|---|
| `aBudgetShowsTheExpensesOfItsCategoryInTheMonthRefundsIncluded` | 150 + 200 − 30 di rimborso = 320; altre categorie e altri mesi esclusi; media dei 3 mesi prima; categoria senza spazi |
| `theStatusDependsOnHowMuchOfTheBudgetIsSpent` | 79% → OK, 100% → WARNING, 100,01 → OVER |
| `aBudgetCanBeChangedAndDeletedButNotDuplicated` | 409 per il doppione, la stessa categoria in USD va bene, modifica, cancellazione, 404 |
| `invalidBudgetsAreRejected` | importo zero, valuta minuscola, tre decimali, categoria vuota, mese inesistente → 400 |
| `theExpectedSpendingGrowsDayByDayInTheCurrentMonth` | il ritmo il 10 e il 30 settembre, in un mese passato e in uno futuro |

---

## Parte C: il frontend

| File | Cosa fa |
|---|---|
| `budget/budget-page.ts` / `.html` | la pagina: mesi, barre, stati, modulo |
| `api/models.ts`, `api/wallet-api.ts` | `Budget`, `BudgetRequest`, i quattro metodi delle API |
| `app.routes.ts`, `app.html` | la pagina `/budget`, nel menu dopo Riepilogo |
| `styles.css` | barre e stati |

### La barra

È fatta di tre `<div>`:

```html
<div class="meter" role="meter" ...>                          ← il binario grigio, tutto il budget
  <div class="meter-fill ok" [style.width.%]="barPercent(b)">  ← la spesa, colorata per stato
  <div class="meter-today" [style.left.%]="expectedPercent(b)"> ← il segno "oggi"
</div>
```

- `barPercent` è la spesa diviso il budget, **al massimo 100%**: un budget superato riempie la barra, e
  "superato di ..." dice di quanto.
- `role="meter"` con `aria-valuenow` e `aria-valuemax` dice ai lettori di schermo che è un indicatore, e
  con quali valori.

### La virgola italiana

Chi scrive "150,50" intende 150 euro e 50 centesimi, ma per JavaScript il separatore decimale è il punto.
Il validatore accetta sia la virgola sia il punto:

```ts
Validators.pattern(/^\d+([.,]\d{1,2})?$/)
```

Leggiamo l'espressione regolare un pezzo per volta: `^\d+` (una o più cifre dall'inizio), poi
facoltativo `( )?` un punto o una virgola `[.,]` seguiti da una o due cifre `\d{1,2}`, e `$` la fine.
Prima di spedire, `replace(',', '.')` trasforma la virgola in punto.

### I mesi

```ts
export function addMonths(month: string, delta: number): string {
  const [year, monthNumber] = month.split('-').map(Number);
  return monthOf(new Date(year, monthNumber - 1 + delta, 1));
}
```

È lo stesso trucco del Riepilogo: `new Date` sistema da solo i mesi fuori scala (il mese 0 − 1 è
dicembre dell'anno prima). La freccia **›** è disattivata sul mese in corso: nei mesi futuri non ci sono
ancora spese.

### La valuta, solo se serve

La pagina chiede i conti (`api.accounts()`) e ne ricava le valute senza doppioni:

```ts
const currencies = [...new Set(accounts.map((a) => a.currency))].sort();
```

`new Set(...)` toglie i doppioni, `[...]` lo ritrasforma in un array. Il menu "Valuta" compare solo se le
valute sono più di una.

### I test

| Test | Cosa verifica |
|---|---|
| `BudgetPage` (7) | stati con icona ed etichetta, barra all'80% e al massimo al 100%, segno "oggi" a metà; frecce dei mesi, niente "oggi" nei mesi passati; creazione con la virgola ("150,50" → 150.5) e categoria senza spazi; tre decimali rifiutati e messaggio del backend per il doppione; "modifica" aggiorna lo stesso budget; menu della valuta solo con più valute; `addMonths` attraverso l'anno |

---

## Parte D: provalo

1. `git pull`, riavvia il backend (Flyway applica la V10) e la dashboard.
2. Pagina **Budget**: crea un budget per una categoria che usi spesso. Guarda la "media dei 3 mesi
   prima" e correggi l'importo se era troppo ottimista.
3. Guarda il segno "oggi": la barra lo ha superato?
4. Con **‹** guarda i mesi passati: avresti rispettato il budget?

Test:

```
cd backend;  .\mvnw.cmd test            # 96 test
cd frontend; npm test -- --watch=false  # 47 test
```

### Esercizi

1. Cambia la soglia di "quasi esaurito" dall'80% al 90%. Quale costante? Quale test fallisce, e come lo
   aggiorni?
2. Aggiungi in cima alla pagina una riga "Totale budget: X € · speso: Y €" (suggerimento: un `computed`
   che somma `monthlyAmount` e `spent` dei budget in euro).
3. Perché `expectedSoFar` riceve `today` come parametro invece di chiamare `LocalDate.now()` al suo
   interno? Prova a scrivere il test del 10 settembre senza quel parametro.
