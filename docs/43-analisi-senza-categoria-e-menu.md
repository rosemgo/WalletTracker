# 43. Ritocchi: "Senza categoria", la categoria tra le entrate, i menu aperti

Tre cose emerse usando l'app, prima della Fase 4.

| Parte | Cosa |
|---|---|
| A | "Senza categoria" in Analisi porta ai movimenti da sistemare |
| B | una categoria di spesa non resta scelta tra le entrate |
| C | i menu a tendina, anche aperti |
| D | i test, e la prova nel browser |

---

## Parte A: "Senza categoria" porta ai movimenti

**Il problema.** In Analisi, toccando una categoria si guarda solo lei (`docs/38`). "Senza categoria" invece non
faceva niente: non è una categoria, non ha un id da filtrare, e nel codice la riga era un semplice `<div>`.

**Che cosa serve davvero.** Chi tocca "Senza categoria" non vuole un grafico in più: vuole vedere **quei movimenti
e dare loro una categoria**. Il posto dove si fa è Movimenti, che ha già il filtro "Senza categoria", la scheda di
ogni movimento e i suggerimenti. Allora la riga diventa un **link** a Movimenti con il filtro e il periodo della
barra guardata:

```
/movimenti?uncategorized=true&type=EXPENSE&from=2026-09-01&to=2026-09-30
```

`type` è `EXPENSE` se si guardavano le spese, `INCOME` se le entrate: così l'elenco ha esattamente i movimenti di
quella riga. Accanto al nome compare "da sistemare ›", e per il lettore di schermo "apri i movimenti, per dare loro
una categoria".

Nel codice:

- **Analisi** (`explore-page.ts`): `uncategorizedQuery(row)` restituisce i parametri per la riga senza categoria, o
  `null` per le altre. Nel template viene prima delle categorie vere, che restano pulsanti:

  ```html
  @if (uncategorizedQuery(r); as query) {
    <a class="group-row" routerLink="/movimenti" [queryParams]="query">...</a>
  } @else if (canPickCategory() && r.group.categoryId !== null) {
    <button type="button" class="group-row" (click)="pickCategory(r)">...</button>
  }
  ```

  Un **link** e non un pulsante, perché porta a un'altra pagina: si può aprire in una scheda nuova, e il tasto
  "indietro" riporta ad Analisi.
- **Movimenti** (`transactions-page.ts`): legge `uncategorized`, `from` e `to` dall'indirizzo, accanto a `type`,
  `text` e `categoryId` che leggeva già. Con delle date, il periodo diventa "Scegli le date" e le mostra.

**Le date dall'indirizzo si controllano.** Un indirizzo lo può scrivere chiunque. `isoDateParam` accetta solo
`AAAA-MM-GG` **e** una data che esiste: "2026-02-30" ha la forma giusta ma non esiste, e `new Date` la
trasformerebbe in silenzio nel 2 marzo. Il trucco per accorgersene è rifare il testo dalla data e confrontarlo:

```ts
const date = new Date(`${value}T00:00:00`);
return Number.isNaN(date.getTime()) || isoDate(date) !== value ? null : value;
```

Una data sbagliata si ignora (nessun limite), come un tipo sconosciuto (`docs/37`). Il backend la controllerebbe
comunque, ma la pagina non deve mandare richieste che sa già sbagliate.

---

## Parte B: una categoria di spesa tra le entrate

**Il problema.** Scelta una categoria di spesa ("Multimedia ed elettronica") e toccato "Entrate", la categoria
restava scelta:

- la cifra era sempre **0 €**, e "di solito 0 €";
- sotto, "I movimenti" mostrava comunque le **spese** della categoria, sotto il titolo "Entrate".

Ha senso? No. Una categoria ha un **tipo** (`kind`: spesa, entrata, investimento, altro) e una categoria di spesa
tra le entrate dà sempre zero, per come è fatta. Mantenerla non dice niente e confonde.

**La regola.** Cambiando quello che si guarda, la categoria resta solo se è del tipo giusto:

| Si guarda | La categoria resta se è |
|---|---|
| Spese | di spesa |
| Entrate | di entrata |
| Netto | (le categorie non si scelgono nel netto: si toglie sempre, come prima) |

Se non va, si toglie, e si torna all'elenco per categoria: come toccare la ✕ della pillola.

**Da dove viene il tipo.** La pagina non lo sapeva: della categoria scelta teneva solo id e nome. All'apertura ora
chiede l'elenco delle categorie (`GET /api/categories`, quello delle altre pagine) e si tiene una mappa
id → tipo. Lo stesso controllo vale per un indirizzo come `/analisi?cosa=entrate&categoria=7`, scritto a mano o
salvato quando le regole erano diverse: quando arriva l'elenco, la categoria sbagliata si toglie e si ricarica.
Finché l'elenco non è arrivato, la categoria si tiene: meglio non togliere niente sulla base di un "non so".

