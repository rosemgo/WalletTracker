# 31. Fase 3: completare i movimenti di Trade Republic con il suo file

**Obiettivo:** Trade Republic è collegato con Enable Banking, ma i suoi movimenti arrivano **senza causale né
controparte**: solo data e importo. Senza testo, regole, suggerimenti e IA non hanno niente su cui lavorare, e
tutto resta "Da verificare". Trade Republic però dà un file, l'**esportazione delle transazioni** in CSV, che ha
tutto. In questo passo lo carichiamo nel conto **collegato**, per **completare** i movimenti già arrivati e per
**aggiungere** quelli più vecchi dello storico che la banca ha dato (Parte L). Quelli più recenti no: li porta la
sincronizzazione, e aggiungerli creerebbe doppioni.

È l'ultima voce della Fase 3 (`docs/25`, "E Trade Republic?").

| Parte | Cosa |
|---|---|
| A | il problema, e perché il CSV e non il PDF |
| B | il file di Trade Republic: colonne, importo lordo e netto, righe senza denaro |
| C | l'abbinamento: quale riga va con quale movimento |
| D | che cosa si scrive, e che cosa non si tocca mai |
| E | le regole di partenza per le causali di Trade Republic (V25) |
| F | il backend: `TradeRepublicCsv`, `CompletionService`, le API |
| G | la pagina "Completa i movimenti" |
| H | i test, e la prova nel browser |
| I | provalo tu |
| J | sicurezza e dati personali |
| K | esercizi guidati, con le soluzioni |
| L | aggiornamento: gli acquisti di titoli, i mesi prima dello storico della banca, le regole V26 |

