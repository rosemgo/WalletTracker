# 35. Fase grafica, passo 2: le basi

**Obiettivo:** dare a tutta l'app lo stile scelto nel progetto (`docs/33`, stile C in due temi) **prima** di
ridisegnare le singole schermate: il carattere, i colori del giorno e della notte, la navigazione in basso sul
telefono e a lato sul PC, le schede, i campi e i pulsanti. Le pagine di oggi non cambiano contenuto: cambiano aspetto,
e da qui in poi ogni schermata nuova usa le stesse basi.

| Parte | Cosa |
|---|---|
| A | che cosa cambia, in breve |
| B | il carattere, servito dall'app |
| C | i colori come variabili, e i due temi con `light-dark()` |
| D | la navigazione: una sola, disposta in due modi |
| E | "Altro" e la sezione attiva |
| F | gli elementi comuni: schede, tabelle, campi, pulsanti |
| G | la scelta del tema nelle Impostazioni |
| H | i test, e la prova nel browser |
| I | esercizi guidati |

---

## Parte A: che cosa cambia, in breve

- Il carattere è **Schibsted Grotesk**, servito dall'app stessa.
- Tutti i colori sono **variabili CSS** con due valori, giorno e notte. Il tema segue il telefono o il computer
  (automatico), oppure si sceglie nelle Impostazioni.
- Sul **telefono** la navigazione è una **barra in basso** con quattro voci: Riepilogo, Movimenti, Budget, Altro.
  "Altro" apre l'elenco delle pagine di servizio (Abbonamenti, Conti, Collega una banca, Importa, Regole,
  Categorie, Impostazioni).
- Sul **PC** (da 900 pixel di larghezza) la stessa navigazione è una **colonna a sinistra** con tutte le pagine.
- Schede piatte con gli angoli arrotondati, nessuna ombra, nessun bordo colorato a sinistra (`docs/33`, parte F).

Il nome della prima sezione: nel progetto era "Oggi"; diventa **Riepilogo**, il nome che la pagina ha già. Mostra il
**mese in corso** (quanto hai speso finora rispetto al solito), più le spese in arrivo nei prossimi giorni e le cose
da sistemare: "Oggi" faceva pensare al solo giorno.

**Analisi** non è ancora nella barra: arriverà con la sua pagina (passo 5). Fino ad allora i grafici di 12 mesi
restano nel Riepilogo; quando il Riepilogo diventerà la schermata del mese (passo 3), passeranno ad Analisi. Una voce
che porta a una pagina vuota non la mettiamo.

---

## Parte B: il carattere, servito dall'app

Le bozze caricavano il carattere da Google Fonts. Nell'app no, per due motivi:

1. **Riservatezza**: ogni apertura della pagina farebbe una richiesta a Google, con l'indirizzo IP di chi la apre.
   Un'app di finanze personali, sul proprio computer, non ha bisogno di dirlo a nessuno.
2. **Senza rete**: l'app si userà dal telefono, anche con rete scarsa (e come PWA, passo 7). Un carattere che arriva
   da fuori può non arrivare.

Il pacchetto npm `@fontsource-variable/schibsted-grotesk` (licenza OFL, libera) contiene i file del carattere e il CSS
con le `@font-face`. In `styles.css` basta una riga:

```css
@import '@fontsource-variable/schibsted-grotesk';
```

Angular, nella build, copia i file `.woff2` nella cartella dell'app (`media/`) e corregge gli indirizzi. Sono due
file: l'alfabeto latino di base e quello esteso, e il browser scarica il secondo solo se una pagina usa quei
caratteri (`unicode-range`).

"Variable" vuol dire **carattere variabile**: un file solo contiene tutti i pesi, dal normale (400) al nerissimo
(900); `font-weight: 600` non chiede un altro file.

Nella `font-family` dopo il nome vengono i caratteri di riserva (`system-ui`...): si usano finché il file non è
arrivato (`font-display: swap`), così il testo si legge subito.

---

## Parte C: i colori come variabili, e i due temi con `light-dark()`

### I nomi

Ogni colore ha un nome che dice **a che cosa serve**, non com'è fatto:

