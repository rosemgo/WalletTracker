# 39. Fase grafica, passo 6: Budget e le pagine di "Altro"

**Obiettivo:** portare nel nuovo stile le pagine rimaste: il **Budget** (che diventa una pagina da guardare, non
solo da compilare) e le pagine di **Altro** (Conti, Abbonamenti, Regole, Categorie), che sul telefono erano tabelle
larghe da scorrere di lato. In più, un difetto trovato strada facendo: gli errori di aggiornamento delle banche
arrivavano all'utente come messaggi tecnici.

| Parte | Cosa |
|---|---|
| A | che cosa cambia, pagina per pagina |
| B | il Budget: il totale, l'ordine, il ritmo in parole |
| C | le tabelle sul telefono: una scheda per riga, solo CSS |
| D | le Categorie: la variante compatta |
| E | gli errori di aggiornamento in parole (`SyncErrors`) |
| F | Regole e Abbonamenti: meno da scorrere |
| G | i test, e quello che ha trovato il browser |
| H | esercizi guidati |

---

## Parte A: che cosa cambia, pagina per pagina

| Pagina | Prima | Adesso |
|---|---|---|
| Budget | titolo, tabella di barre, modulo | il mese come nel Riepilogo (frecce), **il totale** in una scheda grande, una **scheda per budget** con stato, barra, ritmo in parole, link alla pagina della categoria; in alto quelli superati |
| Conti | tabella di 6 colonne | sul telefono una scheda per conto |
| Abbonamenti | tre riquadri di numeri, tabella | il costo al mese in grande (all'anno sotto), una scheda per abbonamento |
| Regole | 24 regole di partenza in tabella (9.300 px sul telefono) | prima **le tue** regole; quelle di partenza in una sezione chiusa che si apre al tocco |
| Categorie | tabella larga | una riga compatta per categoria, una scheda per gruppo |
| Riepilogo e Conti | "EnableBankingNotConfiguredException: 409 CONFLICT…" | "Enable Banking non è ancora configurato: apri la pagina Impostazioni." |

Le API del Budget, delle regole e delle categorie **non cambiano**: è tutto lavoro di pagina e di CSS, tranne la
Parte E.

---

## Parte B: il Budget

### Il totale

In cima una scheda con una cifra sola: quanto hai speso **nelle categorie che hanno un budget**, su quanto. È un
`computed` sui budget che la pagina ha già:

```ts
protected readonly total = computed(() => {
  const budgets = this.budgets();
  if (budgets.length === 0 || new Set(budgets.map((b) => b.currency)).size > 1) {
    return null;
  }
  const sum = (pick: (b: Budget) => number) => budgets.reduce((t, b) => t + pick(b), 0);
  ...
});
```

- `new Set(...).size > 1`: se ci sono budget in valute diverse il totale **non** si mostra. Sommare euro e dollari
  darebbe un numero senza senso; meglio niente che un numero sbagliato.
- `sum` è una piccola funzione che riceve **un'altra funzione** (`pick`, "quale campo sommare"): la stessa riga
  somma lo speso, il budget e il ritmo. In Java sarebbe `budgets.stream().mapToDouble(pick).sum()`.

Per il mese in corso sotto la cifra c'è la frase del ritmo; per un mese passato "Rispettati 3 su 4".

### Il ritmo in parole

La barra aveva già la tacca del **ritmo** (`docs/38`, parte F): quanto avresti speso finora spendendo in modo
regolare. Ma una tacca va interpretata; una frase no:

```ts
export function paceSentence(spent: number, expected: number, euros: (v: number) => string): string {
  const difference = spent - expected;
  if (Math.abs(difference) < SAME_PACE) {
    return 'In linea con il ritmo di oggi.';
  }
  return difference > 0
    ? `${euros(difference)} sopra il ritmo di oggi: rallenta per restare nel budget.`
    : `${euros(-difference)} sotto il ritmo di oggi.`;
}
```

- È una **funzione esportata**, fuori dal componente: non usa niente di Angular, quindi si prova con tre righe di
  test, senza creare la pagina.
- `SAME_PACE` (5 €): sotto questa differenza dire "1 € sopra il ritmo" sarebbe pignolo. È una **costante con un
  nome**, non un 5 sparso nel codice.
- `euros` arriva come parametro: la funzione non sa come si scrivono gli importi (valuta, lingua), lo decide chi la
  chiama. Il componente le passa `formatCurrency` con la lingua dell'app (`LOCALE_ID`).

Per un mese passato il ritmo non ha senso (il mese è finito): la frase diventa "Nei tre mesi prima, in media 150 €",
un dato che il backend dava già (`averageLast3Months`) e la pagina non mostrava.

### L'ordine delle schede

```ts
const URGENCY: Record<BudgetStatus, number> = { OVER: 0, WARNING: 1, OK: 2 };

protected readonly sorted = computed(() =>
  [...this.budgets()].sort(
    (a, b) => URGENCY[a.status] - URGENCY[b.status] || a.category.localeCompare(b.category),
  ),
);
```

Prima i superati, poi i quasi esauriti, poi gli altri: quello che chiede attenzione sta in alto. A parità di stato,
per nome (`||` passa al secondo criterio quando il primo dà 0, come `thenComparing` in Java).

`[...this.budgets()]` fa una **copia** prima di ordinare: `sort` cambia l'array su cui lavora, e quello dentro un
signal non va mai cambiato sul posto.

`Record<BudgetStatus, number>` obbliga ad avere un numero per **ogni** stato: se domani ne aggiungiamo uno, il
compilatore segnala la riga da completare.

### La scheda di un budget

Il nome è un link alla **pagina della categoria** (`/analisi/categoria/:id?mese=...`), dove si vede in che cosa sono
andati quei soldi. Lo stato è un'icona **e** una parola ("Superato", "Quasi esaurito"): il colore da solo non basta a
chi non distingue il rosso dal verde. Sotto, **Modifica** ed **Elimina**; Modifica riempie il modulo in fondo alla
pagina e lo porta in vista:

```ts
document.getElementById('budget-form')?.scrollIntoView?.({ behavior: 'smooth' });
```

Il secondo `?.` (dopo `scrollIntoView`) serve nei test: jsdom, il finto browser dei test, non ha `scrollIntoView`, e
senza quel punto interrogativo il test fallirebbe per una funzione che non c'entra.

---

## Parte C: le tabelle sul telefono, una scheda per riga

Conti, Abbonamenti e Regole sono tabelle: sul PC vanno benissimo, sul telefono no (sei colonne in 390 pixel si
scorrono di lato, e i nomi delle colonne spariscono). Invece di scrivere ogni pagina due volte, una per il PC e una
per il telefono, usiamo la **stessa tabella** e cambiamo solo il CSS sotto i 700 pixel.

### Ogni cella sa il nome della sua colonna

```html
<table class="cards">
  ...
  <td class="card-title">Netflix</td>
  <td data-label="Importo">12,99 €</td>
  <td data-label="Prossimo">03/11/2026</td>
  <td data-label="">togli</td>
```

`data-label` è un **attributo `data-`**: HTML permette attributi inventati purché inizino con `data-`. Il browser
non ne fa niente; li leggiamo dal CSS.

### Il CSS

```css
@media (max-width: 699px) {
  table.cards, table.cards tbody, table.cards tr, table.cards td {
    display: block;          /* niente più griglia: ogni riga e ogni cella una sotto l'altra */
  }
  table.cards tr {
    display: flex;
    flex-direction: column;  /* la riga diventa una scheda verticale */
    border-radius: 16px;
    background: var(--card);
  }
  table.cards td {
    position: relative;
    padding-left: 7.5rem;    /* spazio a sinistra per il nome della colonna */
  }
  table.cards td::before {
    content: attr(data-label); /* scrive il valore dell'attributo prima della cella */
    position: absolute;
    left: 0;
    width: 7rem;
  }
  table.cards td.card-title {
    order: -1;               /* il titolo in cima alla scheda, anche se non è la prima colonna */
  }
}
```

I punti da capire:

- `display: block` toglie a `<table>`, `<tr>`, `<td>` il loro comportamento da tabella. È il trucco di tutta la
  parte: la struttura HTML resta quella di una tabella, il disegno no.
- `::before` con `content: attr(data-label)` crea un pezzo di testo **prima** del contenuto della cella, preso
  dall'attributo. Una sola regola CSS per tutte le celle di tutte le tabelle.
- `order: -1` funziona perché la riga è un contenitore **flex**: in flex l'ordine di disegno si cambia senza toccare
  l'HTML. Nelle Regole il nome è la seconda colonna (dopo la priorità), ma nella scheda deve stare in cima.
- Le celle con `data-label=""` (le azioni) non hanno etichetta né rientro: vanno in fondo, su tutta la larghezza.
- `.table-scroll:has(> table.cards)` toglie lo scorrimento laterale del contenitore: `:has()` è il "selettore
  genitore", seleziona un elemento **che contiene** qualcosa.

### E l'accessibilità?

Le intestazioni `<thead>` sul telefono non si vedono (le scrive già ogni cella), ma **non** le togliamo con
`display: none`: le nascondiamo solo alla vista, con il solito "visually hidden" (larghezza 1 px, `clip-path`). Un
lettore di schermo continua a leggere "Importo, 12,99 €", perché la tabella è ancora una tabella.

Una prova trovata nel browser: un'etichetta su due righe ("Ultimo pagamento") usciva dalla sua cella, perché
l'etichetta è `absolute` e non spinge l'altezza della riga. Invece di complicare il CSS, l'etichetta è diventata più
corta: "Pagato il". Le etichette vanno tenute brevi (circa 15 caratteri).

---

## Parte D: le Categorie, la variante compatta

Le categorie sono una trentina: con una scheda di cinque righe ciascuna, la pagina sul telefono era lunga 6.800
pixel. Per le tabelle con tante righe c'è una variante, `class="cards compact"`:

- **una scheda per tabella** (per gruppo: "Categorie di spesa", "Entrate"...), non per riga: lo sfondo va su
  `<tbody>`;
- ogni categoria è **una riga**, con un divisore sopra (`tr + tr`, "una riga che segue un'altra riga": il primo
  elemento non ha il bordo);
- in alto il nome e quanti movimenti; sotto il menu del gruppo e le azioni, sulla stessa riga (`flex-wrap: wrap`
  manda a capo quello che non ci sta).

Il risultato: 4.750 pixel invece di 6.800, e si vedono sette categorie per schermata invece di tre. Sul PC la
tabella resta tabella; l'unica correzione è `vertical-align: middle`, perché il menu è più alto del testo e il
numero dei movimenti restava in alto.

---

## Parte E: gli errori di aggiornamento in parole

Nel Riepilogo, dopo un aggiornamento non riuscito, compariva:

> Aggiornamento non riuscito: Banca Alfa ··0099. EnableBankingNotConfiguredException: 409 CONFLICT "Enable
> Banking non è ancora configurato..."

Il nome della classe Java, il codice HTTP, a volte la risposta intera della banca. Per chi usa l'app sono rumore; per
chi deve capire il problema sono utili, ma il loro posto è il **log** del backend. Ora `SyncService` scrive i dettagli
nel log e salva per l'utente una frase, scelta da una classe nuova:

```java
public static String describe(RuntimeException e) {
    if (e instanceof RestClientResponseException response) {
        int status = response.getStatusCode().value();
        if (status == 429) {
            return "La banca ha ricevuto troppe richieste oggi: riproveremo al prossimo aggiornamento.";
        }
        if (status == 401 || status == 403) {
            return "La banca non ha accettato l'accesso: forse il consenso è scaduto o è stato revocato. "
                    + "Ricollega la banca.";
        }
        ...
    }
    if (e instanceof RestClientException) {
        return "Enable Banking non raggiungibile: controlla la connessione a Internet. ...";
    }
    if (e instanceof ResponseStatusException status && status.getReason() != null) {
        return status.getReason();
    }
    return "Errore imprevisto durante l'aggiornamento: i dettagli sono nel log del backend.";
}
```

- `e instanceof RestClientResponseException response` è il **pattern matching** di Java 16+: controlla il tipo e,
  se va bene, dà già la variabile `response` del tipo giusto. Niente cast.
- **L'ordine conta**: `RestClientResponseException` è una sottoclasse di `RestClientException`. Se il controllo più
  generale venisse prima, un 429 diventerebbe "non raggiungibile".
- Ogni frase dice **che cosa fare**: aspettare (429, 5xx), ricollegare la banca (401/403), controllare la rete.
- I nostri errori, quelli lanciati con un messaggio pensato per l'utente (`ResponseStatusException` con un
  `reason`), passano così come sono.