> Prerequisiti: `docs/25` (l'import CSV), `docs/03` (classificazione e regole), e `git pull`. Al primo avvio
> Flyway applica la **V25** e la **V26**.

---

## Parte A: il problema, e perché il CSV

### A1. Che cosa manda Trade Republic

Con Enable Banking (PSD2) Trade Republic manda per ogni movimento la data, l'importo e la direzione. Niente
causale, niente controparte (`docs/03`, parte 5: è un limite noto della sua interfaccia PSD2). Nella pagina
Movimenti si vede "(nessuna descrizione)" e il tipo "Da verificare". Correggerli a mano uno per uno si può, ma è
proprio il lavoro che l'app dovrebbe risparmiare.

### A2. PDF o CSV

Trade Republic dà due documenti:

- l'**estratto conto in PDF**: bello da leggere, scomodo per un programma. Bisogna ricostruire la tabella dalle
  posizioni del testo, le descrizioni lunghe vanno a capo, le date si spezzano su due righe ("01 ago" / "2026"), e
  se un giorno cambia l'impaginazione la lettura si rompe senza avvisare;
- l'**esportazione delle transazioni in CSV** (da aprile 2026): colonne fisse con un nome, una riga per
  operazione, numeri con il punto. È fatto apposta per i programmi: lo importano già Portfolio Performance,
  getquin, CoinTracking.

Abbiamo scelto il CSV. Il PDF è servito a una cosa: **controllare** che i numeri che ricaviamo dal CSV siano quelli
dell'estratto conto (Parte B2).

---

## Parte B: il file di Trade Republic

### B1. Le colonne

La prima riga del file (intestazione):

```
"datetime","date","account_type","category","type","asset_class","name","symbol","shares","price","amount","fee",
"tax","currency","original_amount","original_currency","fx_rate","description","transaction_id",
"counterparty_name","counterparty_iban","payment_reference","mcc_code"
```

Quelle che usiamo:

| Colonna | Che cosa contiene | Esempio |
|---|---|---|
| `date` | la data dell'operazione | `2026-07-07` |
| `category` | `CASH` se muove denaro; `DELIVERY`, `CORPORATE_ACTION` per i titoli | `CASH` |
| `type` | il tipo di Trade Republic | `INTEREST_PAYMENT`, `DIVIDEND`, `TRANSFER_INSTANT_OUTBOUND`, `TAX_OPTIMIZATION` |
| `name` | il titolo, per dividendi e operazioni sui titoli | `Alfa SpA` |
| `amount`, `fee`, `tax` | importo, commissioni, tasse | `10.000000`, vuoto, `-2.60` |
| `currency` | la valuta | `EUR` |
| `description` | la causale | `Interest payment for payout collection ...` |
| `counterparty_name` | chi riceve o manda un bonifico | `Mario Rossi` |

Il file si riconosce dalle colonne: se le ha tutte (`TradeRepublicCsv.COLUMNS`), è un'esportazione di Trade
Republic. Non serve la mappatura scelta dall'utente come in `docs/25`: le colonne hanno sempre lo stesso nome.

### B2. La sorpresa: l'importo è lordo

Una riga di interessi (dati inventati, stessa forma di quelli veri):

```
"2026-07-01T07:00:00Z","2026-07-01","DEFAULT","CASH","INTEREST_PAYMENT","","","","","","10.000000","","-2.60","EUR",...
```

`amount` è **10,00**, ma sul conto arrivano **7,40**: 10,00 di interessi meno 2,60 di tasse. Lo stesso per i
dividendi (200,00 lordi, 52,00 di tasse, 148,00 sul conto), e l'imposta di bollo ha `amount` **0** e tutto nella
colonna `tax` (−34,20). Quindi il movimento è:

```
importo sul conto = amount + fee + tax
```

Come lo sappiamo? Confrontando il tuo file con l'estratto conto PDF dello stesso periodo: con la somma, **tutti**
i 18 importi coincidono con quelli del PDF (interessi, dividendi, bolli, bonifici). Con il solo `amount` non ne
coinciderebbe nessuno tra interessi e dividendi. Gli importi di questa guida e dei test sono inventati.
È il motivo per cui i dati vanno sempre controllati su un caso vero, non solo immaginati.

### B3. Le righe senza denaro

Il file contiene anche operazioni sui **titoli** senza denaro: azioni trasferite (`FREE_RECEIPT`), assegnazioni
(`INTERMEDIATE_SECURITIES_DISTRIBUTION`). Hanno importo, commissione e tassa **vuoti**: sul conto non si muove
niente, quindi le saltiamo e lo diciamo nell'anteprima ("4 operazioni sui titoli, che non muovono denaro e
vengono saltate"). Saltiamo anche le righe con importo netto zero. Attenzione: gli **acquisti** di titoli hanno
anch'essi una categoria diversa da `CASH` (`TRADING`), ma il denaro lo muovono; per questo le righe si riconoscono
dall'importo, non dalla categoria (Parte L1).

### B4. La controparte

- bonifico: `counterparty_name` ("Mario Rossi");
- dividendo e acquisto: nessuna controparte nel file, ma `name` dice il titolo ("Alfa SpA");
- interessi e bollo: nessuna (resta vuota).

---

## Parte C: l'abbinamento

### C1. Le regole

Per ogni riga del file cerchiamo il suo movimento tra quelli del conto (`CompletionService.match`):

1. **stesso importo, con il segno**, e stessa valuta;
2. **data vicina**: al massimo 3 giorni di differenza (`DAYS_TOLERANCE`);
3. ogni movimento si usa **una volta sola**;
4. tra più movimenti possibili vince quello con la **data più vicina**; a parità, il primo arrivato (id più
   piccolo);
5. le righe si abbinano in **ordine di data**.

### C2. Perché la tolleranza sulla data

Le due date non sono sempre la stessa. Un bonifico può avere `datetime` `2026-09-18T23:40:00Z` e `date`
`2026-09-19`: alle 23:40 UTC in Italia è già il 19. La banca, via Enable Banking, può usare il 18, o la data di
contabilizzazione, che può essere il giorno lavorativo dopo. Tre giorni coprono un fine settimana.

### C3. Perché il segno conta

Trade Republic può **annullare** un dividendo (−148,00) e **ripagarlo** (+148,00) lo stesso giorno: è successo davvero.
Stessa data, stesso valore assoluto: solo il segno li distingue, e ogni riga trova il suo movimento. È uno dei
test (`CompletionMatchingTest.theSignTellsACancellationFromAPayment`).

### C4. Che cosa resta fuori

L'anteprima mostra anche quello che **non** si abbina, e perché può succedere:

- **righe del file senza movimento** che non si aggiungono: più recenti dell'ultimo aggiornamento del conto (si
  aggiorna e si riprova), oppure nel periodo della banca ma senza abbinamento (da controllare). Quelle più vecchie
  dello storico della banca invece si aggiungono (Parte L2);
