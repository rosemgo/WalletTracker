# 18. Fase 3, passo 1: il riepilogo mensile e il primo grafico

**Obiettivo:** una nuova pagina **Riepilogo**, che diventa la pagina iniziale della dashboard:
1. quattro riquadri con i totali del periodo: **entrate**, **spese**, **risparmio** (con la percentuale
   delle entrate) e **rendite nette degli investimenti**;
2. un **grafico** a colonne: entrate e spese, mese per mese;
3. una **tabella** con gli stessi numeri;
4. un **avviso** se ci sono movimenti "da verificare", perché finché ci sono i numeri sono incompleti.

Si può scegliere il periodo: ultimi 6, 12 o 24 mesi.

> Prerequisiti: Fase 2 completata e `git pull` fatto.

---

## Il piano della Fase 3

Come sempre, a piccoli passi. Ogni passo è utilizzabile da solo:

| Passo | Cosa | Perché in quest'ordine |
|---|---|---|
| **1** | **riepilogo mensile e grafico entrate/spese** (questo) | usa solo i dati che abbiamo già: il tipo di ogni movimento |
| 2 | **categorie**: assegnarle a mano, "crea una regola da questa correzione", spese per categoria, rimborsi | oggi la categoria la mettono solo le regole, quindi molte spese non ne hanno |
| 3 | **budget** mensili per categoria | servono le categorie del passo 2 |
| 4 | **avvisi**: consenso della banca in scadenza, sincronizzazione fallita | il consenso dura al massimo 180 giorni e va rinnovato dall'utente |
| 5 | **abbonamenti ricorrenti** (Netflix, palestra...) | proposti come *suggerimenti*: il motore non deve indovinare |
| 6 | **import CSV** (es. l'esportazione di Trade Republic) | per i conti che Enable Banking non copre bene |
| 7 | facoltativo: **suggerimenti di categoria con l'intelligenza artificiale** | vedi `docs/03` |

---

## Parte A: i concetti

### 1. Cosa conta come entrata e come spesa

Il riepilogo **non guarda i conti né le banche**: guarda solo il **tipo** di ogni movimento, già deciso
dalla classificazione (o dalla tua correzione manuale). Per questo la classificazione è il cuore del
progetto: se i tipi sono giusti, i totali sono giusti.

| Tipo | Nel riepilogo |
|---|---|
| `INCOME` (entrata) | **entrate** |
| `EXPENSE` (spesa) | **spese** |
| `INVESTMENT_INCOME`, `INVESTMENT_TAX` | **rendite nette investimenti** (cedole e dividendi meno le tasse), a parte |
| `TO_REVIEW` (da verificare) | **non conta**, ma viene **contato** e segnalato |
| trasferimenti tra conti propri, saldo carta di credito, versamenti e prelievi investimenti, compravendita titoli, ignorati | **non conta**: sono soldi che si spostano, non che entrano o escono |

Un esempio: lo stipendio arriva su ING (+2.000), ne sposti 500 su Revolut, con Revolut paghi una cena
(-60). Entrate: 2.000. Spese: 60. Il trasferimento di 500 non compare da nessuna parte.

**Risparmio = entrate − spese.** Può essere negativo (in rosso): quel mese hai speso più di quanto è
entrato.

**I rimborsi.** Se un rimborso è classificato come spesa (importo positivo), riduce le spese del mese,
che è quello che vogliamo. Oggi però un movimento positivo senza regola diventa `INCOME`, quindi un
rimborso di Amazon risulta un'entrata. Lo sistemeremo nel passo 2, con le categorie.

**I movimenti in attesa** (`PENDING`) contano: sono spese vere, solo non ancora definitive. Ad ogni
sincronizzazione vengono sostituiti dalla versione aggiornata, quindi non vengono contati due volte.

### 2. Le valute non si sommano

100 EUR più 100 USD non fa 200 di niente. Il backend fa un riepilogo **per ogni valuta**; se ne hai più di
una, nella pagina compare il menu "Valuta". Chi ha solo conti in euro non lo vede.

### 3. Le somme le fa il database

Per sommare un anno di movimenti potremmo caricarli tutti in Java e sommarli in un ciclo. Ma sarebbero
migliaia di oggetti caricati solo per ottenere poche decine di numeri. Meglio chiedere al database di
fare le somme con `GROUP BY`:

```sql
SELECT anno, mese, valuta, tipo, SUM(importo), COUNT(*)
FROM bank_transaction
WHERE booking_date >= '2025-11-01' AND booking_date < '2026-11-01'
GROUP BY anno, mese, valuta, tipo
```

Il risultato è una riga per ogni combinazione, ad esempio "ottobre 2026, EUR, EXPENSE, -1.234,56, 42
movimenti". Al massimo qualche centinaio di righe, già sommate. Java poi le trasforma nei numeri del
riepilogo.

### 4. Un grafico disegnato a mano (SVG)

Per i grafici esistono librerie (Chart.js, ECharts...). Per questo primo grafico l'abbiamo **disegnato a
mano in SVG**, per tre motivi:
- **si impara come funziona un grafico "dentro"**: è solo geometria (da un valore in euro a una
  coordinata y);
- **una dipendenza in meno**: ogni libreria è codice di altri da aggiornare e di cui fidarsi (vedi
  `docs/17`, *supply chain*);
- **i test lo leggono come normale HTML**: `querySelectorAll('path.bar')`.

Un **SVG** è HTML fatto di forme: `<line>`, `<rect>`, `<path>`, `<text>`. Angular lo genera come
qualsiasi template, con `@for` e i binding `[attr.x]`. Se un giorno servissero grafici molto più complessi
(zoom, centinaia di serie), passeremmo a una libreria.

**Le regole che abbiamo seguito** (sono regole generali per fare grafici chiari):
- **colori verificati**: blu e arancione si distinguono anche per chi è daltonico. L'abbiamo verificato
  con uno strumento che simula i vari tipi di daltonismo, non a occhio;
- **una legenda** sempre, perché le serie sono due e il colore da solo non basta;
- **una scala a numeri tondi** (0, 1.000, 2.000...) e righe orizzontali leggere;
- **colonne sottili** (al massimo 24 px), arrotondate in cima e dritte alla base;
- **i valori al passaggio del mouse**, e anche con il tasto **Tab** (accessibilità);
- **la tabella** con gli stessi numeri, per chi non vede bene i colori o usa un lettore di schermo;
- **su un telefono** il grafico non si rimpicciolisce fino a diventare illeggibile: scorre in orizzontale
  dentro il suo riquadro.

---

## Parte B: il backend

### L'API

```
GET /api/summary/monthly?from=2025-11&to=2026-10
```

Entrambi i parametri sono facoltativi: senza `to` vale il mese in corso, senza `from` gli 11 mesi prima
(in tutto 12). Risposta (accorciata):

```json
{
  "from": "2025-11",
  "to": "2026-10",
  "currencies": [
    {
      "currency": "EUR",
      "months": [
        { "month": "2026-10", "income": 2100.00, "expenses": 1300.00, "savings": 800.00,
          "investmentNet": 42.00, "toReview": 1 }
      ],
      "total": { "income": 27000.00, "expenses": 21142.00, "savings": 5858.00,
                 "investmentNet": 168.00, "toReview": 2 }
    }
  ]
}
```

Le spese sono scritte come numero **positivo** (1300 = hai speso 1300 euro): così chi legge il JSON, e la
dashboard, non deve ricordarsi di cambiare segno.

### I file

| File | Cosa fa |
|---|---|
| `transaction/BankTransactionRepository.java` | nuova query `sumByMonthAndType`: le somme con `GROUP BY` (punto 3) |
| `summary/SummaryService.java` | dalle righe sommate ai numeri del mese; aggiunge i **mesi vuoti** (con zero), così il grafico non salta i mesi senza movimenti; calcola i totali |
| `summary/SummaryController.java` | `GET /api/summary/monthly` |
| `summary/MonthSummary.java`, `CurrencySummary.java`, `SummaryDto.java` | i record della risposta |

Alcuni dettagli da notare:

- **`extract(year from t.bookingDate)`** nella query: estrae l'anno da una data. Va ripetuto nel
  `GROUP BY` perché si raggruppa per il risultato dell'espressione.
- **La proiezione `MonthlyTotal`**: come `RuleUsage` nel passo 4, Spring Data riempie un'interfaccia con
  le colonne della query, abbinandole per nome (`AS bookingYear` → `getBookingYear()`).
- **`YearMonth`**: la classe Java per "un mese di un anno". `@DateTimeFormat(pattern = "yyyy-MM")` dice a
  Spring come leggere `"2026-10"` dall'indirizzo; Jackson lo scrive nel JSON nello stesso formato.
- **Un limite di 120 mesi** per richiesta: 10 anni bastano, e una richiesta enorme non serve a nessuno.
  Un periodo al contrario (`from` dopo `to`), troppo lungo o scritto male (`2026-13`) riceve **400 Bad
  Request** con un messaggio chiaro. È la regola "validare tutto nel backend" di `docs/17`.
- **`ResponseStatusException`**: un'eccezione di Spring che porta con sé il codice HTTP. Spring la
  trasforma da sola in una risposta Problem Details (`{"status": 400, "detail": "..."}`), come le altre
  risposte d'errore del progetto.
- **I soldi restano `BigDecimal`** fino alla fine, anche nei totali: niente `double`.

### I test

| Test | Cosa verifica |
|---|---|
| `onlyRealIncomeAndExpensesCountAndEmptyMonthsAreFilled` | stipendio, spese (anche in attesa), trasferimento escluso, dividendo meno ritenuta, un movimento da verificare, un movimento fuori periodo; agosto vuoto c'è comunque, con zero |
| `differentCurrenciesAreNeverAddedTogether` | EUR e USD in due riepiloghi separati |
| `withoutParametersTheLastTwelveMonthsAreReturned` | i valori predefiniti: 12 mesi fino a quello in corso |
| `wrongPeriodsAreRejected` | periodo al contrario, troppo lungo, mese inesistente → 400 |

Una curiosità nei test: nel JSON `2000.00` e `2000` sono lo stesso numero, ma Java li legge in tipi
diversi. Il metodo `amount(...)` li confronta come `BigDecimal` con `isEqualByComparingTo`, che ignora i
decimali a zero.

---

## Parte C: il frontend

| File | Cosa fa |
|---|---|
| `summary/summary-page.ts` / `.html` | la pagina: periodo, valuta, riquadri, avviso, grafico, tabella |
| `summary/monthly-chart.ts` / `.html` | il grafico SVG, un componente a sé |
| `api/models.ts`, `api/wallet-api.ts` | i tipi della risposta e il metodo `monthlySummary(from)` |
| `app.routes.ts`, `app.html` | la pagina `/riepilogo`, prima voce del menu e nuova pagina iniziale |
| `styles.css` | riquadri e grafico |

### Un componente dentro un altro: `input()`

La pagina passa i dati al grafico così:

```html
<app-monthly-chart [months]="c.months" [currency]="c.currency" />
```

e il grafico li riceve con:

```ts
readonly months = input.required<MonthSummary[]>();
```

`input()` è un **signal**: quando la pagina cambia i dati (ad esempio scegli "Ultimi 24 mesi"), tutti i
`computed` del grafico che lo leggono si ricalcolano da soli: la scala, le colonne, le etichette. È lo
stesso meccanismo dei signal che conosci dalla guida `docs/14`, solo che il valore arriva dal componente
padre. `required` vuol dire che senza quel dato il grafico non ha senso: se lo dimentichi, la
compilazione fallisce.

### Da euro a pixel

Tutto il grafico si basa su una funzione:

```ts
private y(value: number): number {
  const top = this.ticks()[this.ticks().length - 1]; // il valore più alto della scala
  return BASELINE - (value / top) * PLOT_HEIGHT;
}
```

In SVG la y cresce **verso il basso** (0 è in alto). Per questo si parte dalla linea dello zero
(`BASELINE`, in basso) e si **sottrae**: più il valore è alto, più la colonna sale.

La scala usa numeri "tondi": `niceStep` sceglie un passo tra 1, 2, 2,5 e 5 per una potenza di 10 (250,
500, 1.000...), il primo abbastanza grande da dividere il valore più alto in circa 4 parti.

### La colonna: un `<path>`

Una colonna arrotondata solo in cima non è un `<rect>` (che arrotonda tutti e quattro gli angoli): è un
`<path>`, una "penna" che segue dei comandi:

```
M x,base        sposta la penna in basso a sinistra
V top+r         linea verticale fino a poco sotto la cima
Q x,top x+r,top curva per l'angolo arrotondato
H x+w-r         linea orizzontale
Q ...           l'altro angolo
V base          giù fino alla base
Z               chiudi la forma
```

### Il passaggio del mouse

Ogni mese ha un rettangolo **invisibile** grande quanto tutta la fascia (`rect.hit`): è più facile da
"prendere" di una colonna sottile. Quando il mouse entra (`pointerenter`) o il rettangolo riceve il focus
con Tab (`focus`), il signal `active` prende il numero del mese, e il `computed` `tooltip` prepara il
riquadro con i valori. Il riquadro si apre a destra del mese; negli ultimi mesi si apre a sinistra, per
non uscire dal grafico.

### Il periodo: una data nel browser

```ts
export function firstMonth(today: Date, months: number): string {
  const first = new Date(today.getFullYear(), today.getMonth() - (months - 1), 1);
  ...
}
```

`new Date(2026, -2, 1)` non è un errore: JavaScript sistema da solo i mesi fuori scala, e il "mese -2 del
2026" diventa ottobre 2025. Un test verifica proprio il passaggio d'anno.

### I test

| Test | Cosa verifica |
|---|---|
| `SummaryPage` (4) | riquadri, avviso "da verificare", una riga per mese con il risparmio negativo in rosso; il periodo scelto arriva al backend; la pagina vuota invita a collegare una banca; `firstMonth` attraverso il cambio d'anno |
| `MonthlyChart` (3) | una colonna per serie e per mese, nessuna per lo zero; i valori al passaggio del mouse e con la tastiera; la scala a numeri tondi |

Un dettaglio: tra l'importo e `€` Angular mette uno spazio speciale che non va mai a capo (` `,
*non-breaking space*), così il simbolo non resta da solo sulla riga dopo. Nel test va scritto così.

