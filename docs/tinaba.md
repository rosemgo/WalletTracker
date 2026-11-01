# Tinaba: un CSV senza segno

Fuori dalla numerazione, come `docs/investimenti.md`: scritta in parallelo alla Fase 4.

| Parte | Cosa |
|---|---|
| A | il problema: importi senza segno |
| B | il modo "senza segno" dell'importazione |
| C | il file di Tinaba si riconosce da solo |
| D | i tipi giusti: le regole di `V31` |
| E | perché il CSV e non i PDF mensili |
| F | i test, e la prova nel browser |
| G | come usarlo |
| H | due correzioni: la causale intera, e i giroconti scelti a mano |
| I | le regole di Tinaba cercano tutte le parole (`V32`) |
| J | il cashback è un'entrata, non un rimborso (`V33`); a che cosa servono le categorie |

---

## Parte A: il problema

Il CSV di Tinaba ha queste colonne:

```
Data,Categoria,Controparte,Descrizione,Causale/Messaggio,CRO/NOP,Importo,Community
```

e nella colonna `Importo` **tutti i numeri sono positivi**. Un esempio inventato:

```
28/10/2026 01:32:31,Bonifici,Mario Rossi,Ricevuto da Mario Rossi,Trasferimento a mio conto,ABC001,200,no
02/10/2026 17:04:33,Robo advisor,,Nuovo versamento Robo advisor,,,200,no
```

Il primo è un bonifico **ricevuto**, il secondo un versamento **uscito** verso il Robo advisor: stessa cifra, stesso
segno. Importato così, tutto diventava un'entrata e il saldo saliva sempre.

Fino a oggi l'importazione conosceva due modi per l'importo (`docs/25`):

- **SINGLE**: una colonna con il segno (`-12,50`);
- **SPLIT**: due colonne, "Dare" e "Avere".

Nessuno dei due va bene qui.

---

## Parte B: il modo "senza segno"

Il verso del movimento, nel file, c'è: è scritto nel **testo**. Le entrate dicono "Ricevuto da …" o "Trasferimento
ricevuto …", le uscite no. Allora un terzo modo, **UNSIGNED**:

- si prende l'importo e se ne toglie un eventuale segno (`abs()`);
- si guarda una colonna di testo scelta (`signColumn`);
- se contiene una delle **parole delle entrate** (`incomeWords`, separate da virgole), è un'entrata; altrimenti
  un'uscita.

In `ImportMapping`:

```java
public enum AmountMode { SINGLE, SPLIT, UNSIGNED }

@Min(0) Integer signColumn,
@Size(max = 300) String incomeWords

public List<String> incomeWordList() {
    // "Ricevuto, RICEVUTA , " -> ["ricevuto", "ricevuta"]
}
```

e in `RowReader`, dove si sceglie l'importo con uno `switch` sul modo:

```java
private static BigDecimal unsignedAmount(List<String> row, ImportMapping mapping) {
    BigDecimal amount = required(parseAmount(cell(row, mapping.amountColumn()), mapping.decimalSeparator()),
            "l'importo").abs();
    String text = cell(row, mapping.signColumn());
    String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
    boolean income = mapping.incomeWordList().stream().anyMatch(lower::contains);
    return income ? amount : amount.negate();
}
```

**Perché "le entrate" e non "le uscite".** Bisogna scegliere quale dei due versi riconoscere dalle parole; l'altro è
"tutto il resto". Le entrate di un conto come Tinaba sono poche e scritte sempre allo stesso modo ("ricevuto"), le
uscite sono di tanti tipi (versamenti, ricariche, pagamenti). Riconoscere le poche è più sicuro: una riga nuova e mai
vista diventa un'uscita, e un'uscita in più si nota subito nel saldo.

**Il confronto non guarda maiuscole e minuscole** (`Locale.ROOT`, come nel resto del codice: con la lingua turca
`"I".toLowerCase()` non è `"i"`).

**Il controllo prima di importare** (`CsvImportService.checkColumns`): in UNSIGNED servono la colonna dell'importo,
quella del testo e almeno una parola. Altrimenti:

- "Indica la colonna dell'importo e quella in cui cercare le parole delle entrate."
- "Scrivi almeno una parola delle entrate (per esempio "ricevuto")."

**Nella pagina Importa** c'è la terza voce "una colonna senza segno: il verso si capisce dal testo". Scelta, compaiono:

- "Cerca le parole delle entrate nella colonna" (un menu con le colonne del file);
- "Parole delle entrate, separate da virgole";
- la spiegazione: "Le righe che ne contengono una sono entrate; tutte le altre, uscite."