| Nome | Serve per | Giorno | Notte |
|---|---|---|---|
| `--bg` | lo sfondo della pagina | `#EEEDE9` | `#15171A` |
| `--card` | le schede | `#FFFFFF` | `#1F2226` |
| `--ink` | il testo | `#16171B` | `#ECEDEE` |
| `--sec` | il testo secondario | `#5B5D64` | `#A3A8B0` |
| `--line` | linee, bordi dei campi | `#E6E4DE` | `#2C3036` |
| `--track` | fondo delle barre, pulsanti secondari | `#ECEBE6` | `#2A2E33` |
| `--acc` | l'accento: link, sezione attiva | `#1F5FD6` blu | `#E59A2E` ambra |
| `--soft` | un riquadro da notare | `#E8EEF9` | `#2B2419` |
| `--pos` / `--neg` | entrate e risparmio / valori negativi | blu / `#C8571B` | ambra / `#6E9CF0` |
| `--ok`, `--warn`, `--bad` | gli stati (budget, avvisi) | verde, giallo, rosso | gli stessi, più chiari |

Le regole CSS usano **solo i nomi**: `background: var(--card)`. Così il tema scuro non tocca nessuna regola: cambia
solo i valori. Prima nei file c'erano `white`, `#eef2f7`, `#fff7e6` sparsi in trenta regole; ora un colore si cambia
in un posto solo.

### Due valori per nome: `light-dark()`

```css
:root {
  color-scheme: light dark;
  --bg: light-dark(#eeede9, #15171a);
  --card: light-dark(#ffffff, #1f2226);
  ...
}

:root[data-theme='giorno'] { color-scheme: light; }
:root[data-theme='notte']  { color-scheme: dark; }
```

`light-dark(a, b)` vale `a` quando l'elemento è in schema chiaro, `b` in schema scuro. Lo schema lo dice la
proprietà `color-scheme`:

- `light dark` (il caso normale, nessun `data-theme`): "vanno bene tutti e due, scegli tu, browser". Il browser
  sceglie come il **sistema**: se il telefono è in modalità scura, la notte.
- `data-theme="giorno"` o `"notte"` sull'elemento `<html>`: uno solo, qualunque cosa dica il sistema.

`color-scheme` fa anche un'altra cosa: i campi, i menu a tendina e le barre di scorrimento del browser prendono i
colori chiari o scuri da soli.

Perché `light-dark()` e non la classica `@media (prefers-color-scheme: dark)`? Con la media query i valori della
notte vanno scritti **due volte**: una per "il sistema è scuro" e una per "l'utente ha scelto notte". Due elenchi
uguali da tenere allineati a mano sono un errore che aspetta di succedere. Con `light-dark()` ogni colore è scritto
una volta, giorno e notte accanto. È supportata da tutti i browser recenti (dal 2024).

### Gli stati

Verde, giallo e rosso sono riservati agli **stati** (budget nei limiti, quasi finito, superato), mai alle serie dei
grafici, e sempre insieme a un simbolo e una scritta (`docs/20`). Il giallo serve solo come sfondo dell'icona, con il
segno scuro sopra: come testo su bianco non si leggerebbe.

### Gli importi

Nell'elenco dei movimenti una spesa **non** è rossa: il segno meno basta. In rosso tutto l'elenco sarebbe un
allarme continuo, e un allarme continuo non si nota più. Restano colorate le entrate (`--pos`) e i valori negativi
dove contano (un risparmio sotto zero, `--neg`).

Una nota sulle cifre: di solito per gli importi in colonna si usano le cifre "tabellari"
(`font-variant-numeric: tabular-nums`), tutte larghe uguali. In questo carattere allargano però anche il punto e la
virgola ("2 . 100 , 00"): le abbiamo tolte. L'allineamento a destra basta.

---

## Parte D: la navigazione, una sola disposta in due modi

Nel template c'è **una** navigazione (`app.html`), con tre gruppi:

```html
<nav class="app-nav" aria-label="Sezioni">
  <span class="brand">WalletTracker</span>
  @for (section of sections; track section.path) {
    <a class="section" [routerLink]="section.path" routerLinkActive="active" ariaCurrentWhenActive="page">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path [attr.d]="section.icon" /></svg>
      <span>{{ section.label }}</span>
    </a>
  }
  <a class="section more" ...>Altro</a>
  <div class="more-pages">...le pagine di servizio...</div>
</nav>
```

Il **CSS** decide come disporla:

- **Telefono** (la regola normale): `position: fixed; bottom: 0`, una griglia di quattro colonne con icona e
  scritta. Il nome dell'app e le pagine di servizio sono nascosti (`display: none`): ci si arriva da "Altro".
