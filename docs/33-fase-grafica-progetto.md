# 33. Fase grafica, passo 1: il progetto

**Obiettivo:** prima di scrivere codice, decidere **che cosa** deve mostrare l'app, **dove** e **come**. Il
telefono è lo strumento principale (fuori casa, senza PC): l'utente deve capire **a colpo d'occhio** quanto sta
spendendo, dove e se è "nel solito". Questo documento raccoglie le scelte; le bozze visive sono in un canvas su
claude.ai (link privato, nella conversazione).

| Parte | Cosa |
|---|---|
| A | le domande a cui l'app risponde |
| B | le funzionalità, in ordine di importanza |
| C | la struttura: cinque sezioni in basso |
| D | le schermate e i loro grafici |
| E | i grafici che **non** usiamo, e perché |
| F | "non sembrare fatta dall'IA": le regole visive |
| G | il telefono: che cosa cambia |
| H | le tre direzioni grafiche |
| I | che cosa serve al backend |
| J | le scelte tecniche da fare |
| K | i passi successivi |

---

## Parte A: le domande a cui l'app risponde

Un'app di finanza personale serve se risponde in pochi secondi a poche domande. In ordine di frequenza:

1. **"Come sto andando questo mese?"**: quanto ho speso finora, ed è tanto o poco **rispetto al solito**?
2. **"Dove vanno i miei soldi?"**: le categorie che pesano di più, e quali stanno crescendo.
3. **"Che cosa sta per uscire?"**: abbonamenti e spese ricorrenti dei prossimi giorni.
4. **"C'è qualcosa da sistemare?"**: movimenti da verificare, categorie suggerite, budget superati, consensi in
   scadenza.
5. **"Come va nel tempo?"**: mese per mese, quanto spendo e quanto metto da parte.
6. **"Dov'è quel movimento?"**: trovarne uno al volo (la ricerca di `docs/32`).

Ogni schermata risponde ad **alcune** di queste domande, non a tutte. La Home risponde alle prime quattro; il resto è
un tocco più in là.

Il punto chiave è il **confronto con il solito**. "Hai speso 1.284 €" da solo non dice niente; "126 € in meno del
solito, a questo punto del mese" dice subito se va bene. Per questo il grafico principale non è "spese per giorno",
ma la spesa **accumulata** del mese contro quella di un mese tipico (la media degli ultimi mesi, giorno per giorno).

---

## Parte B: le funzionalità, in ordine di importanza

**Indispensabili (la prima versione):**

- Home "Oggi": speso nel mese, confronto con il solito, grafico del mese, categorie principali con la differenza dal
  solito, spese in arrivo, cose da sistemare;
- Movimenti: elenco per giorno con il totale del giorno, ricerca, filtri rapidi a "pillole" (mese, da verificare,
  conto, entrate), categoria e suggerimenti direttamente nella riga;
- Analisi: spese mese per mese, quanto metti da parte, categorie nel periodo, calendario dei giorni;
- Dettaglio categoria: andamento, budget con il ritmo, dove (gli esercenti), movimenti;
- Budget: le barre con il ritmo del mese (esistono già: vanno ridisegnate);
- **Modalità discreta**: un tocco nasconde gli importi (in metro o in ufficio, qualcuno guarda lo schermo);
- **Installabile sul telefono** (PWA): icona nella schermata Home, si apre a schermo intero come un'app.

**Utili (dopo):**

