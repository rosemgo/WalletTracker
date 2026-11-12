# Il tocco sulle barre: niente lampo, niente rettangolo

Fuori dalla numerazione, come `docs/tema-blu-oceano.md`. Nasce da due segnalazioni dell'utente sui grafici a barre
(Analisi e la pagina di una categoria):

1. toccando una barra, in modalità Notte la pagina faceva una specie di **lampo** mentre cambiavano i dati; di giorno
   no;
2. sul telefono, durante il tocco compariva un **rettangolo** più grande della barra.

## Il lampo: l'attesa che si vedeva troppo

Quando arrivano dati nuovi, le pagine si oscurano un poco (classe `.loading`, opacità al 60%): così si capisce che
quello che si vede sta per cambiare. Toccando una barra, le schede sotto il grafico ("Entrate", "Uscite", "Dove")
chiedono i dati del periodo scelto. La risposta arriva in un attimo, circa 0,2 secondi: le schede passavano al 60% e
tornavano subito piene.

Misurato nel browser, un fotogramma alla volta: 11 fotogrammi su 55 con le schede oscurate, sia di giorno sia di
notte. Perché si vedeva solo di notte? Di giorno le schede sono bianche su un fondo quasi bianco: al 60% cambiano
poco. Di notte il testo è chiaro su un fondo scuro: spegnerlo e riaccenderlo in un quinto di secondo è un lampo.

La correzione è una riga di CSS, valida per tutte le pagine (`frontend/src/styles.css`, la regola `.loading`):

```css
.loading {
  transition: opacity 0.2s ease 0.3s;
}
```

`transition` con un **ritardo** (il secondo tempo, 0,3 s): la pagina comincia a oscurarsi solo se l'attesa dura più
di 0,3 secondi, e poi lo fa piano. Quando la classe se ne va, l'opacità torna subito piena; se non aveva ancora
cominciato a calare, non succede niente. Risultato:

- risposta veloce (il caso normale): **0 fotogrammi** oscurati, niente lampo;
- risposta lenta (provata rallentando il backend a 0,8 secondi): le schede si oscurano dolcemente dopo 0,3 secondi,
  e si capisce che si sta aspettando.

## Il rettangolo: l'evidenziazione del tocco

Ogni barra ha un'area toccabile alta quanto tutto il grafico e larga quanto il suo spazio (`rect.hit` in
`bar-chart.ts`): così anche una barra bassa si prende con il pollice. Sul telefono, Chrome e Safari **illuminano**
l'elemento toccato per tutta la durata del tocco, con un rettangolo semitrasparente: era quello il rettangolo più
grande della barra.

La correzione è `-webkit-tap-highlight-color: transparent` sul contenitore del grafico (`.bar-chart-wrap`): la barra
scelta si accende già da sola, il rettangolo non serve. Era già stato fatto per il grafico del Riepilogo e per i
pulsanti; mancava qui. Resta il contorno per chi usa la tastiera (`:focus-visible`).

## La prova

Nel browser, a misura di telefono, con dati inventati: Analisi, Netto, il tocco su una barra, l'opacità delle schede
letta a ogni fotogramma per un secondo, di giorno e di notte, con il backend normale e rallentato.