- Una classe con solo metodi `static` e un costruttore `private`: non si crea mai un oggetto `SyncErrors`, è un
  raccoglitore di funzioni (come `Math` o `Collections`).

È anche una protezione: il corpo di una risposta d'errore può contenere dettagli interni (identificativi, testo del
fornitore) che non devono finire sullo schermo (`docs/17`, riga 23).

---

## Parte F: Regole e Abbonamenti, meno da scorrere

### Regole: prima le tue

Le 24 regole di partenza (`docs/07`) occupavano quasi tutta la pagina; le regole create dall'utente finivano in
mezzo. Adesso:

- **Le tue regole** in una sezione propria (o la frase "Nessuna regola tua: creane una qui sopra, oppure da un
  movimento");
- **Regole di partenza (24)** in un `<details>`, l'elemento HTML che si apre e si chiude al tocco da solo, senza una
  riga di TypeScript. Restano consultabili e modificabili, ma non ingombrano.

Le due tabelle sono identiche, quindi il loro HTML è scritto **una volta sola**, in un `ng-template`, e usato due
volte:

```html
<ng-template #rulesTable let-list>
  ...
  @for (rule of asRules(list); track rule.id) { ... }
</ng-template>

<ng-container *ngTemplateOutlet="rulesTable; context: { $implicit: ownRules() }" />
```

`let-list` dà un nome al valore passato in `$implicit`. Angular però non sa di che tipo è (per lui è `any`), e con il
controllo stretto dei tipi la build si ferma; `asRules(list: Rule[])` è un metodo che restituisce lo stesso valore,
ma con il tipo scritto. È il modo più semplice per riavere il controllo dei tipi dentro un template.

Le due liste sono due `computed` (`ownRules`, `builtinRules`) che dividono l'elenco con `rule.builtin`.

### Abbonamenti: la cifra in grande

I tre riquadri (al mese, all'anno, quanti) sono diventati la stessa scheda del Riepilogo: "Gli abbonamenti costano,
al mese" e la cifra grande, con l'anno sotto. Una cifra da ricordare, non tre da confrontare. Nei suggerimenti
"È un abbonamento" è il pulsante principale; "no, non suggerirlo più" un pulsante normale (prima era un link, ma
cambia dei dati: un'azione è un pulsante).

---

## Parte G: i test, e quello che ha trovato il browser

Frontend (`budget-page.spec.ts`, `rules-page.spec.ts`), 172 test in tutto:

- i budget superati vengono **prima**, anche se nella risposta erano dopo;
- il totale somma speso e budget, e con euro e dollari non compare (le schede sì);
- `paceSentence`: in linea sotto i 5 €, sopra, sotto;
- un mese passato: "Rispettati 1 su 2." e "Nei tre mesi prima, in media 150 €";
- Modifica riempie il modulo della scheda giusta;
- Regole: le regole dell'utente nella prima sezione, quelle di partenza nella seconda.

Backend: `SyncErrorsTest` (ogni codice diventa la sua frase; i nostri messaggi restano; l'imprevisto rimanda al log)
e `SyncServiceTest` (un 429 salva "troppe richieste"). 325 test.

Nel browser (Playwright, telefono 390 px e PC 1366 px), con un anno di movimenti inventati:

- nessuna pagina più larga dello schermo, nessuna tabella da scorrere di lato;
- Regole da 9.300 a 1.300 pixel, Categorie da 6.800 a 4.750;
- l'etichetta "Ultimo pagamento" su due righe (Parte C) e il numero dei movimenti non centrato sul PC (Parte D);
- sul PC le barre del Budget erano larghe 1.000 pixel, difficili da leggere: la pagina ora ha `max-width: 720px`,
  come la pagina di una categoria. Un componente Angular è un elemento "inline" se non si dice altro, e `max-width`
  vale solo per i blocchi: per questo serve anche `app-budget-page { display: block }`.

---

## Parte H: esercizi guidati

1. **Una tabella in più.** La tabella "Completa i movimenti" (`import/completion-page`) sul telefono scorre ancora di
   lato. Aggiungi `class="cards"`, un `data-label` per cella e una `card-title`. Quante righe di CSS hai scritto?
2. **Il totale per valuta.** Invece di non mostrare il totale con due valute, mostrane uno per valuta. Che cosa
   diventa `total`: un oggetto o un array? Scrivi prima il test.
3. **Un errore nuovo.** Enable Banking risponde 422 quando la richiesta non è valida. Aggiungi una frase in
   `SyncErrors` e il suo caso in `SyncErrorsTest`. Perché il controllo va **dentro** il blocco di
   `RestClientResponseException`?
4. **Il ritmo più preciso.** `SAME_PACE` è 5 € per tutti i budget: per un budget di 20 € è tanto, per uno di 800 €
   poco. Prova con una percentuale del budget (per esempio il 3%) e cambia i test di `paceSentence`.
