# 41. Fase grafica: un aspetto moderno, grafici vivi

**Obiettivo:** prima della PWA (passo 7), rendere il sito **moderno** e piacevole da usare:

- grafici "vivi" come quelli di Revolut, sia quello a linea del Riepilogo sia quello a barre di Analisi;
- menu a tendina e pulsante "Sfoglia" che non sembrino degli anni '90;
- testi più leggibili in tutto il sito.

Niente librerie nuove, tranne il carattere: è tutto CSS e SVG scritti a mano, come prima (`docs/35`).

| Parte | Cosa |
|---|---|
| A | che cosa cambia, a colpo d'occhio |
| B | il carattere e i testi |
| C | colori, ombre, angoli |
| D | i campi: menu a tendina, caselle, pulsanti, la scelta di un file |
| E | i nomi leggibili e le iniziali colorate |
| F | il movimento, e chi non lo vuole |
| G | il grafico a linea del Riepilogo |
| H | il grafico a barre di Analisi |
| I | i test, e la prova nel browser |
| J | esercizi guidati |

---

## Parte A: che cosa cambia, a colpo d'occhio

| Dove | Prima | Adesso |
|---|---|---|
| Carattere | Schibsted Grotesk | **Inter** con la dimensione ottica; cifre tutte larghe uguali |
| Schede | piatte, angoli 16 px | angoli 20 px, un'ombra appena accennata |
| Barra in basso | bianca piena | semitrasparente e sfocata ("vetro"); l'icona attiva in una pillola colorata |
| Colonna del PC | grigia | bianca, con il marchio "W" e la sezione attiva colorata |
| Accento nel tema scuro | arancione | lo stesso blu del giorno, più chiaro |
| Menu a tendina | quello del sistema | bordo leggero, freccia nostra, anello colorato quando è attivo |
| Caselle e scelte | quelle del sistema | disegnate da noi, uguali in tutti i browser |
| "Sfoglia" | il pulsante del browser | una zona su cui si tocca o si trascina il file |
| Nomi dalla banca | "PIZZERIA DA GINO" | "Pizzeria da Gino", con l'iniziale in un cerchio colorato |
| Grafico del mese | linea spezzata, area piatta | curva morbida che si disegna, sfumatura, punto che pulsa, si scorre con il dito |
| Grafico a barre | barre grigie, la scelta blu | barre a pillola, tenui, la scelta piena con un alone, crescono all'apertura |

Le pagine, i dati e le API non cambiano.

---

## Parte B: il carattere e i testi

### Perché Inter, dopo averlo scartato

In `docs/33` Inter compariva tra le cose da evitare: "il carattere Inter ovunque, titoli enormi e testo grigio
chiarissimo" è l'aspetto di tante pagine fatte in fretta. Il problema però era il **grigio chiarissimo**, non il
carattere. Con il testo ben contrastato, Inter è il carattere di molte app bancarie, e ha due cose che Schibsted
Grotesk non aveva:

1. **Cifre tabellari** vere. In `docs/37` avevamo dovuto rinunciarci, perché in Schibsted allargavano anche il
   punto e la virgola ("2 . 100 , 00"). In Inter no:

   ```css
   .amount, .big-figure, .detail-amount, ... {
     font-variant-numeric: tabular-nums;
   }
   ```

   Ogni cifra è larga uguale: gli importi uno sotto l'altro stanno in colonna, e la cifra grande che cambia mentre si
   scorre il grafico non "balla".