L'anteprima si aggiorna come per gli altri modi: si vedono subito i segni.

Il modo non è legato a Tinaba: va bene per qualunque banca che esporti importi senza segno.

---

## Parte C: il file di Tinaba si riconosce da solo

`ColumnGuesser` propone una mappatura guardando le intestazioni (`docs/25`). Ora, se le intestazioni sono
esattamente quelle di Tinaba (`TINABA_HEADERS`, confrontate in minuscolo), propone direttamente:

| Campo | Colonna |
|---|---|
| data | `Data` |
| importo | `Importo`, modo **senza segno** |
| parole delle entrate | `ricevuto, ricevuta`, cercate in `Descrizione` |
| descrizione | `Descrizione` |
| controparte | `Controparte` |

Perché le intestazioni intere e non solo "c'è una colonna Importo": tante banche hanno `Importo`, e lì il segno c'è.
Riconoscere il file solo quando è proprio quello evita di rompere gli altri.

La proposta resta una proposta: si può cambiare tutto prima di importare.

---

## Parte D: i tipi giusti, le regole di `V31`

Con il segno giusto, resta da dire **che cosa** è ogni movimento. La migrazione
`V31__regole_tinaba.sql` aggiunge tre regole predefinite, a priorità 20 (prima della regola 40 sui bonifici, vedi
`docs/investimenti.md`, parte H):

| Regola | Se il testo contiene (tutte le parole, da `V32`) | Verso | Allora |
|---|---|---|---|
| Versamento nel Robo advisor (Tinaba) | `versamento Robo advisor` | uscita | **Acquisto titoli** |
| Credito Power (Tinaba) | `Ricarica effettuata Tinaba Power` | uscita | **Spesa**, "Costi bancari" |
| Cashback delle commissioni (Tinaba) | `Tinaba Cashback` | entrata | **Entrata**, "Rimborsi fiscali e bonus" (da `V33`; prima spesa positiva in "Costi bancari") |

Il perché di ciascuna:

- **Robo advisor → Acquisto titoli.** I soldi escono dal conto Tinaba ed entrano nel portafoglio. Il conto Tinaba ha
  il ruolo **Investimenti**, quindi questo movimento **non** conta di nuovo nel "versato": il versato l'ha già
  contato il bonifico da ING a Tinaba (`docs/investimenti.md`, "il conto tre volte"). Resta però visibile come tipo
  giusto nei movimenti del conto.
- **Ricarica Power → spesa in Costi bancari.** È il credito che Tinaba usa per le commissioni: soldi spesi, come una
  commissione pagata in anticipo.
- **Cashback → entrata in "Rimborsi fiscali e bonus"** (da `V33`). È un bonus di una promozione della banca. `V31`
  lo trattava come un rimborso della ricarica (spesa positiva in "Costi bancari"), ma così la categoria andava in
  attivo: il perché del cambio è nella parte J.

La categoria si cerca per nome (`LEFT JOIN category c ON lower(c.name) = lower(r.category)`): se qualcuno l'ha
rinominata, la regola si crea lo stesso, senza categoria, invece di far fallire la migrazione.

**E i bonifici ricevuti da ING?** Nessuna regola nuova: il bonifico in uscita da ING e quello in entrata su Tinaba si
**accoppiano** come giroconto (stessa cifra, date vicine, conti dello stesso proprietario) e diventano un
**Versamento investimenti**, perché il conto di arrivo ha il ruolo Investimenti.

**Chi aveva già una sua regola** (per esempio a priorità 10) la può tenere: vince la sua, che ha priorità più bassa.
Se fa la stessa cosa, la può anche disattivare.

---

## Parte E: perché il CSV e non i PDF mensili

Tinaba dà anche un PDF al mese, con il segno. Li abbiamo valutati e la scelta è il **CSV**:

| | CSV | PDF mensili |
|---|---|---|
| Quanta storia | tutta, in un file solo | un mese per file: dodici caricamenti l'anno |
| Quando | si scarica quando si vuole | uno per mese chiuso |
| Leggerlo | colonne fisse, un parser che c'è già | un'impaginazione per la stampa: colonne ricavate dalle posizioni del testo, che cambiano se Tinaba cambia grafica |
| Il segno | manca, ma si ricava in modo affidabile dal testo | c'è |

Leggere un PDF in modo robusto è un lavoro grande e fragile, per avere una sola informazione (il segno) che nel CSV si
ricava già. I PDF restano utili come **controllo**: il saldo di fine mese scritto nel PDF deve essere quello che
l'app mostra per il conto Tinaba alla stessa data.

---

## Parte F: i test, e la prova nel browser