```ts
private dropMismatchedCategory(): boolean {
  ...
  const expected = metric === 'EXPENSES' ? 'EXPENSE' : metric === 'INCOME' ? 'INCOME' : null;
  if (metric !== 'NET' && (kind === undefined || kind === expected)) {
    return false;
  }
  this.category.set(null);
  ...
}
```

---

## Parte C: i menu a tendina, anche aperti

In `docs/41` avevamo ridisegnato il menu **chiuso**. Quello **aperto** restava l'elenco del sistema: grigio, con gli
angoli dritti, diverso su Windows, Mac e telefono. Fino a poco fa non si poteva cambiare.

Ora Chrome ed Edge (dalla versione 135) hanno il **select personalizzabile**: una riga di CSS sostituisce l'elenco
del sistema con un elenco che si disegna come il resto della pagina.

```css
@supports (appearance: base-select) {
  select,
  ::picker(select) {
    appearance: base-select;
  }
  ...
}
```

- **`::picker(select)`** è l'elenco che si apre: è diventato una scheda in rilievo, con gli angoli arrotondati,
  un'ombra, staccata di 6 pixel dal campo, larga almeno quanto il campo (`anchor-size(width)`).
- **`option`**: righe alte 40 pixel con gli angoli arrotondati. Al passaggio del mouse o della tastiera, grigie; la
  voce scelta ha il colore tenue dell'accento e la spunta a destra (`option::checkmark`).
- **`::picker-icon`** è la freccia del campo: la stessa di prima, che si capovolge quando l'elenco è aperto
  (`select:open`).
- **L'apertura**: l'elenco appare scendendo un poco. `@starting-style` dice da dove parte la transizione;
  `transition-behavior: allow-discrete` fa animare anche il passaggio da nascosto a visibile, che di solito è uno
  scatto.

**Resta un vero `<select>`.** Le frecce, Invio, Esc, la ricerca scrivendo le prime lettere, i lettori di schermo e i
moduli di Angular funzionano come prima. Non è un menu "finto" di `<div>`, che andrebbe rifatto tutto.

**`@supports`: un miglioramento, non un requisito.** Firefox e Safari oggi non conoscono `base-select`: saltano
l'intero blocco e mostrano l'elenco del sistema, con il campo chiuso già ridisegnato in `docs/41`. Quando lo
conosceranno, il blocco varrà anche per loro senza cambiare niente. Sul telefono Android con Chrome si vede la
versione nuova; su iPhone l'elenco di iOS, che è comunque quello a cui la persona è abituata.

Due dettagli trovati provando:

- con `base-select` il campo riparte dal carattere del browser, e il testo del menu chiuso era diventato più piccolo.
  Un `font: inherit` nel blocco lo riporta al nostro;
- misurando, è venuto fuori un difetto più vecchio: nei moduli i campi erano a 12,75 pixel, perché ereditavano la
  misura piccola dell'etichetta che li contiene (`.form-grid label`, 0,85rem). Ora il campo ha la misura del testo
  normale. Sui telefoni (`@media (pointer: coarse)`) almeno 16 pixel: con meno, Safari su iPhone ingrandisce tutta
  la pagina quando si tocca un campo.

---

## Parte D: i test, e la prova nel browser

Frontend, 192 test (187 prima):

- Analisi:
  - "Senza categoria" è un link a `/movimenti` con `uncategorized`, `type`, `from` e `to` della barra; le categorie
    vere restano pulsanti;
  - scelta "Spesa alimentare" e toccato "Entrate", la richiesta parte senza categoria, raggruppata per categoria, e
    l'indirizzo non ha più `categoria`;
  - un indirizzo con una categoria di spesa tra le entrate viene corretto;
- Movimenti:
  - dall'indirizzo arrivano "senza categoria", il tipo e le due date (la pillola accesa, il calendario con le date);
  - le date che non esistono si ignorano.

Nel browser (Chromium di Playwright, che ha `base-select`):

- da Analisi, "Senza categoria · da sistemare ›" porta a Movimenti con "Senza categoria", "Spesa" e le date del mese;
- la categoria di spesa sparisce passando a "Entrate";
- il menu del Budget aperto: scheda arrotondata, voce scelta con la spunta; scelta con la tastiera (frecce e
  Invio);
- la pillola del periodo in Movimenti, aperta, nel tema scuro.
