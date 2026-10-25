# 36. Fase grafica, passo 3: il Riepilogo

**Obiettivo:** la prima schermata dell'app risponde in un colpo d'occhio a "come sto andando questo mese?". Una cifra
grande (quanto ho speso finora) con la frase che la spiega rispetto al **solito**, il grafico del mese contro un mese
tipico, dove vanno i soldi, che cosa arriva nei prossimi giorni, che cosa c'è da sistemare. I grafici di 12 mesi, che
prima stavano qui, passano nella nuova sezione **Analisi**.

| Parte | Cosa |
|---|---|
| A | che cosa mostra la schermata |
| B | l'API: una richiesta, tutto già calcolato |
| C | il "solito": la media giorno per giorno dei mesi completi |
| D | la query: sommare per giorno nel database |
| E | il grafico del mese, in SVG |
| F | la pagina: dai numeri alle frasi |
| G | Analisi nella barra, e le pagine caricate quando servono |
| H | i test, e la prova nel browser |
| I | esercizi guidati |

---

## Parte A: che cosa mostra la schermata

Dall'alto:

1. **Il mese**: "Domenica 25 / Ottobre 2026", con le frecce per i mesi passati (non oltre quello in corso).
2. **Gli avvisi** (consenso della banca in scadenza, aggiornamento non riuscito): il pannello di `docs/21`, ora qui.
3. **La cifra**: "Speso finora **782 €**", e sotto la frase: "7 € più del solito a questo punto del mese". Poi il
   **grafico**: la spesa accumulata dal giorno 1 a oggi (accento) contro quella di un mese tipico (grigia). Sotto, le
   entrate del mese, piccole.
4. **Dove vanno**: le cinque categorie che pesano di più, ognuna con una barra (stesso colore per tutte: conta la
   lunghezza) e la differenza dal solito ("+32 € sul solito", "come al solito"). "Tutte" porta ad Analisi.
5. **In arrivo nei prossimi 7 giorni**: gli abbonamenti confermati (`docs/22`) attesi entro una settimana.
6. **Da sistemare**: "3 movimenti da verificare" (porta a Movimenti già filtrati) e "N categorie suggerite".

Una sezione vuota non si mostra (niente "In arrivo" se non arriva niente). Un mese passato si vede intero: "Speso nel
mese", "Tutto il mese", e nessun "in arrivo".

Sul **PC** (da 1100 pixel) la pagina ha due colonne: a sinistra la cifra con il grafico, a destra il resto.

---

## Parte B: l'API, una richiesta e tutto già calcolato

```
GET /api/overview?month=2026-10&currency=EUR
```

Senza `month` vale il mese in corso; senza `currency` la valuta con più spese. La risposta (`OverviewDto`) contiene
già **tutti** i numeri della schermata:

| Campo | Che cos'è |
|---|---|
| `day` | il giorno fino a cui contano i numeri: oggi, o l'ultimo giorno per un mese passato |
| `spent`, `income` | spese ed entrate dal giorno 1 a `day` |
| `spentByDay` | la spesa accumulata, un valore per giorno: la linea del grafico |
| `usualMonths` | da quanti mesi è calcolato il solito (0 = nessun confronto) |
| `usualSoFar`, `usualMonth` | di solito, a questo giorno del mese e in un mese intero |
| `usualByDay` | il mese tipico, giorno per giorno: la linea grigia |
| `categories` | le categorie con spese nel mese, ognuna con il suo solito |
| `upcoming` | gli abbonamenti attesi entro 7 giorni |
| `toReview`, `suggestions` | quanti movimenti da verificare, quante categorie suggerite |

Perché tutto in una risposta, invece di far sommare i movimenti alla pagina? Sul telefono, con rete lenta, scaricare
migliaia di movimenti per sommarli sarebbe lento e pesante; e i calcoli (il "solito" soprattutto) vanno **provati**
con dei test, che in Java sono più semplici. La pagina riceve pochi numeri e li mette in parole.

Contano gli stessi movimenti del riepilogo di 12 mesi (`docs/18`): spese (`EXPENSE`) ed entrate (`INCOME`) vere,
anche in attesa. Giroconti, saldo della carta, investimenti e movimenti ignorati non sono né spese né entrate; quelli
da verificare restano fuori, e la schermata dice quanti sono.