2. **La dimensione ottica** (l'asse `opsz` del carattere variabile). Lo stesso carattere ha forme diverse per i
   titoli grandi (lettere più strette e nitide) e per il testo piccolo (più aperte, si leggono meglio). Il browser
   sceglie da solo, con `font-optical-sizing: auto`, in base alla dimensione.

```css
@import '@fontsource-variable/inter/opsz.css';
```

È sempre un pacchetto npm (`@fontsource-variable/inter`, versione fissata, 5.3.0): il carattere lo serve la nostra
app, nessuna richiesta a Google. Il file `opsz` pesa 73 kB invece di 48: lo paghiamo una volta, poi il browser lo
tiene in memoria.

### La scala dei testi

| Che cosa | Misura | Peso | Spaziatura |
|---|---|---|---|
| testo | 15 px (`1rem`) | 400 | normale, interlinea 1.5 |
| etichette dei campi, testo secondario | 0,85rem | 500 / 400 | normale |
| titolo di una scheda (`h2`) | 1,1rem | 650 | −0,015em |
| titolo della pagina (`h1`) | 1,85rem | 700 | −0,03em |
| la cifra grande | 2,9rem (3,4 sul PC) | 700 | −0,045em |
| intestazioni delle tabelle | 0,72rem, MAIUSCOLO | 600 | +0,06em |

Tre idee:

- **i pesi intermedi** (550, 650): con un carattere variabile il peso è un numero qualsiasi tra 100 e 900, non
  solo "normale" e "grassetto". Un titolo a 650 si distingue senza "urlare";
- **la spaziatura negativa** nei caratteri grandi: a 40 pixel le lettere sembrano lontane tra loro, e
  `letter-spacing` le avvicina. Nei testi piccoli si lascia com'è. Nelle intestazioni maiuscole, al contrario, si
  allarga: le maiuscole attaccate si leggono male;
- **`text-wrap`**: `balance` per i titoli (due righe di lunghezza simile, non una lunga e una parola sola) e `pretty`
  per i paragrafi (niente parola sola sull'ultima riga). Il browser che non li conosce li ignora.

---

## Parte C: colori, ombre, angoli

I nomi dei colori sono quelli di prima (`docs/35`), con qualche valore nuovo e quattro nomi in più:

| Nome | A che cosa serve |
|---|---|
| `--acc-strong` | l'accento come **sfondo pieno** (i pulsanti), con il testo bianco sopra. `--acc` resta quello per testi e linee: di notte è più chiaro, per leggersi sul fondo scuro, e il bianco sopra non si leggerebbe |
| `--acc-2` | il secondo colore delle sfumature (viola): barre orizzontali, marchio, etichetta "IA" |
| `--raised` | qualcosa in rilievo sopra una scheda: la scelta attiva di un interruttore |
| `--field-line` | il bordo dei campi, un po' più deciso delle linee di separazione |

E tre variabili che non sono colori:

```css
--ring: color-mix(in srgb, var(--acc) 28%, transparent);
--shadow:
  0 1px 2px light-dark(rgb(16 24 40 / 0.04), transparent),
  0 6px 20px -4px light-dark(rgb(16 24 40 / 0.07), transparent);
--ease: cubic-bezier(0.22, 1, 0.36, 1);
```

- **`color-mix()`** mescola due colori: qui l'accento con il trasparente, cioè l'accento al 28%. È l'**anello**
  intorno a un campo attivo (Parte D). Lo stesso trucco dà le barre tenui del grafico (Parte H).
- **L'ombra** è doppia: una piccola e netta (il bordo) e una grande e sfumata (la distanza dal fondo), molto
  leggere. Di notte è trasparente: su un fondo scuro un'ombra non si vede, e le schede si staccano già perché
  sono più chiare del fondo. `light-dark()` funziona anche dentro un'ombra, nella posizione del colore.
- **`--ease`** è la curva dei movimenti (Parte F).

**L'accento di notte.** Nel passo 2 il tema scuro aveva l'arancione (`docs/33`, parte H). Due accenti diversi
facevano sembrare due app diverse: ora l'accento è lo stesso blu, più chiaro di notte. Entrate e uscite restano in
due colori distinguibili anche da chi non vede bene i colori: blu e arancione, in entrambi i temi.

---

## Parte D: i campi

### Il menu a tendina

Il `<select>` del sistema su Windows è un rettangolo grigio con una freccia "anni '90". Si toglie il suo aspetto e si
disegna la freccia:

```css
select {
  appearance: none;
  padding-right: 2.4rem;
  background-image: url("data:image/svg+xml,%3Csvg ... stroke='%23838a96' ...%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 0.75rem center;
  background-size: 1.05rem;
}
```

- `appearance: none` toglie **solo l'aspetto** del campo chiuso. Il menu che si apre resta quello del sistema: sul
  telefono la ruota o l'elenco a cui la persona è abituata, e la tastiera funziona come sempre. Un menu "finto"
  fatto di `<div>` sarebbe più bello da aperto, ma andrebbe rifatto tutto: tastiera, lettori di schermo, telefono.
- La freccia è un piccolo **SVG scritto dentro l'indirizzo** (`data:`): i caratteri speciali sono codificati
  (`%3C` è `<`, `%23` è `#`). Il colore è un grigio medio (`#838a96`) che si vede sia sul bianco sia sul fondo scuro:
  dentro un'immagine le variabili CSS non arrivano.
- Da ricordare per la Fase 4: se aggiungeremo una **Content-Security-Policy** (l'elenco di ciò che la pagina può
  caricare), dovrà permettere le immagini `data:` (`img-src 'self' data:`). Altrimenti spariscono la freccia dei
  menu e la spunta delle caselle.

Tutti i campi hanno lo stesso comportamento: 44 pixel di altezza (il pollice), un bordo leggero che al passaggio
del mouse si scurisce, e quando sono **attivi** il bordo dell'accento con un **anello** intorno:

```css
select:focus-visible, input:...:focus-visible, textarea:focus-visible {
  outline: none;
  border-color: var(--acc);
  box-shadow: 0 0 0 4px var(--ring);
}
```

`box-shadow` senza sfocatura e con 4 px di "spread" disegna un anello pieno: segue gli angoli arrotondati, cosa che
il vecchio `outline` non faceva in tutti i browser. Il contorno resta per chi usa la tastiera: è cambiato
l'aspetto, non la regola (`docs/17`).

### Caselle e scelte

```css
input[type='checkbox'] {
  appearance: none;
  width: 20px; height: 20px;
  border: 1.5px solid var(--field-line);
  border-radius: 6px;
}
input[type='checkbox']:checked {
  background-color: var(--acc-strong);
  background-image: url("data:image/svg+xml,... stroke='white' ... d='m5 12.5 4.5 4.5L19 7.5' ...");
}
input[type='radio']:checked {
  border: 6px solid var(--acc-strong);
}
```

Restano veri `<input>`: lo spazio le spunta, i lettori di schermo dicono "casella, selezionata". La scelta
(`radio`) è un trucco: un bordo spesso 6 pixel su un cerchio di 20 lascia al centro un pallino del colore della
scheda.

### I pulsanti

Secondario grigio, principale dell'accento pieno. Con il mouse si schiariscono un poco (`filter: brightness(1.08)`),
e premuti **si abbassano**:

```css
button:not(:disabled):active {
  transform: scale(0.97);
}
```

`@media (hover: hover)` limita l'effetto del passaggio ai dispositivi con un puntatore: sul telefono un tocco
lascerebbe il pulsante "acceso" finché non si tocca altrove.

### La scelta di un file: `app-file-drop`

Il pulsante "Sfoglia" del browser non si può ridisegnare bene: è disegnato dal sistema, con la scritta nella lingua
del sistema. Il componente nuovo (`ui/file-drop.ts`) mostra una **zona tratteggiata** con un'icona, "Scegli il file
CSV" e sotto "o trascinalo qui · al massimo 5 MB". Scelto il file, la zona diventa piena e mostra il nome.

Il trucco è il vero `<input type="file">`, **trasparente e grande quanto tutta la zona**:

```css
.file-drop input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0; /* invisibile, ma c'è */
}
```

Un clic in qualsiasi punto lo apre. Un file trascinato dal computer ci cade sopra, e il browser lo accetta da solo
(non serve scrivere il codice del "trascina e rilascia"). La tastiera ci arriva con Tab e i lettori di schermo lo
trovano come prima. `opacity: 0` e non `display: none`: un elemento nascosto con `display: none` non si può né
toccare né raggiungere con la tastiera.

Il componente restituisce l'**evento originale** (`picked`), così le tre pagine che lo usano (Importa, Completa i
movimenti, Impostazioni) hanno tenuto il loro `onFile(event)` com'era, e anche i loro test.

---

## Parte E: i nomi leggibili e le iniziali colorate

### Basta maiuscolo

Le banche scrivono tutto in maiuscolo: "PAGAMENTO POS AMAZON.IT". Il maiuscolo continuo si legge più lentamente (le
parole perdono la loro "sagoma", tutte rettangoli uguali) e sembra gridato. `readable()` (`text/readable.ts`) lo
trasforma solo **per mostrarlo**:

| Dalla banca | Sullo schermo |
|---|---|
| PIZZERIA DA GINO | Pizzeria da Gino |
| PAGAMENTO POS AMAZON.IT | Pagamento POS Amazon.it |
| ACME SRL STIPENDIO | Acme SRL Stipendio |
| GAMMA LUCE E GAS S.P.A. | Gamma Luce e Gas S.P.A. |

Le regole, in ordine: un testo che ha **già delle minuscole** resta com'è (l'ha scritto una persona). Poi, parola
per parola:

