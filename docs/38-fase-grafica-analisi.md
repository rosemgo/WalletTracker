# 38. Fase grafica, passo 5: Analisi, l'esploratore e la pagina di una categoria

**Obiettivo:** capire **in ogni categoria** che spese e che entrate ci sono, come nell'esploratore dell'app Revolut ma
meglio (`docs/33`, parte L). Che cosa guardare (spese, entrate, netto) × su che periodo (settimana, mese, 6 mesi,
anno) × come raggruppare (categoria, esercente, conto): si tocca una barra per guardare quel periodo, una categoria
per guardare solo lei. In più, la **pagina di una categoria**: il mese rispetto al solito, il budget con la tacca del
ritmo, gli ultimi 12 mesi, dove.

| Parte | Cosa |
|---|---|
| A | che cosa mostra Analisi |
| B | le API: la serie, i gruppi, la pagina di una categoria |
| C | i calcoli: barre, media di solito, gruppi |
| D | la pagina: le scelte nell'indirizzo |
| E | il grafico a barre, toccabile anche con la tastiera |
| F | la pagina di una categoria e la tacca del ritmo |
| G | che cosa sparisce: il vecchio riepilogo di 12 mesi |
| H | i test, e quello che ha trovato il browser |
| I | esercizi guidati |

---

## Parte A: che cosa mostra Analisi

Dall'alto:

1. **Spese · Entrate · Netto**: un interruttore a tre posizioni.
2. **La scheda della cifra**: "Spese · ottobre 2026", la cifra grande, "Di solito 682 € al mese". Con una categoria
   scelta compare la sua pillola (✕ per toglierla) e il link alla **pagina della categoria**.
3. **Il grafico**: una barra per giorno, settimana o mese. La barra guardata ha l'accento, le altre sono grigie, una
   linea continua segna la **media di solito**. Si tocca una barra e tutta la pagina passa a quel periodo. Nel
   **netto** le barre vanno in su (risparmiato) o in giù (speso più di quanto è entrato), in due colori.
4. **Settimana · Mese · 6 mesi · Anno**: con la settimana le barre sono i giorni, con il mese le settimane (1–7,
   8–14, 15–21, 22–28, 29–31), con 6 mesi e un anno i mesi. Si parte sempre dall'ultima barra già successa.
5. **Per Categoria · Esercente · Conto**.
6. **L'elenco**: per ogni gruppo l'importo, la percentuale, una barra sottile, quanti movimenti e, per le categorie
   di un mese, la **differenza dal solito** allo stesso giorno ("+32 € sul solito"). Toccando una categoria la si
   sceglie: il grafico mostra solo lei, l'elenco i suoi esercenti, e sotto compaiono **i suoi movimenti** del
   periodo, giorno per giorno (ognuno apre la sua scheda, `docs/37`).
7. Nel **netto** l'elenco si divide in **Entrate** e **Uscite**.

Sul **PC** (da 1100 pixel) due colonne: a sinistra la cifra, il grafico e le scelte; a destra l'elenco e i
movimenti.

Dove WalletTracker fa meglio di Revolut, in breve: conta tutte le banche, esclude i giroconti tra i tuoi conti, ha le
tue categorie, confronta con **il solito** (non con il mese prima) e ha la pagina della categoria con il budget.

---

## Parte B: le API, la serie, i gruppi, la pagina di una categoria

Tre API in `ExploreController`, con la logica in `ExploreService` e i calcoli in `Explorer`.

### `GET /api/explore/series?metric=EXPENSES&period=YEAR&end=2026-10-25&categoryId=7`

Le barre del grafico (`Series`): per ognuna `from`, `to`, `value` e `future` (non ancora successa), più l'unità
(`DAY`, `WEEK`, `MONTH`) e la **media di solito per barra** (`averagePerUnit`). Senza `end`, oggi; senza
`categoryId`, tutte le categorie. `metric` è `EXPENSES`, `INCOME` o `NET`; `period` è `WEEK`, `MONTH`,
`SIX_MONTHS` o `YEAR`. Un valore sconosciuto è un 400: Spring converte da solo il testo nell'enum.

### `GET /api/explore/groups?metric=EXPENSES&from=2026-10-01&to=2026-10-31&by=CATEGORY&categoryId=7`

I movimenti di una barra raggruppati per categoria, esercente (`MERCHANT`) o conto (`ACCOUNT`), dal più alto, con il
totale. Per il netto, `income` ed `expenses` in due elenchi. Al massimo un anno.

### `GET /api/categories/7/overview?month=2026-10`

