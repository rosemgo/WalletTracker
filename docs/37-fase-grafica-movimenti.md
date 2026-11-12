# 37. Fase grafica, passo 4: i Movimenti e il dettaglio di un movimento

**Obiettivo:** l'elenco dei movimenti come nelle app delle banche (per giorno, con il totale del giorno, la ricerca e
i filtri a "pillole") e, toccando un movimento, la sua **scheda**: tutti i dati, la categoria da cambiare con un
tocco (e perché ha quella), "conta come", lo stesso esercente, la nota. Cambiando la categoria, l'app propone di
spostare anche gli altri movimenti dello stesso esercente, e tutto si può **annullare** (`docs/33`, parte M).

| Parte | Cosa |
|---|---|
| A | che cosa cambia, in breve |
| B | il backend: il dettaglio, i simili, la correzione in blocco |
| C | l'Annulla: la risposta è la richiesta inversa |
| D | l'elenco: giorni, pillole, "Mostra altri" |
| E | il dettaglio come pagina figlia: accanto o al posto dell'elenco |
| F | i fogli di scelta con `<dialog>` |
| G | i test |
| H | la prova nel browser |
| I | esercizi guidati |

---

## Parte A: che cosa cambia, in breve

**L'elenco**

- I movimenti sono **raggruppati per giorno**: "Oggi · domenica 25 ottobre", "Ieri · sabato 24 ottobre", "Venerdì 23
  ottobre". Accanto al giorno c'è il **totale**, fatto solo di spese ed entrate vere: un giroconto da 500 € non fa
  sembrare la giornata una catastrofe.
- Ogni riga: il nome (controparte o causale), sotto "categoria · conto" (o il tipo, per un giroconto; "in attesa" se
  non è ancora contabilizzato), la nota se c'è, l'importo (le entrate nel colore delle entrate).
- In alto la **ricerca** (anche nelle note) e le **pillole**: Periodo, Da verificare, Senza categoria, Conto, Tipo.
  Una pillola accesa è piena. Sul telefono scorrono di lato, sul PC vanno a capo.
- Niente più "pagina 1 di 5": in fondo c'è **Mostra altri**, che aggiunge i 50 successivi.
- Il menu della categoria, il menu del tipo, "crea una regola" e la nota non sono più nella riga: sono nella scheda.

**La scheda** (`/movimenti/42`)

1. L'iniziale, il nome, l'importo grande, la data e il conto, lo stato.
2. **Categoria** con "Cambia" e il **perché**: "Scelta da te: le regole non la cambiano", "Messa dalla regola
   «Canone del conto»", "Nessuna categoria: sceglila tu, o crea una regola".
3. **Conta come** (il tipo), con "(scelto da te)" se l'hai corretto.
4. Il **suggerimento**, se il movimento non ha categoria e ce n'è uno: Accetta / No, grazie.
5. Dopo un cambio di categoria: la **proposta** per i movimenti simili, poi la conferma con **Annulla**.
6. "Crea una regola" per i prossimi movimenti, ma **solo se ha senso**: se i movimenti dell'esercente stanno in più
   categorie (da Amazon si compra di tutto), l'app spiega perché una regola non conviene.