1. le **sigle** di un elenco (SRL, SPA, POS, SDD...) e le lettere puntate (S.P.A.) restano maiuscole;
2. la parola dopo un punto attaccato (`.IT`, `.COM`) è la fine di un indirizzo, e resta minuscola;
3. le **parole piccole** (da, di, e, per...) restano minuscole, tranne la prima;
4. le altre hanno la maiuscola iniziale, anche dopo un apostrofo ("Dell'Acqua").

Le espressioni regolari usano le **classi Unicode**: `\p{L}` è "una lettera di qualsiasi alfabeto" (anche "È"),
`\p{Ll}` "una lettera minuscola". Servono il flag `u` e, in Java, la stessa sintassi (`docs/32`).

Nel database, nella ricerca e nei Dettagli di un movimento il testo resta **quello originale**. `nameOf` usa
`readable`; nei template c'è la pipe `{{ nome | readable }}` (suggerimenti, gruppi di Analisi, abbonamenti).
Anche il nome proposto per un abbonamento ora è leggibile: diventa il nome salvato.

### Le iniziali colorate

Nell'elenco ogni movimento ha un cerchio con l'iniziale, come il logo nelle app delle banche. Il colore viene dal
nome, con un piccolo **hash**:

```ts
export function avatarHue(transaction: Transaction): number {
  let hash = 0;
  for (const char of nameOf(transaction).toUpperCase()) {
    hash = (hash * 31 + char.codePointAt(0)!) % 360;
  }
  return hash;
}
```