- **PC** (`@media (min-width: 900px)`): la pagina diventa una griglia di due colonne (`232px` e il resto); la
  navigazione è la prima, `position: sticky` e alta quanto lo schermo, con il nome dell'app in cima, le sezioni,
  una linea, le pagine di servizio. "Altro" è nascosto: le sue pagine sono già lì.

Perché una sola navigazione e non due (una per il telefono, una per il PC)? Una sola vuol dire un solo elenco di
voci, un solo test, nessun rischio che le due si dimentichino una voce a vicenda. Gli elementi con `display: none`
non esistono nemmeno per i lettori di schermo: chi usa la navigazione vocale sente solo quelli visibili.

Alcuni dettagli del telefono:

- **Il pollice**: ogni voce è alta almeno 48 pixel (la regola è 44, `docs/33` parte G).
- **La barretta in basso** dei telefoni senza tasti: `env(safe-area-inset-bottom)` è il suo spazio, e la barra lo
  aggiunge sotto le voci. Funziona perché in `index.html` il viewport ha `viewport-fit=cover` (la pagina usa tutto
  lo schermo, e ci dice quanto spazio lasciare).
- **Il contenuto non finisce sotto la barra**: `main` ha in fondo 88 pixel di spazio in più.
- **Le icone** sono tracciati SVG a linea (`stroke`, niente riempimenti), con `stroke: currentColor`: prendono il
  colore del testo del link, grigio o accento se la sezione è attiva. Hanno `aria-hidden="true"`: la scritta accanto
  dice già tutto.
- **`aria-current="page"`** sulla voce attiva (`ariaCurrentWhenActive`): un lettore di schermo dice "pagina
  corrente". Il colore da solo non basta.

In `index.html` ci sono anche due `<meta name="theme-color">`: il colore della barra del browser sul telefono,
chiaro o scuro secondo il sistema (`media="(prefers-color-scheme: dark)"`).

---

## Parte E: "Altro" e la sezione attiva

Sul telefono "Altro" deve risultare acceso anche dentro le sue pagine: se sei in Regole, la barra deve dire "sei in
Altro". `routerLinkActive` non lo può sapere (il link è `/altro`, l'indirizzo è `/regole`), quindi lo calcoliamo:

```ts
private readonly url = toSignal(
  this.router.events.pipe(
    filter((event) => event instanceof NavigationEnd),
    map((event) => event.urlAfterRedirects),
  ),
  { initialValue: this.router.url },
);

protected readonly inMore = computed(() => {
  const path = this.url().split(/[?#]/)[0];
  return [MORE_SECTION, ...MORE_PAGES].some(
    (page) => path === page.path || path.startsWith(page.path + '/'),
  );
});
```

- Il router emette un **evento** a ogni passo della navigazione; `NavigationEnd` è quello di "arrivato".
  `urlAfterRedirects` è l'indirizzo vero dopo i reindirizzamenti (`/` diventa `/riepilogo`).
- `toSignal` trasforma il flusso di eventi (un Observable, `docs/14`) in un **signal**: il valore più recente.
- `computed` ricalcola `inMore` solo quando cambia l'indirizzo.
- `split(/[?#]/)[0]` toglie i parametri (`/regole?pattern=NETFLIX` è sempre Regole); `startsWith(page.path + '/')`
  riconosce le sotto-pagine (`/importa/completa`) ma non i nomi simili (`/importazioni` non è `/importa`).

L'elenco delle pagine di servizio è in un file solo, `more/more-pages.ts`, con il nome e la riga che spiega a che
cosa serve: lo usano la colonna del PC e la pagina "Altro" del telefono.

---

## Parte F: gli elementi comuni

Le pagine esistenti usano già poche classi (`card`, `tile`, `filters`, `hint`, `secondary`, tabelle): restilizzate
quelle, tutte le pagine cambiano aspetto senza toccarle.

- **Schede** (`.card`, `.tile`, le tabelle): `background: var(--card)`, `border-radius: 16px`, nessun bordo e
  nessuna ombra. Per arrotondare una tabella serve `border-collapse: separate` (con `collapse` gli angoli non si
  arrotondano) e `overflow: hidden`.
- **Campi** e **menu**: alti almeno 40 pixel, bordo `--line`, angoli di 10 pixel, sfondo della scheda.
- **Pulsanti**: grigi (`--track`); quello che conferma un modulo (`type="submit"`) è **pieno di accento**: in ogni
  modulo si vede subito qual è il pulsante che conta.
- **La tastiera**: `:focus-visible` disegna un contorno nell'accento intorno al campo o pulsante attivo, solo quando
  si usa la tastiera (non al clic).