Le richieste sbagliate ricevono 400 con un messaggio: un mese futuro ("non è ancora cominciato"), più di 120 mesi fa,
una valuta che non è di tre lettere.

`OverviewService.overview(month, currency, today)` riceve **oggi** come parametro: il controller passa
`LocalDate.now()`, i test passano il 15 ottobre 2026. Così un test dà lo stesso risultato qualunque giorno giri.

---

## Parte C: il "solito", la media giorno per giorno dei mesi completi

La frase "126 € in meno del solito" è la cosa più importante della schermata, quindi deve essere **giusta**.

**Giorno per giorno.** Il 15 ottobre ho speso 170 €. Di solito in un mese intero spendo 230 €: "60 € in meno del
solito"? No: il mese non è finito. Il confronto giusto è con quanto avevo speso **di solito entro il 15** (diciamo
130 €): "40 € più del solito a questo punto del mese". Per questo il backend calcola, per ogni mese passato, la spesa
accumulata giorno per giorno, e ne fa la media.

**I mesi sono lunghi diversi.** Il giorno 31 di settembre non esiste: per settembre, "entro il 31" vuol dire il mese
intero. `MonthCurves.upTo` usa `Math.min(day, month.lengthOfMonth())`.

**Quali mesi.** I sei mesi prima di quello guardato (abbastanza per non dipendere da un mese strano, e recenti), ma
solo quelli conosciuti **per intero**: un mese conta se comincia dopo il primo movimento della storia. Se il conto è
stato collegato il 10 agosto, agosto ha solo venti giorni di spese, e nella media farebbe sembrare il solito più basso:
si parte da settembre. Senza nessun mese completo, il solito non c'è (`null`) e la pagina lo dice: "Il confronto con
il solito comincia dopo il primo mese completo di movimenti".

**Le categorie** hanno lo stesso calcolo, ognuna con le sue spese: "Bar e ristoranti: 50 €, di solito 30 € entro il
15".

Tutti questi calcoli stanno in `MonthCurves`, una classe di **funzioni pure**: ricevono le spese già lette e
restituiscono numeri, senza database e senza Spring. Si provano con dati scritti a mano (`MonthCurvesTest`) in pochi
millisecondi; il servizio (`OverviewService`) si limita a leggere i dati e a chiamarle.

Le medie hanno due decimali, arrotondate "come a scuola":

```java
total.divide(BigDecimal.valueOf(months.size()), 2, RoundingMode.HALF_UP)
```

Con `BigDecimal` una divisione va sempre detta **come** arrotondare: 10 / 3 non ha un numero finito di decimali, e
senza scala e arrotondamento `divide` lancerebbe un'eccezione.

---

## Parte D: la query, sommare per giorno nel database

```sql
SELECT t.bookingDate AS day, t.currency AS currency, t.type AS type,
       c.id AS categoryId, c.name AS category, SUM(t.amount) AS total, COUNT(t) AS movements
FROM BankTransaction t LEFT JOIN t.category c
WHERE t.type IN (EXPENSE, INCOME) AND t.bookingDate >= :from AND t.bookingDate < :to
GROUP BY t.bookingDate, t.currency, t.type, c.id, c.name
```

Una sola query per sette mesi (i sei del solito più quello guardato): il database restituisce una riga per giorno,
valuta, tipo e categoria, già sommata. Sono qualche centinaio di righe, qualunque sia il numero di movimenti. Come in
`docs/18`, la risposta è una **proiezione** (`interface DailyTotal` con i getter dei nomi dopo `AS`).

`LEFT JOIN` tiene anche le spese senza categoria (con `c.id` e `c.name` a null): nella pagina diventano "Senza
categoria".

Altre due query piccole: `findFirstDate(currency)` (il primo movimento, per sapere quali mesi sono completi) e
`countByTypeOrTypeIsNull(TO_REVIEW)`, che Spring Data ricava dal **nome del metodo**: "conta quelli con tipo uguale
al parametro, oppure con tipo nullo".