- **movimenti del periodo che nel file non ci sono**: restano come sono.

Perché un algoritmo "goloso" (greedy: ogni riga prende il migliore libero) e non uno che cerca l'abbinamento
ottimo tra tutte le combinazioni? Perché qui i casi difficili sono rari (due importi uguali a pochi giorni di
distanza), e in quei casi l'ordine di data dà già la risposta giusta. Più semplice da leggere, e l'anteprima fa
vedere ogni abbinamento prima di salvare.

---

## Parte D: che cosa si scrive, e che cosa non si tocca mai

- Si riempiono solo **controparte e causale**, e solo se sono **vuote**: quello che la banca ha mandato non si
  sovrascrive mai (`BankTransaction.completeDetails`, provato da `whatTheBankSentIsNeverOverwritten`);
- si **aggiungono** solo le righe **più vecchie** del primo movimento della banca (Parte L2); quelle recenti le
  porta la sincronizzazione;
- **non cambia l'impronta** (`dedup_key`, `docs/06`): la prossima sincronizzazione riconosce gli stessi movimenti
  di prima e non li duplica;
- solo i movimenti **contabilizzati**: quelli "in attesa" vengono cancellati e riscaricati a ogni aggiornamento
  (`docs/06`), e il testo aggiunto andrebbe perso;
- **tutto o niente**: se una riga del file non si legge, non cambia niente e l'errore dice quale riga;
- **ricaricare lo stesso file** non fa danni: i movimenti già completati, e le righe già aggiunte, vengono contati
  come "già fatte";
- dopo il completamento i movimenti vengono **classificati di nuovo**: le regole, le correzioni a mano (che vincono
  sempre) e gli abbinamenti dei trasferimenti tra conti propri ripartono con il testo.

Il conto deve essere **collegato**: per un conto manuale (Trade Republic aggiunto a mano, `docs/25`) i movimenti
si caricano con "Importa CSV".

---

## Parte E: le regole di partenza per Trade Republic (V25)

Con la causale arrivano testi in inglese che le regole di partenza (V4, scritte per Fineco e ING) non conoscono.
La **V25** ne aggiunge quattro:

| Regola | Testo (inizia con) | Segno | Tipo | Categoria |
|---|---|---|---|---|
| Interessi (Trade Republic) | `Interest payment` | entrata | proventi da investimenti | Interessi |
| Dividendo (Trade Republic) | `Cash Dividend` | entrata | proventi da investimenti | Dividendi |
| Dividendo annullato (Trade Republic) | `Cancellation Cash Dividend` | uscita | proventi da investimenti | Dividendi |
| Imposta di bollo (Trade Republic) | `Stamp Duty Tax` | qualsiasi | tasse sugli investimenti | Imposta di bollo |

Due dettagli:

- "Cash Dividend" con **inizia con**, non "contiene": così non prende "Cancellation Cash Dividend", che ha la sua
  regola. Il dividendo annullato resta un provento (negativo): annullamento e nuovo pagamento si compensano nella
  stessa categoria;
- la categoria si cerca **per nome** con un `LEFT JOIN`: se l'utente l'ha rinominata o eliminata, la regola c'è lo
  stesso, senza categoria, e decide solo il tipo.

```sql
INSERT INTO classification_rule (name, priority, match_mode, pattern, amount_sign, result_type, category_id, builtin)
SELECT r.name, r.priority, r.match_mode, r.pattern, r.amount_sign, r.result_type, c.id, TRUE
FROM (VALUES ('Interessi (Trade Republic)', 10, 'STARTS_WITH', 'Interest payment', 'POSITIVE', 'INVESTMENT_INCOME', 'Interessi'),
             ...) AS r (name, priority, match_mode, pattern, amount_sign, result_type, category)
LEFT JOIN category c ON lower(c.name) = lower(r.category);
```

I bonifici ("Outgoing transfer for ...", "Incoming transfer from ...") non hanno una regola: se l'IBAN nella
causale è quello di un tuo conto, la classificazione li riconosce già come trasferimenti tra conti propri;
altrimenti li categorizzi tu, o li suggeriscono la storia e l'IA.

---

## Parte F: il backend