- **`accent-color: var(--acc)`**: caselle, pulsanti di opzione e barre di avanzamento del browser prendono l'accento,
  senza ridisegnarli.
- **Tabelle larghe**: sul telefono stanno in un riquadro che scorre di lato (`.table-scroll`), non fanno scorrere la
  pagina. Ne mancavano quattro (Regole, Collega una banca, Completa con il file): la prova nel browser le ha trovate
  (Parte H).
- **Gli avvisi** avevano un bordo colorato a sinistra, uno dei segni dello stile "da IA" (`docs/33`, parte F): ora
  sono schede, e lo stato lo dice l'icona con la scritta.

---

## Parte G: la scelta del tema nelle Impostazioni

In fondo alle Impostazioni, **Aspetto → Tema**: Automatico, Giorno, Notte. Si applica subito.

`ThemeStore` (`theme/theme-store.ts`) fa tre cose:

1. tiene la scelta in un signal;
2. la applica: nessun attributo per "automatico", altrimenti `data-theme="giorno"` o `"notte"` su `<html>`
   (`document.documentElement`);
3. la ricorda nel **`localStorage`** del browser, e la rilegge all'avvio.

Il `localStorage` vale per quel browser su quel dispositivo: il telefono può stare in automatico e il PC sempre in
notte. Non è un dato da salvare nel database.

Leggere e scrivere il `localStorage` può **fallire** (navigazione privata in alcuni browser, archiviazione bloccata):
per questo è dentro `try/catch`, e in quel caso l'app resta in automatico. Un valore sconosciuto (scritto a mano, o
da una versione futura) vale "automatico".

`DOCUMENT` si ottiene con `inject(DOCUMENT)` invece di usare `document` direttamente: è il modo di Angular per
arrivare alla pagina, e nei test si può sostituire.

Il componente `App` "chiede" `ThemeStore` nel costruttore: la classe viene creata all'avvio e applica il tema prima
che si veda la prima pagina.

---

## Parte H: i test, e la prova nel browser

- `theme-store.spec.ts`: parte in automatico (nessun attributo); applica e ricorda una scelta, "automatico" la
  dimentica; una scelta ricordata vale all'avvio e un valore sconosciuto no; funziona anche con il `localStorage`
  che lancia errori; i pulsanti di opzione delle Impostazioni cambiano il tema.
- `app.spec.ts`: le sezioni principali, "Altro" e le pagine di servizio, nell'ordine; ogni sezione ha la sua icona,
  nascosta ai lettori di schermo; "Altro" è la sezione attiva in `/regole`, `/importa/completa` e `/altro`, non in
  `/movimenti` e `/riepilogo` (con `aria-current`).
- `settings-page.spec.ts` cercava il campo "Application ID" come "il secondo `<input>` della pagina": con i pulsanti
  del tema non era più vero. Ora lo cerca dal suo esempio (`placeholder`). Un test che dipende dalla posizione si
  rompe al primo campo in più.

**Nel browser** (Playwright, con dati inventati): sei pagine, sul telefono (390 pixel) e sul PC (1280), con il
sistema in chiaro e in scuro. Per ognuna si controlla che la pagina **non sbordi** di lato
(`document.documentElement.scrollWidth - innerWidth` deve essere 0), che il carattere sia caricato
(`document.fonts.check`) e quale voce abbia `aria-current`. La prima prova ha trovato la pagina Regole larga 309
pixel più del telefono: una tabella senza `.table-scroll`. Poi: scelta "Notte" con il sistema in chiaro, lo sfondo
diventa scuro e la scelta resta dopo aver ricaricato la pagina.

---

## Parte I: esercizi guidati

1. **Un colore nuovo.** Aggiungi `--warn-text` per un testo di avviso leggibile in entrambi i temi (sul bianco il
   giallo `#F2B21B` ha un contrasto di circa 1,8:1: troppo poco; serve almeno 4,5:1). Prova con un ambra scuro di
   giorno e il giallo di notte.
2. **Il tema nel titolo.** Fai in modo che anche `<meta name="theme-color">` segua la scelta manuale: in `apply()`
   cambia il `content` dei due meta. Perché basta cambiarli tutti e due nello stesso valore?
3. **Una voce in più.** Aggiungi temporaneamente una quinta sezione a `MAIN_SECTIONS`. Che cosa devi cambiare nel CSS
   del telefono (`grid-template-columns`)? Come lo eviteresti con `grid-auto-flow: column`?