Gli abbonamenti in arrivo vengono da `SubscriptionService.overview(today)`, già pronto (`docs/22`): si tengono quelli
confermati, non "in ritardo" (probabilmente disdetti), con il prossimo pagamento tra oggi e fra sette giorni.

---

## Parte E: il grafico del mese, in SVG

`MonthChart` (`overview/month-chart.ts`) è un componente nostro, come il grafico mensile di `docs/18`:

- l'SVG ha un `viewBox` largo 340 e alto 130: si disegna in queste coordinate, e il browser lo allarga quanto la
  scheda (`width: 100%`);
- `x(day)` va da 0 (giorno 1) a 340 (ultimo giorno); `y(value)` da 112 (zero, in basso) verso l'alto. Nell'SVG la
  y cresce **verso il basso**: per questo `y = 112 - valore × scala`;
- una curva è un tracciato `M x y L x y L x y...` ("muoviti qui, poi traccia una linea fin qui");
- l'area sotto la linea del mese è lo stesso tracciato, chiuso giù fino alla base (`L ... 112 L 0 112 Z`), riempita
  con l'accento al 10%: si vede quanto è stato speso senza coprire la linea grigia;
- il **punto** di oggi segna dove finisce il mese: la linea grigia continua fino al 31 e dice quanto si spende di
  solito da qui in avanti;
- una o due linee di riferimento a valori tondi (500 €, 1.000 €), scelte dalla lista `STEPS` in modo da averne poche;
- `role="img"` e `aria-label` con una frase ("Spesa accumulata nel mese: 782 € al giorno 25, contro 775 € di un mese
  tipico allo stesso giorno"): chi usa un lettore di schermo ha la stessa informazione.

Le scritte del grafico si allargano con lui: sul PC il disegno è quasi il doppio, e le scritte anche. Da 600 pixel in
su il CSS le scrive più piccole, così sullo schermo restano di circa 12 pixel.

Un dettaglio già incontrato nelle bozze: `toLocaleString('it-IT')` **non** mette il punto delle migliaia sotto 10.000
(scrive "1000 €"). Le etichette lo aggiungono con un'espressione regolare.

---

## Parte F: la pagina, dai numeri alle frasi

`OverviewPage` chiede i numeri e li mette in parole. Le frasi hanno più casi e sono funzioni esportate, provate da
sole:

```ts
export function comparisonSentence(o: Overview, isCurrent: boolean, euros: (v: number) => string): string {
  if (o.usualSoFar === null) {
    return 'Il confronto con il solito comincia dopo il primo mese completo di movimenti.';
  }
  const when = isCurrent ? ' a questo punto del mese' : '';
  const difference = o.spent - o.usualSoFar;
  if (Math.abs(difference) < SAME_AS_USUAL) {
    return `In linea con il solito${when}.`;
  }
  return difference < 0
    ? `${euros(-difference)} in meno del solito${when}.`
    : `${euros(difference)} più del solito${when}.`;
}
```

- Sotto i 5 € di differenza si è "in linea": 3 € di scarto non sono una notizia.
- Gli importi sono senza decimali ("782 €"): a colpo d'occhio i centesimi non servono. `formatCurrency` con
  `'1.0-0'` vuol dire "almeno una cifra intera, da zero a zero decimali".
- Il nome del mese viene da `formatDate(..., 'MMMM y', locale)` ("ottobre 2026") con la prima lettera maiuscola.

**Le frecce dei mesi** cambiano un signal `month` e richiedono il Riepilogo; come nella ricerca (`docs/32`), ogni
richiesta ha un numero e conta solo la risposta all'ultima (premendo in fretta, una risposta vecchia non sovrascrive
quella nuova). La freccia "avanti" è disattivata nel mese in corso.

**"Da verificare"** porta a `/movimenti?type=TO_REVIEW`. La pagina Movimenti ora legge quel parametro all'avvio
(`ActivatedRoute.snapshot.queryParamMap`) e lo usa come filtro di partenza; un valore che non è un tipo valido si
ignora.

I due riquadri hanno un `aria-label` ("3 movimenti da verificare"): il numero e la parola sono due elementi, e così un
lettore di schermo li legge come una frase sola.