Backend, 375 test:

- `RowReaderTest.amountsWithoutSignTakeTheSignFromTheWordsOfTheIncome`: "Ricevuto" (anche in maiuscolo) dà un
  importo positivo, il resto negativo, un "-" nel file non inganna;
- `ColumnGuesserTest.theTinabaFileIsRecognizedAndItsAmountsAreReadWithoutSign`: le intestazioni di Tinaba danno la
  mappatura senza segno;
- `CsvImportControllerTest.theTinabaFileGetsTheSignsAndTheTypesRight`, dall'inizio alla fine con dati inventati: un
  conto ING con il bonifico di −200, un conto Tinaba con ruolo Investimenti, un CSV Tinaba. Controlla:
  - l'anteprima propone il modo senza segno e mostra 200, 46,74, −200, −50;
  - dopo l'importazione: il bonifico ricevuto accoppiato come Versamento investimenti, il Robo advisor come Acquisto
    titoli, la ricarica Power come spesa in "Costi bancari", il cashback come entrata in "Rimborsi fiscali e bonus"
    (da `V33`; prima: spesa positiva in "Costi bancari").

Frontend: la pagina Importa mostra i campi del modo senza segno e li manda al backend.

Nel browser, con un file inventato: la pagina Importa ha scelto da sola "una colonna senza segno", con le parole
"ricevuto, ricevuta", e l'anteprima mostrava 200,00 € · 46,74 € · −200,00 € · −50,00 €.

---

## Parte G: come usarlo

1. In **Conti**, un conto manuale "Tinaba", con il suo IBAN e il ruolo **Investimenti**.
2. In **Importa**, scegli il conto Tinaba e il CSV: la mappatura è già pronta. Guarda i segni nell'anteprima e importa.
3. Se avevi già importato il file prima di questa modifica, quei movimenti hanno il segno sbagliato, e
   reimportarlo non li corregge: l'importo fa parte dell'impronta della riga (`docs/25`, "niente doppioni"), quindi
   le uscite arriverebbero una seconda volta, accanto alle vecchie col segno sbagliato.
   Cancella il conto Tinaba in **Conti**, ricrealo e importa di nuovo.

---

## Parte H: due correzioni, trovate usando l'app

### La causale intera: una seconda colonna di testo

**Il problema.** Il file di Tinaba ha due colonne di testo: `Descrizione` ("Ricevuto da Mario Rossi") e
`Causale/Messaggio` ("Trasferimento a mio conto"). L'importazione ne leggeva **una sola**, e la seconda finiva solo
nei "dati originali" del movimento.

**La soluzione.** Nella mappatura c'è un campo in più, `detailColumn`: nella pagina Importa,
"Altro testo da aggiungere alla descrizione (facoltativo)". Il suo testo si aggiunge alla causale dopo " · ":

```
Ricevuto da Mario Rossi · Trasferimento a mio conto
```

Se una delle due è vuota resta l'altra, senza separatore. Per il file di Tinaba la proposta automatica sceglie già
`Causale/Messaggio`.

Nel codice: `RowReader.ParsedRow` tiene separate le due parti (`description` e `detail`) e le unisce in
`fullDescription()`, che è quella che si salva e che si vede nell'anteprima.

**L'impronta non cambia, e i movimenti già importati si completano.** L'impronta di una riga (`docs/25`, "niente
doppioni") si calcola con la sola `description`, **senza** la seconda colonna. Per questo:

- reimportando un file già importato prima di questa modifica, le righe sono riconosciute come già presenti e
  **non raddoppiano**;
- per quelle righe, se la causale salvata è ancora quella di allora, si aggiunge il testo della seconda colonna
  (`BankTransaction.extendDescription`). Una causale diversa, cambiata nel frattempo, non si tocca mai.

Il risultato dell'importazione dice quante ne ha completate (`completed`): "0 movimenti importati (4 righe erano già
presenti e sono state saltate); a 2 movimenti già presenti è stata completata la descrizione."

Quindi, per chi aveva già importato il file di Tinaba: basta **reimportarlo**, con la proposta automatica.

### I giroconti scelti a mano si abbinano lo stesso

**Il problema.** Un bonifico da ING a Tinaba, corretto a mano con "Conta come: Versamento investimenti". La sua altra
metà, l'entrata su Tinaba, restava un'**Entrata** "senza categoria". Il motivo: i movimenti corretti a mano erano
esclusi dall'abbinamento (`ClassificationService.canBePaired`). L'idea era "l'utente ha deciso, non tocco niente",
ma così l'altra metà non trovava più con chi abbinarsi.