Lo stesso nome dà sempre lo stesso numero, quindi lo stesso esercente ha sempre lo stesso colore, e lo si riconosce
a colpo d'occhio scorrendo l'elenco. Il numero è una **tonalità** (0-360, il giro dei colori), passata al CSS come
variabile: `[style.--hue]="avatarHue(t)"`.

```css
.avatar {
  background: light-dark(oklch(0.94 0.045 var(--hue)), oklch(0.33 0.07 var(--hue)));
  color: light-dark(oklch(0.45 0.13 var(--hue)), oklch(0.88 0.09 var(--hue)));
}
```

**`oklch()`** descrive un colore con tre numeri: luminosità, intensità e tonalità. Con `rgb()` o `hsl()` un giallo e
un blu "alla stessa luminosità" non sembrano affatto ugualmente chiari. In `oklch` sì: fissate luminosità e
intensità, cambia solo la tonalità, e il testo scuro sul fondo chiaro si legge su **tutti** i 360 colori.

---

## Parte F: il movimento, e chi non lo vuole

Pochi movimenti, brevi, tutti con la stessa curva (`--ease`: parte veloce e rallenta, come un oggetto che si posa):

| Che cosa | Come |
|---|---|
| una pagina che si apre | sale di 8 px e appare (0,4 s) |
| le barre del budget, di "Dove vanno", dei gruppi | si riempiono da sinistra (`scaleX` da 0) |
| il foglio di scelta (categoria, tipo) | sale dal basso; il fondo dietro si sfoca |
| il grafico del mese, le barre di Analisi | Parti G e H |

```css
@media (prefers-reduced-motion: no-preference) {
  main > * {
    animation: page-in 0.4s var(--ease) both;
  }
}
```

**`prefers-reduced-motion`** è un'impostazione del sistema ("Riduci movimento" su iPhone e Mac, "Rimuovi
animazioni" su Android e Windows): chi soffre il movimento sullo schermo la attiva. Le animazioni partono solo se
**non** l'ha chiesto (`no-preference`), e in fondo una regola le azzera comunque tutte. È anche una regola di
accessibilità (WCAG 2.3.3).

`main > *` è la pagina aperta dal router: un elemento nuovo a ogni cambio di pagina, quindi l'animazione riparte da
sola. `both` tiene lo stato iniziale durante l'attesa e quello finale alla fine.

---

## Parte G: il grafico a linea del Riepilogo

### Tre disegni uno sopra l'altro

```
.month-chart-wrap            (position: relative)
├── svg.month-chart          le linee di riferimento, il mese tipico tratteggiato, le date
├── svg.layer.area-layer     l'area sotto la linea                    → maschera CSS: la sfumatura
└── svg.layer.line-layer     la linea, il punto di oggi, lo scorrimento → filtro CSS: l'alone
```

Hanno lo stesso `viewBox`, e gli strati sopra sono `position: absolute; inset: 0`: si sovrappongono al pixel.

Perché tre e non uno? Per la sfumatura sotto la linea la via "da manuale" è un `<linearGradient>` di SVG, richiamato
con `fill="url(#id)"`. Ma le app Angular hanno `<base href="/">` nella pagina, e con quello alcuni browser cercano
`#id` all'indirizzo sbagliato: la sfumatura sparisce. Allora l'area è dell'accento **pieno**, e la sfumatura la fa
una **maschera CSS** su tutto il suo strato:

```css
.area-layer {
  mask-image: linear-gradient(to bottom, rgb(0 0 0 / 0.38), rgb(0 0 0 / 0) 82%);
}
```

La maschera dice "quanto si vede" punto per punto: il 38% in alto, niente all'82% dell'altezza. Allo stesso modo
l'**alone** della linea è un filtro CSS sul suo strato: `filter: drop-shadow(...)`, un'ombra dell'accento.

### La curva morbida (e onesta)

La linea non è più una spezzata ("L" da un giorno all'altro) ma una serie di curve di Bézier ("C"). Non una curva
qualsiasi, però: una **spline monotona** (il metodo di Fritsch e Carlson, `smoothPath`). In ogni punto la pendenza è
la media "armonica" delle due vicine, e diventa zero dove la curva cambia verso:

```ts
return a * b <= 0 ? 0 : (2 * a * b) / (a + b);
```

Perché tanta cura? La spesa accumulata **non scende mai**. Una curva qualsiasi, dopo un giorno di spese e uno senza,
"rimbalzerebbe" un poco verso il basso: un grafico bello e falso. Questa non va mai sopra il punto più alto né sotto
il più basso tra due giorni, e il test lo controlla.

### La linea che si disegna

```html
<path class="month" pathLength="1" [attr.d]="monthPath()" />
```

```css
.month-chart .month {
  stroke-dasharray: 1;
  animation: draw-line 1.1s var(--ease) both;
}
@keyframes draw-line {
  from { stroke-dashoffset: 1; }
  to   { stroke-dashoffset: 0; }
}
```

Il trucco classico. Il tratteggio ha un solo trattino lungo quanto la linea (`pathLength="1"` dice "considera la
linea lunga 1", qualunque sia la sua lunghezza vera). Spostato tutto fuori (`dashoffset: 1`) la linea non si vede;
riportato a 0 si disegna da sinistra. Poi appare la sfumatura, e il punto di oggi comincia a **pulsare**: un cerchio
dietro che si allarga e sparisce, solo nel mese in corso (`[live]`), perché è il dato che sta ancora cambiando.

Quando si cambia mese il disegno deve ripartire, ma Angular riusa gli elementi. Il trucco è un `@for` su un elenco
di **un solo** elemento, la chiave del mese:

```html
@for (k of [animationKey()]; track k) { ... il disegno ... }
```

Cambia la chiave, cambia il "track": Angular toglie il vecchio disegno e ne crea uno nuovo, con l'animazione.

### Scorrere i giorni

Passando il dito (o il mouse) sul grafico compaiono una linea verticale tratteggiata, il punto del mese e quello del
mese tipico, e un'etichetta: "12 ott · di solito 402 €". La **cifra grande** sopra diventa quella del giorno: "Speso
fino al 12 ott: 447 €". È il componente che lo dice alla pagina, con un `output`:

```ts
readonly scrubbed = output<number | null>();
```

```html
<app-month-chart ... (scrubbed)="scrubDay.set($event)" />
```

Il giorno si calcola dalla posizione: `(event.clientX - box.left) / box.width` è la frazione della larghezza (da 0 a
1), moltiplicata per i giorni del mese.

- **`pointermove` / `pointerdown`** valgono per il mouse, il dito e la penna: un evento solo invece di
  mouse e touch separati.
- **`touch-action: pan-y`**: il dito in verticale scorre la pagina come sempre; in orizzontale scorre i giorni.
- **La tastiera**: il riquadro è un `role="slider"` ("cursore") con `tabindex="0"`. Le frecce cambiano giorno, Inizio
  e Fine vanno al primo e all'ultimo, Esc smette. `aria-valuetext` dice al lettore di schermo la frase intera:
  "12 ott: 447 €, di solito 402 €".

L'etichetta del giorno è **HTML** sopra il disegno, non testo SVG: il disegno si allarga con la scheda (sul PC quasi
il doppio), un testo HTML resta nitido e della sua misura.

---

## Parte H: il grafico a barre di Analisi

- **Le barre tenui** sono dell'accento mescolato con il colore della scheda, non grigie:

  ```css
  .bar-chart .bar { fill: color-mix(in srgb, var(--acc) 24%, var(--card)); }
  .bar-chart .bar.on { fill: var(--acc); filter: drop-shadow(...); }
  ```

  Il grafico resta "di un colore solo", e quella scelta si stacca con il pieno e l'alone. Mescolare con `--card`
  invece che con il trasparente dà un colore pieno: la linea della media dietro non si vede attraverso.
- **A pillola**: l'angolo in cima è largo fino a mezza barra.
- **Crescono** dalla linea dello zero, una dopo l'altra:

  ```css
  .bar-chart .bar {
    transform-box: fill-box;
    transform-origin: 50% 100%;
    animation: grow-y 0.7s var(--ease) both;
    animation-delay: calc(var(--i, 0) * 25ms);
  }
  ```

  `transform-box: fill-box` fa partire l'ingrandimento dalla barra stessa: senza, per un elemento SVG l'origine
  sarebbe l'angolo del disegno intero. `--i` è il numero della barra (`[style.--i]="$index"`): 25 millesimi di
  ritardo ciascuna, un'onda. Le barre del netto che vanno in giù crescono dall'alto (`transform-origin: 50% 0`).
  La chiave del `@for` (Parte G) qui comprende i valori: le barre ricrescono quando cambiano i dati, non quando se
  ne sceglie un'altra.
- **Con il mouse** la barra sotto il puntatore si accende un poco: ogni barra è in un gruppo `<g class="slot">` con il
  suo rettangolo toccabile, e `.slot:hover .bar` la colora.
- **La media** è una linea tratteggiata; la scritta "media 709 €" è una **legenda** in alto a destra, con un
  trattino uguale. La prima prova la metteva sulla linea, a destra: copriva la cima delle barre più alte.

---

## Parte I: i test, e la prova nel browser

Frontend, 180 test (172 prima):

- `readable.spec.ts`: maiuscolo → leggibile, le sigle, gli indirizzi, gli apostrofi, i testi già scritti bene;
- `month-chart.spec.ts`: il tracciato ha un punto per giorno (con le curve, i punti si contano alla fine di ogni
  "C"); la base è più in basso; le frecce scorrono i giorni e `aria-valuetext` dice la cifra; `smoothPath` passa per
  tutti i punti e non va mai oltre;
- `file-drop.spec.ts`: c'è il vero campo file, l'evento originale arriva, si vede il nome;
- aggiornati, per i nomi leggibili e l'iniziale nel cerchio: elenco e dettaglio dei movimenti, suggerimenti,
  Analisi.

Nel browser (Playwright: telefono 390 px e PC 1366 px, giorno e notte), con un anno di movimenti inventati:

- nessuna pagina più larga dello schermo;
- il grafico del mese: la linea si disegna, il punto pulsa; con il mouse al 35% della larghezza la cifra grande
  diventa "Speso fino al 12 ott";
- le barre di Analisi, anche il netto; l'alone della barra scelta si vede (il filtro CSS su un elemento SVG
  funziona);
