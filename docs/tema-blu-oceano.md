# Il nuovo logo, il tema Blu oceano e i colori dei grafici

Fuori dalla numerazione, come `docs/loghi.md`. Nasce da tre richieste dell'utente: un logo più esclusivo, con la W e
la T di WalletTracker; un colore al posto del blu-viola, che non piaceva; i grafici che dicono con il colore se si sta
andando bene o male.

| Parte | Cosa |
|---|---|
| A | il logo: la W attraversata dalla T |
| B | il tema Blu oceano: i colori cambiati |
| C | il grafico del Riepilogo: verde sotto il solito, rosso sopra |
| D | il Netto di Analisi: barre verdi e rosse |
| E | i test, e la prova nel browser |

---

## Parte A: il logo

Delle quattro proposte (una W che diventa T, un tracciato, un monogramma, delle barre) è stato scelto il
**monogramma**: una W nella sfumatura dell'accento, dal blu al ciano, attraversata da una **T bianca**, su un fondo
blu notte. La T passa *sopra* la W: intorno alla T c'è un alone del colore del fondo, che stacca le due lettere dove si
incrociano.

Come prima (`docs/42`, parte D), il logo è disegnato con **linee**, non con lettere: non dipende dal carattere
installato. I file:

- `frontend/public/icons/icon.svg`: il disegno, con gli angoli arrotondati;
- `frontend/public/icons/icon-maskable.svg`: lo stesso disegno, più piccolo, con il fondo fino ai bordi (Android
  ritaglia l'icona con la sua forma);
- i PNG (`icon-192.png`, `icon-512.png`, `favicon-32.png`, `icon-maskable-512.png`, `apple-touch-icon.png`), fatti da
  Chromium aprendo gli SVG e fotografandoli con Playwright.

Il marchio in alto a sinistra nell'app (e nella pagina del PIN) prima era una "W" scritta su un quadratino colorato.
Ora è **la stessa immagine dell'icona** (`<img class="brand-mark" src="icons/icon.svg">`): un disegno solo, ovunque.

Sul telefono, l'icona dell'app già installata può restare quella vecchia per un po': il telefono la ricorda. Si
aggiorna da sola nei giorni seguenti, o subito disinstallando e reinstallando l'app.

## Parte B: il tema Blu oceano

Tutti i colori dell'app passano da poche variabili all'inizio di `frontend/src/styles.css` (`docs/41`). Sono cambiate
queste, di giorno e di notte:

| Variabile | A cosa serve | Giorno | Notte |
|---|---|---|---|
| `--acc` | link, la sezione attiva, i grafici | `#1d4ed8` | `#60a5fa` |
| `--acc-2` | il secondo colore delle sfumature | `#0891b2` | `#0ea5c6` |
| `--acc-strong` | i pulsanti pieni, con il testo bianco | `#1d4ed8` | `#2563eb` |
| `--soft` | i riquadri da notare | `#e6eeff` | `#13213f` |
| `--pos` | entrate, risparmio | `#1d4ed8` | `#60a5fa` |

Il viola (`--acc-2`) è diventato un **ciano**, un po' più scuro di quello della proposta, che era troppo chiaro. Il blu
pieno dei pulsanti resta abbastanza scuro perché il testo bianco si legga bene.

La scelta del colore da parte dell'utente non c'è: si è deciso un colore solo. Se un giorno servirà, basterà un
attributo sulla pagina (come `data-theme` per giorno e notte, `ThemeStore`) che cambia queste cinque variabili.

## Parte C: il grafico del Riepilogo

Il grafico mette a confronto la spesa del mese, accumulata giorno per giorno, con quella di un **mese tipico**, la
linea tratteggiata: la **media degli ultimi 6 mesi** allo stesso giorno (`MonthCurves.usualUpTo` nel backend). Ora il
colore dice da che parte stai:

- **verde** se sei sotto il solito (o in linea: meno di 5 € di differenza, non è un allarme);
- **rosso** se sei sopra di almeno 5 €;
- il colore del tema se un solito non c'è ancora (il primo mese).

Il confronto è quello della frase sopra il grafico (`comparisonSentence`), quindi i due non si contraddicono mai. La
regola è in `trendOf` (`overview-page.ts`); il grafico riceve il risultato (`trend`) e mette una classe,
`trend-under` o `trend-over`.

Il grafico resta "vivo" come prima: la curva che si disegna, la **sfumatura** sotto la linea, l'alone e il punto di
oggi che pulsa. Prendono tutti il colore da una variabile sola, `--trend`, che le due classi cambiano. Dalla stessa
variabile prendono il colore anche la frase sopra il grafico (con ▲ o ▼ davanti) e il trattino della legenda.

Il colore non basta da solo: chi distingue male il rosso dal verde legge la frase, con la freccia e la cifra.

> Attenzione al nome. La prima versione usava la variabile `--line`, che però esisteva già: è il colore delle linee di
> separazione, usato anche dalle righe "50 €" e "100 €" del grafico, che così erano diventate rosse. Prima di dare un
> nome a una variabile CSS, cercalo in `styles.css`.

## Parte D: il Netto di Analisi

In Analisi, scegliendo **Netto**, ogni barra è entrate meno uscite: in su se hai risparmiato, in giù se hai speso più
di quanto è entrato. Prima erano blu e arancione; ora sono **verdi** (in su) e **rosse** (in giù), con gli stessi
colori di "tutto bene" (`--ok`) ed "errore" (`--bad`). Le barre non scelte sono un po' più tenui, quella scelta è
piena, con un alone.

## Parte E: i test, e la prova nel browser

- `overview-page.spec.ts`: `trendOf` (sotto, in linea, sopra, senza un solito); la pagina colora la frase, il grafico e
  la legenda, e cambia colore quando il mese va sopra il solito;
- `month-chart.spec.ts`: il grafico senza `trend` resta del colore del tema, poi diventa rosso e verde.

Prova nel browser con dati inventati, di giorno e di notte: il Riepilogo di novembre in verde ("▼ 5 € in meno del
solito"), quello di ottobre in rosso ("▲ 30 € più del solito"), il Netto con due mesi in verde e gli altri in rosso, e
il nuovo marchio in alto.