La pagina di una categoria (`CategoryOverview`): quanto nel mese (fino a oggi per il mese in corso), il solito allo
stesso giorno e in un mese intero, gli ultimi 12 mesi, gli esercenti del mese, il budget (da `BudgetService`, con il
suo `expectedSoFar`, `docs/20`). Per una categoria di **entrata** (lo stipendio) guarda le entrate.

Infine `GET /api/transactions` accetta `categoryId`: i movimenti di una categoria ("Vedi i movimenti" nella pagina
della categoria, e i movimenti sotto l'esploratore). È una condizione in più nella `Specification` della ricerca
(`docs/32`): `cb.equal(category.get("id"), filter.categoryId())`.

---

## Parte C: i calcoli, barre, media di solito, gruppi

Come nel Riepilogo (`docs/36`), i calcoli sono **funzioni pure** in una classe a parte (`Explorer`): ricevono i
movimenti già letti (`Movement`: giorno, importo, tipo, categoria, conto, esercente) e restituiscono numeri. Il
servizio legge i movimenti del periodo con **una query** (`findMovementsBetween`, con categoria e conto già caricati)
e passa tutto a `Explorer`.

**Il valore di una barra** (`Explorer.value`): le spese vere (`EXPENSE`, girate in positivo: "ho speso 120 €"), le
entrate vere (`INCOME`), o il netto (la somma con il segno: entrate meno spese). Giroconti, saldo carta, investimenti
non contano, come in tutta l'app.

**Le barre** (`Explorer.bars`): il mese è diviso in blocchi di sette giorni **dal primo** (1–7, 8–14...), non nelle
settimane del calendario: così nessuna barra sta a cavallo di due mesi, e le barre di un mese si confrontano tra loro.
La settimana va da lunedì a domenica (`TemporalAdjusters.previousOrSame(MONDAY)`).

**La media di solito** (`Explorer.averagePerUnit`): i sei mesi completi prima di quello in corso (`MonthCurves`,
`docs/36`), il totale diviso i **giorni** (al giorno; × 7 a settimana) o i **mesi** (al mese). Dividere per i giorni
veri (61 per agosto e settembre) evita di contare un "mese medio" di 30 giorni.

**I gruppi** (`Explorer.groups`): i movimenti del periodo in una mappa "chiave → movimenti" (`LinkedHashMap`),
con la chiave scelta da una funzione (`Function<Movement, Object>`): l'id della categoria, la chiave dell'esercente
(`MerchantKey`, `docs/22`) o l'id del conto. Il nome di un esercente è quello del suo movimento più recente. Per le
categorie di un mese, ogni gruppo ha il **solito allo stesso giorno** (`usualUpTo`): il 15 ottobre si confronta con
quanto si spendeva di solito entro il 15.

---

## Parte D: la pagina, le scelte nell'indirizzo

`ExplorePage` tiene le scelte in signal (`metric`, `period`, `by`, `category`, `selected`) e le scrive
nell'**indirizzo**:

```
/analisi?cosa=spese&periodo=anno&per=esercente&categoria=1&nome=Spesa%20alimentare&barra=11
```

con `router.navigate([], { queryParams, replaceUrl: true })`: ricaricando la pagina, o tornando ad Analisi dalla
scheda di un movimento, si ritrova la stessa vista. `replaceUrl` **sostituisce** l'indirizzo invece di aggiungere un
passo alla cronologia: altrimenti il tasto "indietro" ripercorrerebbe ogni barra toccata. Le parole dell'indirizzo
sono italiane (`spese`, `anno`): sono quelle che una persona vede.

Due richieste per ogni cambiamento:

- **la serie** quando cambiano metrica, periodo, categoria o valuta;
- **i gruppi** (e, con una categoria, i suoi movimenti) quando cambia la barra o il raggruppamento.

Come nella ricerca (`docs/32`) ogni richiesta ha un numero, e conta solo la risposta all'ultima. Mentre arrivano i
gruppi nuovi, l'elenco di prima è **attenuato e non si tocca** (`pointer-events: none`): appartiene a un altro
periodo.

Le frasi (`groupNote`: "4 movimenti · +32 € sul solito") e i nomi delle barre (`barName`: "lunedì 19 ottobre",
"1–7 ottobre", "ottobre 2026") sono funzioni esportate, provate da sole.

---

## Parte E: il grafico a barre, toccabile anche con la tastiera

`BarChart` serve sia ad Analisi sia alla pagina della categoria:

- le barre sono sottili (al massimo 22 unità) e **arrotondate solo dal lato del valore**: dritte sulla linea dello
  zero, che resta un riferimento netto;
- la scala comprende anche la media e i valori negativi (il netto): lo zero è dove serve, non sempre in basso;
- con `interactive`, sopra ogni barra c'è un rettangolo trasparente **alto quanto il grafico**: anche una barra bassa
  si prende con il pollice. Il rettangolo ha `role="button"`, `tabindex="0"` (si raggiunge con Tab), un nome per i
  lettori di schermo ("ottobre 2026: 661 €"), `aria-pressed` per quella scelta, e risponde a Invio e Spazio. Le barre
  future non hanno il rettangolo: non si possono scegliere.

Le scritte del grafico si allargano con lui; da 600 pixel in su il CSS le scrive più piccole (come in `docs/36`).

---

## Parte F: la pagina di una categoria e la tacca del ritmo

`/analisi/categoria/7` (`CategoryPage`):

1. il nome, la cifra del mese e la frase: "a ottobre, finora · 11 € meno del solito (257 €)";
2. il **budget**, se c'è: "Budget 260 € · 14 € rimasti · 6 giorni" e la barra con la **tacca del ritmo**: dove
   dovresti essere oggi spendendo in modo regolare (`expectedSoFar` / budget). Barra oltre la tacca = stai andando
   troppo veloce. Superato, la barra diventa rossa e la frase dice "superato di 20 €". Un mese passato non ha la
   tacca (il mese è finito). Senza budget, il link per crearne uno;
3. **gli ultimi 12 mesi** con la media di solito (lo stesso `BarChart`, solo da guardare);
4. **dove**: gli esercenti del mese, con quante volte e quanto;
5. **Vedi i movimenti** (Movimenti con la pillola della categoria, che si toglie con un tocco) e **Cambia il budget**.

Ci si arriva dall'esploratore ("La pagina della categoria", con il mese della barra guardata) e dal **Riepilogo**:
ogni categoria di "Dove vanno" ora è un link.

---

## Parte G: che cosa sparisce, il vecchio riepilogo di 12 mesi

La pagina di `docs/18` (entrate e spese mese per mese, risparmio, categorie del periodo) e il suo grafico
(`summary/`) sono stati **tolti**: l'esploratore fa le stesse cose e di più (le spese per mese sono "Spese · Anno",
il risparmio è "Netto · Anno", le categorie del periodo sono l'elenco). L'API `GET /api/summary/monthly` resta nel
backend con i suoi test, perché la guida `docs/18` la spiega.

Una cosa non c'è più nell'interfaccia: le **rendite nette degli investimenti** (cedole e dividendi meno le tasse).
Gli investimenti sono tenuti a parte apposta; avranno una pagina loro (esercizio 3).

---

## Parte H: i test, e quello che ha trovato il browser

**Backend**

- `ExplorerTest` (funzioni pure): le barre di settimana, mese (anche febbraio: quattro barre) e anno, con le future;
  spese, entrate e netto (un rimborso riduce le spese, un giroconto non conta); la media al giorno, a settimana e al
  mese; i gruppi per categoria (con il solito), esercente (con il nome più recente) e conto; il solito di una
  categoria allo stesso giorno.
- `ExploreServiceTest` (database vero, oggi = 15 ottobre 2026): l'anno con la media di 230 € al mese, il netto, una
  categoria sola; il mese a settimane con le future; i gruppi del mese con il solito; la barra del mese in corso che
  arriva al 31; il netto in due elenchi; la pagina della categoria con il budget; una categoria di entrata; le
  richieste sbagliate (400 e 404).
- `TransactionControllerTest`: il filtro per categoria.

**Frontend**: `explore-labels.spec.ts`, `bar-chart.spec.ts` (barra scelta, media, netto in due colori, pulsanti anche
da tastiera, niente pulsanti sulle barre future), `explore-page.spec.ts` (la vista di partenza, barra, periodo,
raggruppamento, netto, categoria con esercenti e movimenti, l'indirizzo, l'errore), `category-page.spec.ts` (budget e
tacca, mese passato, budget superato, senza budget), più la pillola della categoria nei Movimenti e i link del
Riepilogo.

**Nel browser** (Playwright, un anno di movimenti inventati con un mese dal dentista e un budget):

- la prima prova ha trovato un errore che i test non vedevano: per il **mese in corso** la barra arriva al 31, la
  pagina chiedeva i gruppi fino al 31, e il backend rifiutava un giorno futuro con 400. La pagina **ignorava**
  l'errore e lasciava l'elenco di un altro periodo sotto il titolo nuovo. Correzioni: il backend accorcia il periodo
  a oggi (un test lo prova), la pagina mostra l'errore e toglie l'elenco vecchio, e mentre carica lo attenua;
- poi: marzo (il dentista) a 3.030 € con "Cure sanitarie e farmacia +2.400 € sul solito"; il netto di marzo negativo
  (−930 €, la barra in giù); la settimana 22–28 ottobre; la categoria scelta con esercenti e quattro movimenti; la
  vista che resta ricaricando; la pagina della categoria con "11 € meno del solito", "14 € rimasti · 6 giorni" e la
  tacca; "Vedi i movimenti" con la pillola; dal Riepilogo alla pagina di una categoria; una barra scelta con Tab e
  Invio; telefono e PC, giorno e notte, nessuna pagina che sborda;
- sul PC il grafico a tutta larghezza era enorme: ora Analisi ha due colonne e la pagina della categoria è larga al
  massimo 720 pixel.

---

## Parte I: esercizi guidati

1. **Il confronto della barra.** Sotto la cifra, per una barra-mese, aggiungi la differenza dal solito ("120 € più
   del solito"), come nel Riepilogo. Hai già tutto: il valore della barra e `averagePerUnit`. Perché per il mese in
   corso non va bene, e che cosa servirebbe?
2. **Un periodo a scelta.** Revolut ha anche "dal… al…". Che cosa cambia nelle barre (`Explorer.bars`) se il periodo
   è di 45 giorni? E di due anni?
3. **Investimenti.** Una pagina che mostra per mese le rendite (`INVESTMENT_INCOME`), le tasse (`INVESTMENT_TAX`) e il
   netto: puoi riusare `BarChart` con `diverging`. Quale `Metric` aggiungeresti, e dove?

---

## Aggiunta: "Quest'anno" (l'anno in corso, YTD)

Richiesta dell'utente: poter guardare i grafici **dall'inizio dell'anno a oggi** (YTD, *year to date*).

- **Il periodo nuovo** `YEAR_TO_DATE` (`ExploreDtos.Period`): le barre sono i mesi da gennaio al mese di oggi
  (`Explorer.bars`): a novembre undici barre, a gennaio una sola. Si chiama **"Quest'anno"**, come il filtro di
  Movimenti e la scheda "Investito" del Riepilogo; nell'indirizzo `?periodo=questanno`.
- **"Anno" diventa "12 mesi"**: erano gli ultimi 12 mesi, non l'anno solare, e con "Quest'anno" accanto il vecchio nome
  avrebbe confuso. Nell'indirizzo resta `?periodo=anno`, così i link salvati continuano a funzionare.
- **Sotto le barre** l'iniziale del mese, come per i 12 mesi (`explore-labels.ts`, `barLabel`).
- **Sul telefono** cinque pulsanti in fila non ci stavano ("Settimana" e "Quest'anno" erano tagliati): con cinque
  scelte, sotto i 520 pixel, ogni pulsante prende la larghezza del suo testo, con un carattere un poco più piccolo
  (`.segmented:has(> button:nth-child(5))`). Provato a 390 e a 360 pixel.

Lo stesso periodo c'è nella pagina Investimenti (`docs/investimenti.md`, parte N).

**I test.** `ExplorerTest.theBarsOfEachPeriod` (dieci mesi a fine ottobre, uno a gennaio); `explore-page.spec.ts`
(il pulsante chiede `YEAR_TO_DATE` e scrive `questanno` nell'indirizzo).

**Anche nella pagina di una categoria.** Il grafico aveva sempre gli ultimi 12 mesi; ora sopra ha **12 mesi ·
Quest'anno**. Qui non serve cambiare l'API: dà già i 12 mesi che finiscono con il mese guardato, e da gennaio di
quell'anno ci sono sempre tutti. Il frontend tiene solo quelli dello stesso anno (`category-page.ts`, `chartMonths`).
Il titolo dice "Quest'anno" per l'anno in corso e "Gennaio–ottobre 2025" per un mese di un anno passato. Test:
`category-page.spec.ts` (12 barre, poi 10 da gennaio a ottobre).

---

## Aggiunta: niente riquadro bianco dopo aver toccato una barra

**Il difetto, visto dall'utente sul telefono.** Toccando una barra compariva un riquadro bianco arrotondato attorno a
lei, alto quanto il grafico.

**Perché.** Ogni barra ha un'area toccabile (`rect.hit`) con `tabindex="0"`, per poterla scegliere anche con la
tastiera. Dopo un tocco l'area ha il focus; lo stile toglieva il contorno solo per il focus della tastiera
(`:focus-visible`, dove disegna un bordo dell'accento), e per il focus dopo un tocco restava il contorno del browser
(`outline: auto`): il riquadro bianco.

**La correzione.** `.bar-chart .hit:focus { outline: none; }`: dopo un tocco niente contorno (la barra scelta si vede
già, accesa); con la tastiera il bordo dell'accento resta. Vale per tutti i grafici a barre (Analisi, una categoria,
Investimenti). I grafici a linea (Riepilogo, Investimenti) non avevano il difetto.

**La prova.** Con Playwright e un telefono simulato (Pixel 7, tocchi veri): prima, dopo il tocco, `outline: auto`
e il riquadro nella cattura; dopo, `outline: none` e nessun riquadro; con il tasto Tab la barra ha ancora il bordo.
È solo CSS: i test dei componenti (jsdom) non disegnano la pagina.

---

## Aggiunta: la pagina di una categoria, nel mese giusto e con le barre da toccare

Due difetti trovati dall'utente.

**1. Dal Riepilogo di ottobre si apriva novembre.** Il link di una categoria in "Dove vanno" non diceva il mese, e la
pagina della categoria prende allora quello in corso. Ora il link porta `?mese=` con il mese guardato nel Riepilogo
(come facevano già il Budget e Analisi).

**2. Le barre non si potevano toccare.** Ora funzionano come in Analisi:

- toccando una barra si accende, e sopra il grafico c'è il mese con la sua cifra ("settembre 2026: 85,40 €");
- la cifra grande con il confronto con il solito, il budget e **"Dove"** passano a quel mese ("Dove, a settembre");
- **il grafico resta fermo**: sono i 12 mesi del primo caricamento. Se ogni tocco ricaricasse anche le barre, l'API
  darebbe i 12 mesi che finiscono con quello toccato e il grafico "scapperebbe" sotto il dito. Nel codice
  (`category-page.ts`) ci sono due dati: `chart` (le barre e la loro media, fermi) e `overview` (il mese guardato,
  ricaricato con `categoryOverview(id, mese)` a ogni tocco);
- l'indirizzo diventa `?mese=2026-09` (`replaceUrl`: il tasto indietro non deve ripassare per ogni barra toccata), così
  ricaricando la pagina si resta sullo stesso mese;
- la barra accesa è quella del mese guardato, non per forza l'ultima.

**I test.** `category-page.spec.ts`, "touching a bar shows that month, its figure and where; the chart stays as it
is"; `overview-page.spec.ts` (il link porta `?mese=`). Nel browser, con un telefono simulato: dal Riepilogo di
ottobre, "Spesa alimentare" apre ottobre (140 €); toccando settembre, 85 €, "settembre 2026: 85,40 €" e "Dove, a
settembre", con le stesse 12 barre.

**Il grafico arriva sempre fino al mese in corso.** Con il link del Riepilogo, aperta a ottobre, la pagina mostrava i 12
mesi fino a ottobre, e novembre (il mese in corso) spariva. Ora si caricano due cose (`forkJoin`): il grafico senza
mese, cioè fino a oggi, e il mese aperto per la cifra, il budget e "Dove". La barra accesa è quella del mese aperto.
Solo un mese più vecchio delle 12 barre (per esempio da un budget di due anni fa) ha il grafico che finisce con lui,
altrimenti la sua barra non ci sarebbe. Test: `category-page.spec.ts`, "opened on an earlier month, the chart still
reaches the current month". Nel browser: dal Riepilogo di ottobre, barre fino a novembre con ottobre acceso.

## Aggiunta: "Quest'anno" di serie

Richiesta dell'utente: aprire Analisi sull'anno in corso, non sugli ultimi 12 mesi. Senza `?periodo=` nell'indirizzo
il periodo è **"Quest'anno"** (`YEAR_TO_DATE`), e il suo pulsante viene prima di "12 mesi" (`PERIODS` in
`explore-page.ts`). Lo stesso nel grafico della pagina di una categoria (`chartPeriod` in `category-page.ts`) e nella
pagina Investimenti (`docs/investimenti.md`, parte R). A gennaio "Quest'anno" ha una barra sola: è l'anno, appena
cominciato; "12 mesi" è a un clic.

**I test.** `explore-page.spec.ts` (la prima richiesta è `YEAR_TO_DATE`, nell'indirizzo `questanno`, il pulsante
prima di "12 mesi"); `category-page.spec.ts` (dieci barre da gennaio, poi "12 mesi").