- la zona del file nelle pagine Importa e Impostazioni.

Tre difetti trovati così e corretti:

1. l'etichetta del giorno, nera e grande, copriva la frase sotto la cifra e ripeteva la cifra: ora è una piccola
   pillola con la data e il solito, e la cifra la mostra la cifra grande;
2. la legenda della media copriva le barre (Parte H);
3. dentro un modulo a griglia la zona del file prendeva lo stile delle etichette (in colonna, piccola e grigia):
   una regola più specifica la riporta in riga.

Quello che il browser scarica all'avvio è passato da 346 a 361 kB (da 95 a 98,5 kB compressi), più il carattere: il
bilancio della build resta sotto i limiti.

---

## Parte J: esercizi guidati

1. **Il colore dell'esercente.** Cambia 31 in 37 in `avatarHue`. Cambiano i colori? Perché lo stesso nome dà ancora
   sempre lo stesso colore?
2. **La curva bugiarda.** In `smoothPath` sostituisci la media armonica con la media semplice `(a + b) / 2`, senza
   il caso `a * b <= 0`. Quale test fallisce? Disegna su carta i punti del test per capire perché.
3. **Ridurre il movimento.** Nel browser, Strumenti per sviluppatori → Rendering → "Emulate CSS media feature
   prefers-reduced-motion: reduce". Ricarica il Riepilogo: che cosa resta fermo?
4. **Una sigla in più.** "BANCOMAT PAY" diventa "Bancomat Pay". Aggiungi una parola all'elenco delle sigle e il suo
   caso al test. Quando una sigla in elenco potrebbe dare fastidio (pensa a "IP" e "IN")?