### F1. `TradeRepublicCsv`: leggere il file

Funzioni pure (nessun database), nel package `csvimport` accanto all'import di `docs/25`:

- `recognizes(headers)`: il file ha tutte le colonne che usiamo?
- `read(headers, rows)`: per ogni riga `CASH` calcola l'importo netto (`amount + fee + tax`, a 2 decimali), la
  controparte (B4), e restituisce le righe, quante ne ha saltate e gli errori ("Riga 5: amount \"nove\" non è un
  numero.").

Le celle si leggono **per nome di colonna**, non per posizione: se un giorno Trade Republic aggiunge una colonna,
la lettura continua a funzionare. Il file passa da `TextDecoder` e `CsvReader`, gli stessi dell'import (UTF-8 o
Windows-1252, virgolette, separatore riconosciuto da solo).

### F2. `CompletionService`: anteprima e completamento

Come `CsvImportService`, in due tempi, con lo stesso "piano" (`plan`) calcolato due volte:

1. legge il file, controlla che sia di Trade Republic e che il conto sia collegato;
2. carica i movimenti contabilizzati del conto nel periodo del file (più la tolleranza), **in una query**
   (`findByAccountAndStatusAndBookingDateBetweenOrderByBookingDateAscIdAsc`);
3. li abbina (`match`, Parte C);
4. `preview` descrive che cosa cambierebbe; `complete` lo fa, in una transazione, e poi chiama
   `classificationService.classifyAll()`.

Perché ricalcolare il piano nella seconda richiesta, invece di ricordarlo? Perché il server non conserva il file
(come in `docs/25`): il browser lo rimanda. E così, se nel frattempo è arrivato un aggiornamento, il completamento
lavora sui dati di adesso.

`match` è una funzione **statica** che lavora su record semplici (`Candidate`: id, data, importo, valuta), non su
entità JPA: così si prova con dei test veloci, senza database.

### F3. Le API

| Metodo e percorso | Corpo | Risposta |
|---|---|---|
| `POST /api/imports/completion/preview?accountId=5` | il file (`multipart`, pezzo `file`) | `CompletionPreview`: periodo, righe, movimenti da completare con i testi, già completi, righe senza movimento, movimenti senza riga |
| `POST /api/imports/completion?accountId=5` | il file | `CompletionResult`: completati, aggiunti, già fatti |

Errori: 400 se il file non è di Trade Republic o il conto è manuale; 400 con l'elenco `errors` se una riga non si
legge (lo stesso `ImportRejectedException` dell'import); 404 se il conto non esiste.

---

## Parte G: la pagina "Completa i movimenti"

- indirizzo `/importa/completa`; ci si arriva dalla pagina **Conti** (accanto a un conto Trade Republic collegato,
  "completa con il file") e da **Importa CSV**, che ora dice: "Hai Trade Republic collegato? Allora non serve
  importare";
- il menu mostra solo i conti **collegati**, e sceglie da solo quello di Trade Republic;
- scelto il file, l'anteprima arriva subito: il periodo, quanti movimenti verranno completati (con la tabella dei
  testi), quanti lo erano già, che cosa resta fuori;
- "Completa N movimenti" salva; il riepilogo finale porta alla pagina Movimenti;
- una sezione richiudibile spiega come esportare il file dall'app di Trade Republic.

