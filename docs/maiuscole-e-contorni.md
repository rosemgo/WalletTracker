# Le maiuscole, il riquadro dopo un clic e le azioni spezzate

Fuori dalla numerazione, come `docs/grafici-tocco.md`. Nasce da due segnalazioni:

1. nei Movimenti, sotto "Nuovo versamento Robo advisor", c'era scritto "**a**cquisto titoli", con la minuscola, mentre
   sotto gli altri movimenti c'era "**S**pesa alimentare";
2. nella pagina Conti, dopo un clic su "cambia il logo", attorno al link restava un **riquadro**.

Controllando tutta l'app ne sono usciti altri dello stesso tipo. Qui c'è che cosa erano e come sono stati corretti.

## Parte A: le maiuscole

### A1. La regola

**Una riga che si legge da sola comincia con la maiuscola.** Un nome, un valore in una colonna, un pulsante, un link
che sta per conto suo: "Senza categoria", "Conto manuale", "Rinomina", "Elimina".

Resta minuscolo solo quello che **continua** qualcosa scritto subito prima:
- una parola **dentro una frase**: "Nessuna categoria: sceglila tu, o **crea una regola**.";
- una riga che **completa una cifra** sopra di lei: "165,70 €" e sotto "**al** 2 nov 2026 · scritto da te" (si legge
  "165,70 € al 2 nov 2026"); "320 € **su** 500 €";
- il modulo delle Regole, che si legge come una frase sola: "Se il testo… **contiene**… **queste parole**… **allora il
  tipo è**…".

### A2. Perché "acquisto titoli" era minuscolo

La riga sotto il nome di un movimento è fatta di pezzi uniti da " · ": **la categoria** · **il conto**. Le categorie
hanno la maiuscola ("Spesa alimentare"). Ma un acquisto di titoli non ha una categoria (il tipo dice già che cos'è,
`docs/categorie-e-tipi.md`): al suo posto l'app scrive **il tipo**. E lo trasformava apposta in minuscolo
(`toLowerCase()`), come se fosse in mezzo a una frase. Lo stesso per "senza categoria" e "da verificare". Ora
restano come sono: "Acquisto titoli · Banca Profilo", "Senza categoria · ING", "Da verificare".

### A3. Le date

Angular scrive le date in italiano come si scrivono in una frase, con il giorno in minuscolo: "giovedì 5 novembre
2026". A inizio riga (nella scheda di un movimento, nell'elenco dello stesso esercente, nel Riepilogo) ora c'è
"Giovedì 5 novembre 2026", come già nei titoli dei giorni dell'elenco dei Movimenti. Lo fa un piccolo **pipe**,
`upperFirst` (`frontend/src/app/text/upper-first.pipe.ts`): un "filtro" che si mette dopo un valore nel template,
`{{ data | date: 'EEEE d MMMM y' | upperFirst }}`, e ne alza la prima lettera.

### A4. Che cosa è cambiato