7. **Lo stesso esercente**: quanti movimenti nell'ultimo anno, quanto in tutto, gli ultimi cinque (si aprono), e
   "Tutti i movimenti di questo esercente" (la ricerca nell'elenco).
8. La **nota**.
9. **Dettagli**: conto, controparte, causale, date, da dove è arrivato; e "Mostra i dati originali", quello che è
   arrivato davvero dalla banca (o dal file), rientrato per leggerlo.

Sul **telefono** la scheda prende il posto dell'elenco; tornando indietro l'elenco è come lo si era lasciato, allo
stesso punto. Sul **PC** (da 1100 pixel) la scheda sta **accanto** all'elenco, e il movimento aperto è evidenziato.

---

## Parte B: il backend, il dettaglio, i simili e la correzione in blocco

Tre API nuove, in `TransactionController`, con la logica in `TransactionDetailService`.

### `GET /api/transactions/{id}`: il dettaglio

Risponde con `TransactionDetailDto`:

| Campo | Che cos'è |
|---|---|
| `transaction` | il movimento, come nell'elenco |
| `rawJson`, `importedAt` | i dati originali e quando sono arrivati |
| `origin` | `BANK`, `CSV` (un conto aggiunto a mano, `docs/25`), `TRADE_REPUBLIC_FILE` (impronta `trcsv:`, `docs/31`) |
| `categoryOrigin`, `ruleName` | `MANUAL` (scelta a mano, vince su tutto), `RULE` (con il nome della regola), `NONE` |
| `merchant` | l'esercente: quanti movimenti nell'ultimo anno, il totale, in quante categorie, gli ultimi cinque |
| `suggestion` | il suggerimento di categoria (dalla storia o dall'IA), se il movimento non ne ha una |

**L'esercente** si riconosce con la stessa chiave degli abbonamenti e dei suggerimenti (`MerchantKey`, `docs/22`):
il testo ridotto all'essenziale, senza date e numeri ("PAGAMENTO POS AMAZON.IT 24/10" → "PAGAMENTO POS AMAZON IT").
La chiave si calcola in Java, quindi i movimenti dell'anno si leggono con una query sola (con la categoria già
caricata: `LEFT JOIN FETCH`, niente query per ogni movimento) e si filtrano in memoria. Per un'app personale sono
poche migliaia di righe: pochi millisecondi.

### `GET /api/transactions/{id}/similar?categoryId=7`: i simili

Gli **altri** movimenti dello stesso esercente con la categoria 7 (senza `categoryId`: quelli senza categoria), in
tutta la storia, solo spese ed entrate: un giroconto allo stesso nome non è "un altro acquisto". La pagina lo chiede
**dopo** aver cambiato la categoria, con quella **vecchia**: "c'erano altri movimenti di Amazon in Shopping?".

### `PUT /api/transactions/manual-categories`: la correzione in blocco

```json
[{"id": 41, "categoryId": 8}, {"id": 40, "categoryId": 8}]
```

Sceglie a mano la categoria di più movimenti in una volta e **risponde con le scelte di prima** (Parte C). È "tutto
o niente":

- prima si controlla tutto: la lista non è vuota né più lunga di 500, ogni id compare una volta sola, ogni movimento
  esiste (altrimenti 404), ogni categoria esiste (altrimenti 400);
- solo dopo si cambia, e alla fine si riclassifica una volta sola (`classifyAll`).

Se il controllo fallisce a metà lista, niente è stato cambiato: il test `aWrongBlockChangesNothing` lo verifica
mandando un movimento giusto e uno che non esiste. Anche se qualcosa andasse storto dopo, `@Transactional` annulla
tutto: le modifiche arrivano al database solo alla fine del metodo.

---

## Parte C: l'Annulla, la risposta è la richiesta inversa

Come si annulla "ho spostato quattro movimenti in Multimedia"? Bisogna rimettere a ognuno la sua scelta di **prima**,
che può essere diversa per ognuno (uno era "Shopping" scelto a mano, un altro non aveva una scelta a mano).

Il trucco: la correzione in blocco risponde con le scelte di prima, **nello stesso formato** della richiesta.

```
PUT [{"id": 41, "categoryId": 8}, {"id": 40, "categoryId": 8}]
←   [{"id": 41, "categoryId": 1}, {"id": 40, "categoryId": null}]
```

Rimandare la risposta con un altro `PUT` è l'Annulla. La pagina tiene da parte quella lista (più la scelta di prima
del movimento aperto) finché si vede "Spostati 4 movimenti in «Multimedia ed elettronica» · **Annulla**".

Il backend non deve ricordare niente ("la storia delle modifiche"): è la pagina a tenere la richiesta inversa, finché
serve. Una cosa da sapere: l'Annulla rimette le **scelte a mano**; la categoria finale la ricalcola la
classificazione, come sempre.

---

## Parte D: l'elenco, giorni, pillole e "Mostra altri"

**I giorni** li fa una funzione pura, `groupByDay(items, today)` in `day-groups.ts`: scorre i movimenti (già in
ordine dal più recente) e ne apre un gruppo nuovo quando cambia il giorno. Il titolo lo scrive `dayLabel`: "Oggi",
"Ieri" (anche a cavallo di due mesi: il primo ottobre, "ieri" è il 30 settembre), poi giorno della settimana e data,
con l'anno solo se non è quello di oggi. `today` è un parametro, così i test scelgono il giorno.