---

## Parte G: Analisi nella barra, e le pagine caricate quando servono

**Analisi.** La pagina di 12 mesi (entrate e spese mese per mese, risparmio, categorie del periodo) è la vecchia
pagina Riepilogo: ora si chiama Analisi, sta in `/analisi` ed entra nella barra (cinque voci: Riepilogo, Movimenti,
Analisi, Budget, Altro). Nel passo 5 è diventata l'esploratore progettato in `docs/33`, parte L (`docs/38`).

**Lazy loading.** Con la pagina nuova la build ha avvisato: il pacchetto iniziale superava di 3,5 kB il limite di
500 kB che Angular controlla (`budgets` in `angular.json`). Invece di alzare il limite, le pagine si caricano **quando
servono**:

```ts
{ path: 'budget', loadComponent: () => import('./budget/budget-page').then((m) => m.BudgetPage), ... }
```

`import('...')` (con le parentesi, una funzione) dice alla build di mettere quella pagina in un file a parte; il
browser lo scarica la prima volta che si apre la pagina. Solo il Riepilogo, che si apre per primo, resta nel pacchetto
iniziale. Risultato: **da 503 a 331 kB** all'avvio, e sul telefono l'app parte prima.

---

## Parte H: i test, e la prova nel browser

**Backend**

- `MonthCurvesTest` (funzioni pure, senza database): la curva si accumula giorno per giorno e un rimborso la riduce;
  il solito è la media allo stesso giorno, e un mese di 30 giorni vale intero il 31; senza mesi non c'è solito; le
  medie hanno due decimali; contano solo i mesi conosciuti dal primo giorno, al massimo sei; le spese senza
  categoria.
- `OverviewServiceTest` (database vero, oggi = 15 ottobre 2026, storia inventata da aprile): spese finora e solito
  allo stesso giorno (170 € contro 130 €), giroconto e movimento da verificare esclusi, le categorie con il loro
  solito; un abbonamento confermato atteso il 18 è "in arrivo" il 15 ma non il 10, e non in un mese passato; settembre
  si vede intero con il solito di cinque mesi; i dollari hanno i loro numeri e nessun solito; mese futuro, troppo
  vecchio, valuta sbagliata: 400.

**Frontend**

- `overview-page.spec.ts`: la cifra, la frase e il grafico con la sua descrizione; le cinque categorie con la
  differenza dal solito e il link ad Analisi; in arrivo e da sistemare, con i link; le frecce dei mesi (non oltre il
  mese in corso); la scelta della valuta; l'errore; le frasi nei loro casi.
- `month-chart.spec.ts`: un punto per giorno fino a oggi, il mese tipico fino in fondo, più spesa = più in alto, le
  linee di riferimento con il punto delle migliaia.
- `transactions-page.spec.ts`: `?type=TO_REVIEW` è il filtro di partenza, un tipo sconosciuto si ignora.

**Nel browser** (Playwright, sette mesi di movimenti inventati, due abbonamenti confermati): telefono e PC, chiaro e
scuro, nessuna pagina che sborda; la frase ("7 € più del solito a questo punto del mese"); il mese precedente ("In
linea con il solito", entrate del mese); "Tutte" e la voce Analisi portano ad Analisi; "3 movimenti da verificare"
apre Movimenti con i tre movimenti filtrati.

---

## Parte I: esercizi guidati

1. **La media o la mediana?** Un mese con una spesa eccezionale (il dentista, 800 €) alza il solito per sei mesi.
   Prova a usare la **mediana** dei mesi invece della media in `usualUpTo`: che cosa cambia nei test? Quale delle due
   descrive meglio "il solito"?
2. **Le categorie che mancano.** Una categoria che di solito c'è (l'affitto) ma questo mese non ancora non compare.
   Aggiungi le categorie con un solito sopra i 50 € anche se a zero: come scriveresti la frase per non spaventare il 5
   del mese?
3. **Il mese nell'indirizzo.** Metti il mese guardato nell'indirizzo (`/riepilogo?mese=2026-09`), come il filtro dei
   Movimenti: il tasto "indietro" del telefono torna al mese di prima.