| Pagina | Prima | Adesso |
|---|---|---|
| Movimenti (la riga sotto il nome) | acquisto titoli, senza categoria, da verificare | Acquisto titoli, Senza categoria, Da verificare |
| scheda del movimento | giovedì 5 novembre 2026; "senza categoria" nell'elenco dello stesso esercente | Giovedì 5 novembre 2026; Senza categoria |
| Riepilogo | la data delle spese recenti | con la maiuscola |
| Conti | rinomina, vedi i movimenti, senza IBAN, non ancora scritto, la banca non lo dà, al prossimo aggiornamento, plafond non ancora letto, scrivi/correggi il saldo, conto manuale, importa un file, carica/cambia il logo, togli il logo, elimina, annulla, completa con il file | tutti con la maiuscola |
| Categorie | rinomina, elimina, annulla | Rinomina, Elimina, Annulla |
| Abbonamenti | togli, no, non suggerirlo più, suggeriscilo di nuovo | Togli, No, non suggerirlo più, Suggeriscilo di nuovo |
| suggerimenti di categoria | no | No (accanto ad "Accetta") |
| Impostazioni | copia / copiato ✓ (gli indirizzi di Enable Banking); cambia / togli (la chiave dell'IA nel cloud) | Copia / Copiato ✓; Cambia / Togli |
| Budget | restano…, superato di… | Restano…, Superato di… |
| Investimenti | netti: …; al 14 ott 2026 · dalla banca; saldo non scritto; la banca non dà il saldo | con la maiuscola |
| Collega una banca, Regole | consenso fino a…, regola di partenza | Consenso fino a…, Regola di partenza |
| Importa | le scelte dei menu ("una colonna, con il segno", "virgola") | Una colonna, con il segno; Virgola |

## Parte B: il riquadro dopo un clic

### B1. Il focus, dalle basi

In una pagina, un solo elemento alla volta ha il **focus**: è quello che riceve i tasti. Premi Tab e il focus passa
al link o al pulsante dopo; premi Invio e lo "clicchi". Chi usa la tastiera (o non vede bene lo schermo) deve sapere
**dove** è il focus: per questo il browser gli disegna attorno un **contorno** (`outline`).

Ma anche un **clic con il mouse** dà il focus all'elemento cliccato. Lì il contorno non serve: sai già dove hai
cliccato, e un riquadro che resta lì sembra un difetto.

Il CSS ha tre modi di dire "quando ha il focus":

| Selettore | Vale quando | Con il mouse? |
|---|---|---|
| `:focus` | l'elemento ha il focus | **sì** |
| `:focus-within` | l'elemento **o qualcosa dentro di lui** ha il focus | **sì** |
| `:focus-visible` | l'elemento ha il focus **e** il browser pensa che il contorno serva: con la tastiera sì, con il mouse di solito no (ma sì nei campi in cui si scrive) | **no** |

Nell'app i contorni sono disegnati con `:focus-visible` (in `styles.css`: `:focus-visible { outline: 2px solid
var(--acc) }`). Tranne in due posti.

### B2. Il link "Carica il logo"

"Carica il logo" non è un vero link: è un'**etichetta** (`<label>`) che contiene un **campo per scegliere un file**
(`<input type="file">`), nascosto. Cliccare l'etichetta è come cliccare il campo: si apre la finestra per scegliere
l'immagine. Il campo è nascosto perché quello vero del browser ("Sfoglia… Nessun file selezionato") è brutto e
diverso su ogni sistema.

Siccome il campo è nascosto, il contorno per chi usa la tastiera va disegnato **sull'etichetta**. Era scritto così:

```css
.logo-upload:focus-within { outline: 2px solid var(--acc); }
```

"Contorno all'etichetta quando qualcosa dentro di lei ha il focus". Ma con il clic del mouse il campo riceve il focus
lo stesso: il contorno compariva, e restava finché non si cliccava altrove. Il riquadro della tua schermata. (Il
pezzetto di riquadro accanto a "importa un file" era lo stesso contorno: l'etichetta cominciava alla fine della riga
prima e andava a capo, e il contorno si disegna su ogni pezzo di riga.)

Adesso:

```css
.logo-upload:has(input:focus-visible) { outline: 2px solid var(--acc); }
```

`:has(…)` vuol dire "che contiene": "contorno all'etichetta che contiene un campo con `:focus-visible`". Con la
tastiera c'è, con il mouse no. Provato nel browser: dopo il clic con il mouse nessun contorno, con Tab il contorno.

La zona "Scegli un file o trascinalo qui" di Importa aveva lo stesso difetto (il bordo restava acceso dopo aver scelto
il file con il mouse), e ha la stessa correzione.

L'unico altro contorno con `:focus-within` è quello del **campo di ricerca** dei Movimenti, ed è giusto così: è un campo in cui
si scrive, e il contorno dice dove stai scrivendo.

## Parte C: le azioni spezzate a metà

Nella pagina Conti, in una colonna stretta, "Carica il logo" andava a capo in mezzo: "Carica il" su una riga, "logo"
sull'altra; lo stesso "Scrivi il / saldo". Ora le azioni (`button.link`, `label.link`, e i link `a.action` della pagina
Conti) hanno `white-space: nowrap`, "non andare a capo dentro": si va a capo **tra** un'azione e l'altra, mai in
mezzo. I testi delle azioni sono tutti brevi, quindi non escono mai dallo schermo.

## Parte D: verifiche

- test della dashboard: 263, tutti passati (aggiornati quelli che cercavano i testi in minuscolo; nuovi quelli di
  `upperFirst`). **Un difetto nelle prove stesse:** qui, lanciati tutti insieme, i test passavano anche quando tre di
  loro cercavano ancora i testi vecchi ("sabato 24 ottobre", "martedì 3 novembre", "no, non suggerirlo più"); la CI li
  ha trovati. Lanciato **da solo**, lo stesso file falliva: il risultato dipendeva dall'ordine in cui giravano i file.
  Corretti i tre test, ora ogni file modificato è stato provato anche da solo
  (`npx ng test --watch=false --include <file>`), e la CI è verde;
- nel browser (Chromium), sulla pagina Conti con un conto manuale finto ("Banca Alfa"): dopo il clic con il mouse su
  "Carica il logo" nessun contorno, con Tab il contorno c'è; le azioni non vanno più a capo a metà;
- la ricerca di tutti i testi che cominciano in minuscolo, file per file: i casi rimasti minuscoli sono quelli della
  regola A1 (parole dentro una frase, righe che completano una cifra, il modulo delle Regole).

---

## Aggiunta: le azioni di un conto manuale, una per riga

**Il difetto.** Nella pagina Conti, per un conto manuale, l'ultima colonna aveva le azioni una dopo l'altra, separate
da "·": "Importa un file · Cambia il logo · Togli il logo · Elimina". La colonna è stretta, e il testo andava a capo
dove capitava: il "·" restava in fondo a una riga, "Elimina" cominciava con uno spazio, e non si capiva un ordine.
`white-space: nowrap` (la parte sopra) teneva intera ogni azione, ma non poteva mettere in fila l'elenco.

**La correzione.** Le azioni sono un elenco vero (`ul.account-actions`), una per riga, allineate a sinistra, senza
"·". **"Elimina" sta in fondo, un poco staccata, in rosso** (`button.link.danger`): cancella il conto con tutti i suoi
movimenti, e non deve stare in mezzo alle altre. La conferma ha il pulsante pieno rosso (`button.danger`): testo bianco
sul rosso scuro del tema chiaro, testo scuro sul rosso chiaro del tema scuro, dove il bianco si leggeva male.

Due ritocchi trovati guardando la pagina:
- "Importa un file" è un link (porta a un'altra pagina), le altre sono pulsanti: era l'unica sottolineata. Nell'elenco
  ora hanno lo stesso aspetto, e la sottolineatura compare passandoci sopra, come per i pulsanti;
- tra "Elimina" e "Annulla" della conferma lo spazio era doppio: la regola generale `td button + button` (un margine
  tra due pulsanti vicini in una tabella) si sommava allo spazio del contenitore. Lì il margine è tolto.

**I test.** `accounts-page.spec.ts`: le azioni in quest'ordine, una per riga, e "Elimina" in fondo con la classe
`danger`. Nel browser: a 1136 pixel e sul telefono, le quattro azioni con lo stesso bordo sinistro; la conferma nel
tema scuro e in quello chiaro.