Il totale si somma **in centesimi**: in virgola mobile `0.1 + 0.2` fa `0.30000000000000004`.

```ts
group.total = ((group.total ?? 0) * 100 + Math.round(item.amount * 100)) / 100;
```

**Le pillole** sono pulsanti e menu con la classe `pill`:

- "Da verificare" e "Senza categoria" sono **pulsanti a due stati**, con `aria-pressed`: un lettore di schermo dice
  "premuto" o "non premuto";
- Periodo, Conto e Tipo sono `<select>` vestiti da pillola (`appearance: none`, e la freccia disegnata con due
  sfumature nel colore del testo, `currentColor`, così cambia da sola con la pillola accesa);
- "Scegli le date" mostra i due calendari sotto le pillole.

**"Mostra altri"** chiede la pagina successiva (`page = movimenti caricati / 50`) e la **aggiunge** in fondo:
`items.set([...items(), ...nuovi])`. Ogni filtro nuovo riparte dalla prima pagina. Il numero sotto ("50 di 70
movimenti") dice quanti ne mancano.

L'indirizzo può portare un filtro di partenza: `?type=TO_REVIEW` (dal Riepilogo) e `?text=…`.

---

## Parte E: il dettaglio come pagina figlia, accanto o al posto dell'elenco

```ts
{
  path: 'movimenti',
  loadComponent: () => import('./transactions/transactions-page').then((m) => m.TransactionsPage),
  children: [
    { path: ':id', loadComponent: () => import('./transactions/transaction-detail').then((m) => m.TransactionDetail) },
  ],
}
```

`/movimenti/42` è una **rotta figlia**: il router mostra la scheda nel `<router-outlet>` che sta **dentro** la
pagina dell'elenco. La pagina dell'elenco non viene distrutta: tiene i filtri, i movimenti caricati, la posizione.
Il link di ogni riga è relativo (`[routerLink]="[t.id]"`) e conserva i filtri nell'indirizzo
(`queryParamsHandling="preserve"`).

`<router-outlet (activate)="onDetailOpen()" (deactivate)="onDetailClose()">`: il router avvisa quando una pagina
figlia si apre e si chiude. Lì la pagina:

- accende la classe `with-detail`, e il **CSS** decide: sul telefono l'elenco si nasconde (`display: none`, ma resta
  in vita), sul PC diventa una griglia di due colonne con la scheda ferma a destra (`position: sticky`);
- sul telefono ricorda dove era arrivato l'elenco (`window.scrollY`) e lo rimette quando si torna indietro.

**La scheda e l'elenco si parlano** con un piccolo servizio, `TransactionsChanged`: quando la scheda cambia qualcosa
chiama `notify()`, e l'elenco (in un `effect` che legge `version()`) ricarica i movimenti già caricati. "Tutti i
movimenti di questo esercente" chiama `searchFor(...)`: l'elenco scrive il testo nel campo e cerca. Un servizio con
dei signal è il modo più semplice di far parlare due componenti che non sono uno dentro l'altro nel template.

Passando da un movimento all'altro (`/movimenti/41` → `/movimenti/42`) Angular **riusa** la stessa scheda: per
questo la scheda ascolta `route.paramMap` (un Observable) invece di leggere l'id una volta sola.

---

## Parte F: i fogli di scelta con `<dialog>`

La scelta della categoria e del tipo è un **foglio**: sul telefono sale dal basso (a portata di pollice), sul PC sta
al centro. È l'elemento HTML `<dialog>`, che con `showModal()` il browser apre **sopra** la pagina e che fa da solo
le cose difficili:

- lo sfondo scuro (`::backdrop`, nel CSS);
- il tasto **Esc** lo chiude (e lancia l'evento `close`, che rimette a posto il signal);
- il **focus** resta dentro il foglio finché è aperto: con la tastiera non si finisce sulla pagina sotto;
- il resto della pagina non si può toccare.

Un clic sullo sfondo lo chiude: il clic arriva al `<dialog>` stesso (non al suo contenuto), e
`event.target === dialog` lo riconosce.

Il foglio della categoria ha una **ricerca** (scrivendo "stip" resta "Stipendio e pensione"), in cima le categorie
**già usate** per esercenti simili (`docs/26`), poi i gruppi con quello adatto al movimento per primo, e "Togli la
mia scelta" se c'è una scelta a mano. Il foglio del tipo dice per ogni tipo **che effetto ha sui totali**.

---

## Parte G: i test

**Backend** (`TransactionDetailTest`, database vero, dati inventati: quattro acquisti Amazon, una pizzeria, il
canone del conto, un movimento dal file di Trade Republic e uno da un CSV):

- il dettaglio ha tutto: la categoria e il perché, l'origine, i dati originali, l'esercente (quattro movimenti
  nell'ultimo anno, in due categorie, gli altri dal più recente);
- una categoria da regola ha il nome della regola, un movimento senza categoria dice `NONE`;
- un movimento senza categoria mostra il suggerimento della sua pizzeria;
- i movimenti dai file dicono da dove vengono; un id che non esiste è un 404;
- i simili sono gli altri dell'esercente con la vecchia categoria, anche più vecchi di un anno;
- due movimenti cambiano insieme, e rimandare la risposta li rimette com'erano;
- una richiesta sbagliata (vuota, doppia, con un movimento o una categoria che non esistono) non cambia niente.

**Frontend**

- `day-groups.spec.ts`: Oggi, Ieri (anche a cavallo di due mesi), gli altri giorni e gli altri anni; il totale solo
  di spese ed entrate, in centesimi; il nome di un movimento.
- `transactions-page.spec.ts`: i giorni con il totale; ogni riga con nome, categoria o tipo, conto, nota, importo; le
  pillole accese e spente; il periodo e le date (anche quelle sbagliate); la ricerca dopo 300 ms; conta solo l'ultima
  risposta; "nessun movimento"; "Mostra altri"; i filtri dall'indirizzo; l'elenco che si ricarica quando la scheda
  cambia qualcosa, e la ricerca chiesta dalla scheda.
- `transaction-detail.spec.ts`: la scheda con il perché della categoria, l'esercente, i dettagli, il link alla regola
  (o la spiegazione se l'esercente ha più categorie); il cambio di categoria con la proposta, "Sposta anche quelli" e
  l'Annulla di tutti e tre; "Solo questo"; la ricerca nel foglio; "Conta come" e "Automatico"; il suggerimento; la
  nota; i dati originali rientrati; l'elenco avvisato; il movimento che non esiste.
- `suggestions-panel.spec.ts`: i suggerimenti sono ora un elenco, non una tabella (sul telefono la tabella era stretta
  e illeggibile).

---

## Parte H: la prova nel browser

Con Playwright, quattro mesi di movimenti inventati:

- **telefono**: 44 giorni nella prima pagina, nessuna pagina che sborda; "Oggi · domenica 25 ottobre" con il suo
  totale; aperto un acquisto Amazon, l'elenco sparisce e la scheda parte dall'alto; il foglio della categoria si apre
  come `<dialog>`; scelta "Multimedia ed elettronica", la proposta dice "Ci sono altri 3 movimenti di questo
  esercente in «Shopping»"; "Sposta anche quelli" li sposta tutti e quattro (verificato con l'API) e **Annulla** li
  rimette in Shopping; tornando all'elenco si è allo stesso punto (900 pixel più giù, come prima);
- il **suggerimento** della pizzeria si accetta dalla scheda;
- **notte**: elenco e scheda con i dati originali;
- **PC**: elenco e scheda affiancati, il movimento aperto evidenziato, il foglio "Conta come" al centro.

La prima prova ha mostrato il pannello dei suggerimenti schiacciato sul telefono (era una tabella di tre colonne):
ora è un elenco, con i pulsanti sotto.

---

## Parte I: esercizi guidati

1. **Il movimento successivo.** Nella scheda, aggiungi le frecce "precedente / successivo" che aprono il movimento
   sopra e sotto nell'elenco. Dove prendi l'elenco? (Suggerimento: `TransactionsChanged` può tenere gli id caricati.)
2. **Annulla con un tempo.** Fai sparire la conferma "Spostati 4 movimenti · Annulla" dopo dieci secondi. Che cosa
   succede se l'utente cambia movimento prima?
3. **Più veloce sui simili.** `similar` legge tutta la storia per calcolare le chiavi. Salva la chiave dell'esercente
   in una colonna (una migrazione e un indice): che cosa cambia nella query? Che cosa devi ricordarti quando cambia
   `MerchantKey` (vedi la nota nel suo commento)?