- Dettaglio esercente (quante volte, quanto, ogni quanto);
- Riepilogo del mese appena finito, il primo del mese ("A ottobre: 1.510 €, 40 € meno del solito; Bar e ristoranti
  la categoria cresciuta di più");
- Tema scuro, scelto in automatico dal telefono;
- Notifiche (budget quasi finito, abbonamento rincarato): richiedono una PWA con notifiche push, più avanti.

**Non ora:** inserire spese a mano (i movimenti arrivano dalle banche), obiettivi di risparmio, condivisione con
altre persone.

Le pagine "di servizio" (Conti, Collega una banca, Regole, Categorie, Importa, Impostazioni) restano, ma finiscono
sotto **Altro**: si usano di rado, quasi sempre dal PC.

---

## Parte C: la struttura, cinque sezioni in basso

Oggi le pagine sono dieci voci in un menu in alto: sul telefono vanno a capo su quattro righe. Sul telefono il
modello giusto è una **barra in basso** con cinque voci, raggiungibile con il pollice:

| Sezione | Che cosa c'è |
|---|---|
| **Oggi** | la Home (Parte D1) |
| **Movimenti** | elenco, ricerca, filtri, da verificare, suggerimenti |
| **Analisi** | andamento, categorie, calendario; da qui il dettaglio di una categoria |
| **Budget** | i budget del mese, con il ritmo |
| **Altro** | abbonamenti, conti, regole, categorie, importa, impostazioni |

Sul PC la stessa barra diventa una colonna a sinistra: stessa struttura, più spazio.

---

## Parte D: le schermate e i loro grafici

### D1. Oggi

- **La cifra**: "Speso finora 1.284 €", grande, e sotto "126 € in meno del solito, a questo punto del mese". Una
  sola cifra grande per schermata.
- **Il grafico del mese**: una linea della spesa accumulata dal 1° a oggi (blu), contro la linea di un mese tipico
  (grigia). Si capisce a colpo d'occhio se sei sopra o sotto, e se stai accelerando. Una sola scala, due linee, la
  legenda sotto.
- **Dove vanno**: le cinque categorie che pesano di più, con una barra sottile (tutte dello stesso colore: è la
  lunghezza che conta) e la differenza dal solito ("+32 € sul solito"). Un tocco apre la categoria.
- **In arrivo nei prossimi 7 giorni**: gli abbonamenti confermati (passo degli abbonamenti, `docs/22`).
- **Da sistemare**: movimenti da verificare, categorie suggerite; un tocco porta ai Movimenti già filtrati.

### D2. Movimenti

- Ricerca sempre visibile in alto; sotto, le **pillole** dei filtri, che scorrono in orizzontale.
- I movimenti **raggruppati per giorno**, con il totale del giorno: "Ieri · venerdì 24 · −49,98 €".
- Ogni riga: esercente, "categoria · conto", importo. Gli importi in colonna, con cifre della stessa larghezza.
- Un movimento "Da verificare" ha il suo segno e il pulsante "Scegli la categoria"; un suggerimento si accetta o
  rifiuta nella riga stessa.

### D3. Analisi

- **Spese al mese** (12 colonne): il mese corrente in blu, gli altri in grigio, una linea per la media. La frase
  sopra dice la cosa importante: "In media 1.550 € al mese. Agosto il più caro".
- **Quanto metti da parte** (entrate meno spese): colonne in su (blu) quando hai risparmiato, in giù (arancio)
  quando hai speso più di quanto è entrato. Due colori opposti, una linea dello zero.
- **Le categorie nel periodo**: barre orizzontali ordinate, con importo e percentuale.
- **Il mese giorno per giorno**: un calendario in cui il colore dice quanto hai speso (più scuro = di più). Si vedono
  i giorni "pesanti" e le abitudini (il sabato?).

### D4. Una categoria

- Quanto a ottobre, e rispetto al solito;
- il **budget** con la **tacca del ritmo**: dove dovresti essere oggi spendendo in modo regolare. Barra oltre la
  tacca = stai andando troppo veloce;
- gli ultimi 12 mesi della categoria, con la media;
- **dove**: gli esercenti del mese, con quante volte e quanto.

---

## Parte E: i grafici che non usiamo, e perché

| Grafico | Perché no | Al suo posto |
|---|---|---|
| **Torta / ciambella** delle categorie | l'occhio confronta male gli angoli: 18% e 22% sembrano uguali; con più di 5-6 fette diventa illeggibile | barre orizzontali ordinate |
| **Due scale nello stesso grafico** (es. spese a sinistra, numero di movimenti a destra) | l'allineamento delle due scale è arbitrario e inventa relazioni che non ci sono | due grafici, o un'unica scala |
| **Un colore per ogni categoria** nei grafici | con 20 categorie servirebbero 20 colori, impossibili da distinguere (e per chi è daltonico ancora meno) | un colore solo; si evidenzia la cosa importante, il resto in grigio |
| **Un numero su ogni punto** | nessuno li legge | si scrive solo il valore che conta (l'ultimo, il massimo), il resto nel tocco |
| **Tachimetri e "gauge"** | occupano tanto spazio per dire una percentuale | una barra con la tacca del ritmo |
| **Spese giorno per giorno** come grafico principale | è un seghetto: un giorno 3 €, quello dopo 120 €; non dice se il mese va bene | la spesa accumulata contro il mese tipico |

I colori dei grafici sono stati controllati con uno strumento che misura la differenza percepita anche per le forme
più comuni di daltonismo: blu e grigio, blu e arancio, la scala di blu del calendario passano tutti i controlli.

---

## Parte F: "non sembrare fatta dall'IA"

Molte app generate oggi si somigliano tutte. I segni più riconoscibili, e che cosa facciamo invece:

| Lo stile "da IA" | Noi |
|---|---|
| sfumature viola-blu, sfondi con aloni, "vetro smerigliato" | colori pieni, uno sfondo, **un solo** colore d'accento |
| ogni cosa in una "card" con ombra morbida, sfumature, bordo colorato a sinistra | schede piatte: un colore pieno, angoli arrotondati, nessuna ombra |
| il carattere Inter ovunque, titoli enormi e testo grigio chiarissimo | un carattere con personalità (Schibsted Grotesk) e testo ben leggibile al sole |
| emoji come icone, "✨" per le funzioni intelligenti | icone a linea, sobrie; l'IA è una parola ("Suggerita"), non una scintilla |
| quattro riquadri di numeri in cima (KPI) uguali tra loro | una cifra sola, con una frase che la spiega |
| grafici con i colori predefiniti della libreria, torte colorate | grafici disegnati per questa app, pochi colori, frasi al posto delle legende dove si può |
| frasi motivazionali ("Ottimo lavoro! 🎉") | frasi da persona: "126 € in meno del solito" |

---

## Parte G: il telefono, che cosa cambia

- **Pollice**: la navigazione in basso; le azioni importanti nella metà inferiore; ogni cosa toccabile almeno 44×44
  pixel.
- **Sole**: fuori casa lo schermo si legge peggio. Testo scuro su fondo chiaro, contrasto alto (sopra 4,5:1 anche le
  scritte piccole). Il tema scuro c'è, ma non è il principale.
- **Una mano, pochi secondi**: le risposte in cima, i dettagli un tocco più in là; niente tabelle larghe da scorrere
  di lato.
- **Riservatezza**: la modalità discreta (l'occhio in alto) nasconde gli importi; la prossima apertura la ricorda.
- **Rete lenta**: la pagina mostra subito gli ultimi dati avuti e li aggiorna appena arrivano quelli nuovi.
- **Installabile** (PWA): un file `manifest` con nome e icona, e un "service worker" per aprirsi anche con rete
  scarsa. Si aggiunge alla schermata Home dal browser, senza store.
- **Accesso da fuori**: con Tailscale (Fase 4), il telefono raggiunge il computer di casa in modo sicuro.

---

## Parte H: le tre direzioni grafiche

Le bozze mostrano la stessa schermata "Oggi" in tre stili; le altre schermate sono disegnate nella direzione A.

| | A · Estratto | B · Segnaletica | C · Notte |
|---|---|---|---|
| Idea | un estratto conto ben fatto: carta, inchiostro, righe sottili | i cartelli dei trasporti: nero, giallo, scritte forti | scuro, morbido, con un accento ambra |
| Caratteri | IBM Plex Sans e Plex Mono (gli importi) | Archivo, stretto e pesante | Schibsted Grotesk |
| Colori | fondo carta `#F3F2EE`, inchiostro `#16171B`, accento blu `#1F5FD6` | bianco, nero, giallo `#FFD84D` | fondo `#15171A`, superfici `#1F2226`, accento ambra `#E59A2E` |
| Al sole | ottima | ottima | discreta |
| Rischio | sobria: va curata nei dettagli | forte personalità, può stancare | la più vicina a molte app di oggi |

**La mia proposta è la A**: si legge bene al sole, regge molti grafici senza diventare rumorosa, e non somiglia alle
app generate in serie. Dalla C si può prendere il **tema scuro** per la sera.

**La scelta: lo stile C, in due temi (giorno e notte).** Guardandole, A e C sembravano la stessa app di giorno e di
notte; dei due piacciono di più il carattere e le schede arrotondate di C. Quindi:

- **carattere**: Schibsted Grotesk (un grottesco con personalità, ma sobrio), cifre tabulari negli elenchi;
- **schede**: le sezioni sono schede arrotondate (16 px) su uno sfondo appena diverso, **piatte**: niente ombre,
  niente sfumature, niente bordi colorati. È questo che le tiene lontane dallo stile "da IA";
- **due temi**, che il telefono sceglie da solo (`prefers-color-scheme`) e che si possono forzare dalle Impostazioni.

| | Giorno | Notte |
|---|---|---|
| sfondo | `#EEEDE9` | `#15171A` |
| schede, barra in basso | `#FFFFFF` | `#1F2226` |
| testo | `#16171B` | `#ECEDEE` |
| testo secondario | `#5B5D64` | `#A3A8B0` |
| righe | `#E6E4DE` | `#2C3036` |
| accento (il mese corrente, i pulsanti, la sezione attiva) | blu `#1F5FD6` | ambra `#E59A2E` |
| confronto ("il solito") | grigio `#80838B` | grigio `#7E8590` |
| risparmio: messo da parte / in rosso | blu / arancio `#C8571B` | ambra / blu `#6E9CF0` |
| calendario (più spesa = più intenso) | scala di blu, dal chiaro allo scuro | scala di ambre, dallo scuro al luminoso |

L'accento cambia con il tema: il blu su fondo scuro perde forza, l'ambra su fondo chiaro si legge male. Il
significato resta lo stesso: "questo è il dato che conta". Tutte le coppie e le scale dei grafici sono verificate per
il daltonismo nei due temi, sullo sfondo delle schede. Nel canvas: riga 2 il tema giorno, riga 3 il tema notte, con
le stesse quattro schermate (Oggi, Movimenti, Analisi, una categoria). Nelle bozze i colori sono già variabili CSS:
le due versioni di ogni schermata differiscono solo per il tema.

---

## Parte I: che cosa serve al backend

Le schermate chiedono dati già "pronti", calcolati dal backend, non migliaia di movimenti da sommare nel telefono:

| API | Per | Che cosa restituisce |
|---|---|---|
| `GET /api/overview?month=2026-10` | Oggi | speso finora; la spesa accumulata giorno per giorno; il mese tipico (media degli ultimi N mesi, giorno per giorno); categorie del mese con il "solito"; prossime uscite ricorrenti; conteggi da sistemare |
| `GET /api/analysis?from=…&to=…` | Analisi | spese ed entrate per mese; categorie del periodo; spesa di ogni giorno di un mese |
| `GET /api/categories/{id}/overview` | Una categoria | il mese, il solito, il budget e il ritmo, 12 mesi, gli esercenti |

Il "solito" va definito bene: per esempio la **media degli ultimi 6 mesi completi**, escludendo trasferimenti tra
conti propri, investimenti e movimenti ignorati (come già fa il riepilogo, `docs/18`). Lo decideremo con i test.

---

## Parte J: le scelte tecniche da fare

1. **I grafici: una libreria o disegnati da noi?** Le librerie (Chart.js, ECharts) fanno tutto, ma i loro grafici si
   riconoscono, e piegarli a questo stile costa quasi quanto disegnarli. I grafici che servono sono pochi e semplici
   (linee, colonne, barre, calendario): la proposta è **componenti Angular nostri in SVG**, come il grafico mensile
   che c'è già (`summary/monthly-chart`). Più controllo, nessuna dipendenza pesante, e si impara come funziona un
   grafico.
2. **I componenti**: niente kit (Angular Material ha un aspetto suo, molto riconoscibile). Pochi componenti nostri:
   barra di navigazione, pillola, riga di movimento, barra con tacca, cifra grande, sezione.
3. **Lo stile**: i colori, i caratteri e le misure diventano **variabili CSS** (`--ink`, `--paper`, `--accent`...),
   in un posto solo. Il tema scuro è lo stesso elenco con altri valori.
4. **La PWA**: `ng add @angular/pwa` aggiunge `manifest` e service worker.

---

## Parte K: i passi successivi

1. **Scegliere la direzione** (e correggere le bozze finché convincono).
2. **Le basi**: variabili CSS, caratteri, la barra in basso, i componenti comuni; la struttura a cinque sezioni.
3. **Oggi**: l'API `overview` (con i test sul "solito"), poi la schermata.
4. **Movimenti**: il nuovo elenco per giorno, le pillole, i suggerimenti nella riga.
5. **Analisi** e **dettaglio categoria**: le API e i grafici.
6. **Budget** e **Altro**: ridisegnati con i componenti comuni.
7. **PWA**, modalità discreta, tema scuro.

Ogni passo con la sua guida e i suoi test, come sempre.