I testi contano i singolari e i plurali ("1 operazione sui titoli, che non muove denaro e viene saltata"; "18
movimenti di denaro") con un piccolo metodo `count(n, uno, molti)`.

---

## Parte H: i test, e la prova nel browser

| Test | Che cosa prova |
|---|---|
| `TradeRepublicCsvTest` | riconoscere il file; importo netto (interessi, bollo, dividendo e annullamento); righe dei titoli saltate; controparte; errori con il numero di riga |
| `CompletionMatchingTest` | data vicina (il bonifico delle 23:40 UTC); il segno (annullamento e pagamento); la data più vicina e ogni movimento una volta; importi diversi o date lontane non si abbinano |
| `CompletionControllerTest` | l'anteprima non cambia niente; il completamento riempie i testi e le regole V25 classificano (Interessi, Imposta di bollo); ricaricare lo stesso file non cambia niente; quello che la banca ha mandato non si sovrascrive; file sbagliato, conto manuale, riga sbagliata: 400 e nessuna modifica |
| `completion-page.spec.ts` | solo conti collegati, Trade Republic scelto da solo, anteprima, completamento, errore |

I dati dei test sono **inventati** ("Mario Rossi", IBAN `IT00X...`), con la stessa forma di quelli veri.

**Con il file vero**, in locale e senza salvarlo da nessuna parte: si legge senza errori, 18 movimenti di denaro e
4 operazioni sui titoli saltate, importi netti uguali all'estratto conto PDF (Parte B2).

**Nel browser**: un database vuoto, un conto Trade Republic "collegato" con sei movimenti senza testo, il file di
prova. L'anteprima mostra 5 movimenti da completare e 1 del periodo che nel file non c'è; dopo "Completa", la
pagina Movimenti mostra "Interest payment..." come Interessi, "Stamp Duty Tax" come Imposta di bollo, i due
dividendi Alfa SpA come Dividendi, il bonifico con la controparte, e il movimento che mancava nel file ancora "Da
verificare". Provata anche con la larghezza di un telefono.

---

## Parte I: provalo tu

1. `git pull` e riavvia il backend (Flyway applica la V25) e la dashboard.
2. Pagina **Conti** → "Aggiorna ora", così il conto Trade Republic ha i movimenti più recenti.
3. Nell'app di Trade Republic (o su app.traderepublic.com): profilo → documenti → **Esportazione transazioni**,
   formato CSV. Non aprirlo e salvarlo con Excel: cambierebbe i formati.
4. Pagina **Conti** → accanto a Trade Republic, **completa con il file** (oppure Importa CSV → "Completa i
   movimenti").
5. Scegli il file e guarda l'anteprima: i movimenti da completare, con controparte e causale. Se ci sono "righe
   senza movimento" recenti, aggiorna il conto e riprova.
6. **Completa**, poi vai in **Movimenti**, filtra per Trade Republic: interessi, dividendi e bolli dovrebbero già
   avere tipo e categoria; i bonifici, se non sono tra conti tuoi, li categorizzi tu (o con i suggerimenti).
7. Il mese prossimo: un file nuovo, anche sovrapposto al precedente. I movimenti già completati vengono saltati.

Test:

```
cd backend;  .\mvnw.cmd test
cd frontend; npm test -- --watch=false
```

---

## Parte J: sicurezza e dati personali

| Rischio | Difesa |
|---|---|
| file enormi | gli stessi limiti dell'import: 5 MB, 50.000 righe (`docs/17`, riga 14) |
| il file conservato sul server | mai: viene letto in memoria e scartato; il browser lo rimanda alla conferma |
| dati della banca sovrascritti | si riempiono solo i campi vuoti; mai l'importo, la data, l'impronta |
| movimenti a metà | tutto o niente, in una transazione |
| il file sbagliato | riconosciuto dalle colonne: un CSV qualsiasi viene rifiutato con un messaggio che dice che cosa serve |
| dati veri nel repository | i test usano dati inventati; il file vero è servito solo a controllare, in locale |

Una nota sul file: contiene nomi, IBAN e operazioni. Non mandarlo per email e non lasciarlo nella cartella
Download: dopo averlo caricato puoi cancellarlo, i testi sono già nel database.

---

## Parte K: esercizi guidati

### Esercizio 1: Trade Republic come conto manuale

Chi ha Trade Republic come conto **manuale** (`docs/25`) e importa questo file con "Importa CSV" ottiene importi
**lordi** (la colonna `amount`) e anche le righe dei titoli. Come useresti `TradeRepublicCsv` per importarlo
correttamente?

<details>
<summary>Soluzione</summary>

In `CsvImportService.preview` e `importFile`, prima della mappatura: se `TradeRepublicCsv.recognizes(headers)`, si
leggono le righe con `TradeRepublicCsv.read` invece che con `RowReader`, e si salvano come nuovi movimenti
(`BankTransaction` con data, importo netto, valuta, controparte, causale), con la stessa impronta `csv:` per non
importarli due volte. L'anteprima direbbe "File di Trade Republic riconosciuto: nessuna mappatura da scegliere".
Così un formato noto si legge da solo, e la mappatura resta per i file sconosciuti.
</details>

### Esercizio 2: il tipo di Trade Republic nelle regole

Il file ha una colonna `type` (`INTEREST_PAYMENT`, `DIVIDEND`...) più affidabile della causale in inglese, che
Trade Republic potrebbe cambiare. Perché non la usiamo nelle regole, e come si potrebbe?

<details>
<summary>Soluzione</summary>

Le regole guardano il testo del movimento (causale e controparte, `ClassificationService.textOf`), uguale per tutte
le banche: `type` non è salvato da nessuna parte. Per usarlo: una colonna nuova `bank_type` in `bank_transaction`
(migrazione), riempita da `completeDetails`, e un nuovo `match_mode` delle regole (es. `BANK_TYPE`) che la
confronta. È più robusto, ma è un concetto nuovo nel motore delle regole, valido per una banca sola: per ora la
causale basta, e se Trade Republic la cambiasse lo vedremmo subito (i movimenti tornerebbero "Da verificare").
</details>

### Esercizio 3: un abbinamento ambiguo

Due bonifici da 50 € lo stesso giorno, a due persone diverse; via Enable Banking arrivano due movimenti da −50 €
senza testo. Quale riga va con quale movimento? Ha importanza?

<details>
<summary>Soluzione</summary>

Non si può sapere: data e importo sono uguali, e il resto manca proprio. L'abbinamento segue l'ordine (righe per
data e numero di riga, movimenti per id) e può scambiarli. Conta poco: i due movimenti sono identici per l'app
(stessa data, stesso importo, stesso conto), quindi scambiare i testi dà lo stesso risultato finale, due movimenti
da −50 € con le due causali. Si potrebbe segnalare il caso nell'anteprima ("2 righe uguali: abbinate in ordine")
contando, per ogni riga, quanti candidati avevano la stessa distanza.
</details>

---

## Parte L: aggiornamento, un file da gennaio

Caricando un file più lungo, dal 1° gennaio, sono venute fuori tre cose.

### L1. Gli acquisti di titoli venivano saltati

La prima versione leggeva solo le righe con `category` `CASH`, perché nel primo file le altre erano solo
operazioni sui titoli senza denaro. Il file da gennaio ha anche gli **acquisti** (forma vera, dati inventati):

```
"2026-05-04T10:00:00Z","2026-05-04","DEFAULT","TRADING","BUY","STOCK","Beta SpA",...,"-1000.00","-1.00","-2.00","EUR",...,"Buy trade IT0000000002 BETA SPA, quantity: 100",...
```

`category` è `TRADING`, ma sul conto escono 1.003,00 € (prezzo, commissione, tassa sulle transazioni): tra i
movimenti più grandi del conto, e venivano scartati. Il criterio giusto non è la categoria ma **se la riga ha un
importo**: le operazioni senza denaro hanno `amount`, `fee` e `tax` vuoti. Una lezione: un esempio solo (il primo
file) non basta a capire un formato; i casi che non ci sono nell'esempio sono proprio quelli che sfuggono.

Sul file vero da gennaio: 81 movimenti di denaro (prima 72: i 9 acquisti mancavano), 4 righe saltate, nessun
errore.

### L2. I mesi prima dello storico della banca

Enable Banking, per Trade Republic, ha dato pochi mesi di storico; il file parte da gennaio. Che cosa fare delle
righe senza movimento? Dipende da **dove** sono:

| Righe senza movimento | Perché | Che cosa facciamo |
|---|---|---|
| più vecchie del primo movimento della banca | la banca non ha mai dato quei mesi | **si aggiungono**: senza, mancherebbero da riepilogo, budget e grafici |
| più recenti dell'ultimo aggiornamento | la sincronizzazione non le ha ancora portate | **no**: le porterà lei, e con un'impronta diversa sarebbero doppioni |
| nel periodo della banca | importo o data diversi, o un movimento che la banca non ha dato | **no, si mostrano**: è un'anomalia da guardare, non da aggiustare in silenzio |

"Il primo movimento della banca" è `findFirstBankDate`: il giorno più vecchio tra i movimenti contabilizzati del
conto, **esclusi** quelli aggiunti da un file. Si aggiungono le righe di almeno 3 giorni prima (la tolleranza
della Parte C2): una riga di due giorni prima potrebbe essere il primo movimento della banca con la data spostata.
Se il conto non ha ancora movimenti dalla banca non si aggiunge niente: non sapremmo dove inizia il suo storico.

I movimenti aggiunti:

- hanno l'impronta **`trcsv:` + `transaction_id`**, l'identificativo che Trade Republic dà a ogni operazione:
  ricaricando lo stesso file, o un file che si sovrappone, la riga si riconosce subito e non si aggiunge due
  volte;
- hanno come `raw_json` la riga del file, colonna per colonna (come l'import dei conti manuali): si vede sempre da
  dove arrivano;
- vengono classificati come gli altri. I bonifici da e verso altri tuoi conti collegati, se nella causale c'è il
  loro IBAN, diventano trasferimenti tra conti propri: non contano né come spese né come entrate.

### L3. E se un giorno la banca desse quei mesi?

Se ricolleghi Trade Republic, subito dopo il login la banca potrebbe dare uno storico più lungo, e riportare
movimenti che hai già aggiunto dal file: sarebbero doppi. Per questo l'importazione da Enable Banking ha una
**regola 5** (`TransactionImportService.fileMovementFor`): prima di salvare un movimento contabilizzato nuovo,
cerca nel conto un movimento **aggiunto da un file** con stesso importo e valuta e data entro 3 giorni. Se c'è, è
lo stesso: non lo salva, e il movimento del file **prende l'impronta della banca** (`adoptBankKey`), tenendo testi,
categoria e correzioni. Dalla volta dopo lo riconosce dall'impronta, come ogni altro movimento. È provato da
`TransactionImportServiceTest.aMovementAlreadyTakenFromTheFileIsNotSavedTwice`: lo stesso movimento con un giorno
di differenza non si duplica, uno con lo stesso importo un mese dopo sì.

### L4. Le regole V26

Il file da gennaio ha causali nuove. La V25 è già applicata e non si modifica: le regole arrivano con la **V26**.

| Regola | Testo (inizia con) | Segno | Tipo | Categoria |
|---|---|---|---|---|
| Acquisto titoli (Trade Republic) | `Buy trade` | uscita | acquisto titoli | — |
| Vendita titoli (Trade Republic) | `Sell trade` | entrata | vendita titoli | — |
| Distribuzione di capitale (Trade Republic) | `Capital Distribution` | qualsiasi | proventi da investimenti | Dividendi |
| Imposta di bollo annullata (Trade Republic) | `Cancellation Stamp Duty Tax` | qualsiasi | tasse sugli investimenti | Imposta di bollo |

"Sell trade" non c'è nel tuo file (non hai venduto niente): la forma è quella degli acquisti, e se fosse diversa
lo vedremmo dai movimenti "Da verificare".

### L5. Nella pagina

L'anteprima ha una sezione in più, **Movimenti da aggiungere**, con la stessa tabella di quelli da completare, e
dice da che giorno parte lo storico della banca. Il pulsante dice tutte e due le cose: "Completa 18 movimenti e
aggiungine 63". Ricaricando lo stesso file: "Non c'è niente da fare".

### L6. Test

| Test | Che cosa prova |
|---|---|
| `TradeRepublicCsvTest.buyingSecuritiesMovesMoneyToo` | gli acquisti si leggono, con commissione e tassa nel netto |
| `CompletionControllerTest.rowsOlderThanTheBankHistoryBecomeMovements` | le righe più vecchie si aggiungono (impronta, raw_json, regole V26), quella a due giorni dallo storico no; ricaricando niente di doppio |
| `CompletionControllerTest.withoutMovementsFromTheBankNothingIsAdded` | senza movimenti della banca non si aggiunge niente |
| `TransactionImportServiceTest.aMovementAlreadyTakenFromTheFileIsNotSavedTwice` | la regola 5 |
| `completion-page.spec.ts` | la sezione dei movimenti da aggiungere, il pulsante, il messaggio finale |

Nel browser: un conto con i movimenti della banca dal 1° luglio e il file di prova da maggio. Anteprima con 5
movimenti da completare e 3 da aggiungere (acquisto, distribuzione di capitale, bonifico), la riga del 29 giugno
mostrata e non aggiunta; dopo "Completa", l'acquisto è "Acquisto titoli" e la distribuzione "Dividendi";
ricaricando, "8 righe erano già fatte".