**La soluzione.** Un tipo scelto a mano che è già un giroconto (`TRANSFER_TYPES`: trasferimento interno,
versamento e prelievo investimenti, saldo della carta) **si abbina lo stesso**. Il movimento corretto tiene il suo
tipo; l'altra metà prende il tipo del giroconto. Per il movimento scelto a mano non serve nemmeno che "abbia
l'aspetto" di un giroconto (`looksLikeOwnTransfer`): lo ha già detto l'utente.

Un tipo scelto a mano che **non** è un giroconto ("Spesa", "Entrata"…) vuol dire il contrario, "questo non è un
giroconto", e resta escluso come prima.

### Test

- `RowReaderTest.aSecondTextColumnIsAddedToTheDescription`;
- `ColumnGuesserTest`: per Tinaba la seconda colonna è `Causale/Messaggio`;
- `CsvImportControllerTest.reimportingWithTheSecondTextColumnCompletesTheDescriptionsWithoutDuplicates`: importato
  senza la seconda colonna, reimportato con: nessun doppione, una causale completata, e una terza volta niente;
- `CsvImportControllerTest.theTinabaFileGetsTheSignsAndTheTypesRight`, aggiornato con la causale intera;
- `ClassificationServiceTest.aTransferChosenByHandIsStillPairedWithItsOtherHalf` e
  `aMovementChosenByHandAsAnExpenseIsNeverPaired`;
- frontend: il nuovo campo nella pagina Importa e il messaggio delle descrizioni completate.

Backend 379 test, frontend 227. Nel browser, con il file inventato: la proposta sceglie "Causale/Messaggio",
l'anteprima mostra "Ricevuto da Mario Rossi · Trasferimento a mio conto", e reimportando un file importato "alla
vecchia maniera" 2 movimenti vengono completati, senza doppioni.

**Da sapere.** Le regole guardano la causale intera, seguita dalla controparte. Il testo aggiunto va in fondo alla
causale: una regola "contiene" che cerca una frase a cavallo tra causale e controparte può non trovarla più. Per le
regole di Tinaba è risolto subito, nella parte I; per le tue regole, "Prova" dice se trovano ancora i loro movimenti.

---

## Parte I: le regole di Tinaba cercano tutte le parole

**Il buco.** La regola `V31` della ricarica Power cercava, con "contiene", la frase "Ricarica effettuata Tinaba
Power": la fine della causale ("Ricarica effettuata") attaccata alla controparte ("Tinaba Power"). Funzionava solo
finché la seconda colonna di quelle righe restava vuota. Con un messaggio il testo diventa

```
Ricarica effettuata · Credito per le commissioni Tinaba Power
```

e la frase attaccata non c'è più: la ricarica restava una spesa **senza categoria**.

**La soluzione.** Il modo "tutte le parole" (`ALL_WORDS`, `docs/40`): la regola trova il movimento se il testo ha
tutte le sue parole, intere, in qualsiasi ordine e con altre parole in mezzo. È quello giusto per un testo che è
l'unione di più colonne. La migrazione `V32__regole_tinaba_tutte_le_parole.sql` lo usa per tutte e tre le regole di
Tinaba, ma **solo** se sono ancora come le aveva create `V31` (stesso nome, stesso testo, modo "contiene"): una
regola che hai modificato tu resta la tua.

**Perché non un "contiene" più corto** ("Tinaba Power")? Perché basterebbe a questa riga, ma non dice niente del
caso generale: "tutte le parole" resiste a qualsiasi testo in mezzo.

**Il test** (`CsvImportControllerTest.theTinabaFileGetsTheSignsAndTheTypesRight`) ora ha una ricarica Power **con**
un messaggio. Senza `V32` fallisce (la ricarica non ha la categoria "Costi bancari"); con `V32` passa.

Una nota per chi prova a mano: togliendo un file di migrazione da `src/main/resources`, la sua copia resta in
`target/classes` e Flyway la applica lo stesso. Per una prova "senza" bisogna cancellare anche quella (o fare
`mvn clean`).

---

## Parte J: il cashback è un'entrata (`V33`); a che cosa servono le categorie

### Il tipo e la categoria

Ogni movimento ha due informazioni diverse:

- il **tipo** dice che cosa succede ai soldi: Spesa, Entrata, Giroconto, Versamento investimenti, Acquisto titoli,
  Rendita, Tassa… Riepilogo e Investimenti guardano **solo il tipo** (`SummaryService`, `Investments`);
- la **categoria** dice *per che cosa*. Ha un gruppo (Spesa, Entrata, Investimento, Altro, `V12`) che mette in cima
  al menu le categorie adatte, senza vietare le altre.