---

## Parte D: provalo

1. `git pull`
2. Riavvia il backend (`.\mvnw.cmd spring-boot:run` dalla cartella `backend`).
3. Riavvia la dashboard (`npm start` dalla cartella `frontend`) e apri https://localhost:8443: ora si
   apre sul **Riepilogo**.
4. Passa con il mouse sulle colonne, poi prova con il tasto **Tab**.
5. Confronta un mese con la pagina Movimenti: le spese del mese devono essere la somma dei movimenti di
   tipo "Spesa" di quel mese.

**Se un numero ti sembra sbagliato**, quasi sempre è un movimento con il tipo sbagliato: correggilo nella
pagina Movimenti (o crea una regola) e il riepilogo si aggiorna.

Test:

```
cd backend;  .\mvnw.cmd test            # 88 test
cd frontend; npm test -- --watch=false  # 33 test
```

### Esercizi

1. Aggiungi il periodo "Ultimi 36 mesi". Basta una riga: quale? (Suggerimento: `PERIODS`.) Le etichette
   dei mesi restano leggibili?
2. Nel grafico, al passaggio del mouse, mostra anche il numero di movimenti da verificare del mese, solo
   se è maggiore di zero.
3. Nel backend, prova a chiedere `from=2026-10&to=2026-01` con il browser: che risposta ricevi? Dove nel
   codice viene deciso il messaggio?