La categoria serve dove c'è una risposta che il tipo non dà già:

| Tipo | Categoria | Perché |
|---|---|---|
| Spesa, Entrata | sì, sempre | Budget, Analisi, abbonamenti |
| Rendite e tasse degli investimenti | sì (Cedole, Dividendi, Imposta di bollo…) | "Per tipo" nella pagina Investimenti |
| Giroconti, versamenti, prelievi, acquisti e vendite di titoli | no | il tipo dice già tutto; "da dove e verso dove" lo dice il conto |

Per questo nell'elenco dei movimenti "senza categoria" compare **solo** per spese ed entrate
(`transactions-page.ts`, `metaOf()`): per gli altri tipi la riga mostra il tipo ("acquisto titoli", "versamento
investimenti"), che non è un difetto da sistemare. Anche in Analisi la riga "Senza categoria" c'è solo per spese ed
entrate (`docs/43`). I versamenti nel Robo advisor e i bonifici accoppiati di Tinaba senza categoria sono quindi
giusti.

### Il problema del cashback

`V31` metteva ricarica Power e cashback nella stessa categoria, il cashback come rimborso (una spesa con il più,
`docs/19`). Ma il cashback non rimborsa la ricarica: è un **bonus** di una promozione della banca, e in un anno
restituisce molto più di quanto costano le ricariche. Con dati simili a quelli veri:

| | Importo |
|---|---|
| una ricarica Power | −50,00 € |
| cinque cashback | +167,59 € |
| "Costi bancari" | **+117,59 €**: una categoria di spesa in attivo |

Nel Budget e in Analisi una spesa negativa non ha senso, e mescolava i costi veri del conto con un bonus.

### La soluzione

| Movimento | Tipo | Categoria |
|---|---|---|
| Ricarica effettuata, Tinaba Power | Spesa (come prima) | "Costi bancari" (come prima) |
| Tinaba Cashback | **Entrata** | **"Rimborsi fiscali e bonus"**, categoria di partenza di `V12` |

- **La ricarica resta una spesa**, nel momento in cui si fa: sono soldi che escono per pagare le commissioni del
  conto, come un costo pagato in anticipo. Con il cashback spostato, "Costi bancari" torna a mostrare solo costi.
- **Il cashback è un'entrata**: soldi che la banca dà, non un guadagno del mercato. Per questo non è una Rendita
  degli investimenti (che finirebbe accanto a cedole e dividendi), e nemmeno serve una categoria nuova: "Rimborsi
  fiscali e bonus" c'è già.
- **Il versato non cambia.** Il versato conta i soldi usciti dai conti normali verso gli investimenti
  (`docs/investimenti.md`, parte B): il cashback nasce sul conto Tinaba, e si vede tra le entrate del Riepilogo.

### La migrazione

`backend/src/main/resources/db/migration/V33__cashback_tinaba_entrata.sql` fa due cose:

1. cambia la regola "Cashback delle commissioni (Tinaba)" in Entrata con la categoria "Rimborsi fiscali e bonus",
   **solo** se è ancora come l'avevano lasciata `V31` e `V32` (stesso nome e testo, spesa in "Costi bancari"): una
   regola modificata dall'utente resta la sua. La categoria si cerca per nome, come in `V31`: se è stata rinominata,
   la regola diventa un'entrata senza categoria;
2. aggiorna subito i movimenti già classificati da quella regola, con lo stesso calcolo di `classifyAll()`. Serve
   perché l'app riclassifica a ogni sincronizzazione, importazione o modifica, ma **non** all'avvio: senza questo
   passo i cashback già importati resterebbero spese finché non succede una di queste cose. Un tipo scelto a mano li
   tiene fuori; una categoria scelta a mano resta quella.

`V31` e `V32` non si toccano: una migrazione già applicata non si modifica, si aggiunge la successiva.

### I test

- `db.migration.V33CashbackTinabaEntrataTest`, con un database vero portato alla `V32` (Testcontainers, come i test
  di `V16` e `V28`):
  - la regola del cashback diventa Entrata in "Rimborsi fiscali e bonus", quella della ricarica resta com'è;
  - un cashback già importato diventa un'entrata; uno con il tipo scelto a mano resta com'era; uno con la categoria
    scelta a mano la tiene;
  - una regola che l'utente aveva cambiato non viene toccata.
- `CsvImportControllerTest.theTinabaFileGetsTheSignsAndTheTypesRight`: dopo l'importazione il cashback è un'entrata
  in "Rimborsi fiscali e bonus", la ricarica una spesa in "Costi bancari".
