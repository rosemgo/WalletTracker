# 25. Fase 3, passo 6: l'import CSV e i conti manuali

**Obiettivo:** portare in WalletTracker i movimenti dei conti che Enable Banking non copre (ad esempio
Trade Republic), caricando il file CSV che la banca ti fa esportare. Il programma propone come leggere le
colonne, tu controlli l'anteprima, e importi. Reimportare lo stesso file non crea doppioni.

> Prerequisiti: passo 5 (`docs/22`) e `git pull`. Al primo avvio Flyway applica la migrazione **V14**.

Il percorso, nella dashboard:

1. pagina **Conti** → "Nuovo conto manuale" (banca, nome, valuta, ruolo);
2. pagina **Importa CSV** → scegli il conto e il file;
3. controlla la proposta e l'anteprima, correggi se serve, premi **Importa**.

---

## Parte A: i concetti

### 1. Perché i file vanno solo nei conti "manuali"

Un conto collegato a una banca si aggiorna da solo: ogni giorno Enable Banking ci manda i movimenti nuovi.
Se nello stesso conto caricassimo anche un file, avremmo **due copie** di ogni movimento, scritte in modo
diverso (la banca e il CSV non usano gli stessi testi né gli stessi identificativi): nessun controllo
automatico potrebbe capire con certezza che sono lo stesso movimento.

Per questo distinguiamo due tipi di conto:

| | collegato (`ENABLE_BANKING`) | manuale (`MANUAL`) |
|---|---|---|
| da dove arrivano i movimenti | Enable Banking, da soli | file CSV, quando li carichi tu |
| consenso PSD2 | sì, scade (avvisi del passo 4) | no |
| si crea | collegando la banca | dal modulo nella pagina Conti |
| si elimina dalla dashboard | no (tornerebbe alla prossima sincronizzazione) | sì, con tutti i suoi movimenti |
| accetta file CSV | **no** (400) | sì |

Per tutto il resto un conto manuale è un conto come gli altri: ha un ruolo, i suoi movimenti vengono
classificati dallo stesso motore (regole, trasferimenti tra conti, "da verificare"), compaiono nel
riepilogo, nei budget e negli abbonamenti.

### 2. Il conto manuale nel database (V14)

Fino a V13 ogni conto aveva un collegamento (`connection_id`) e l'identificativo di Enable Banking
(`provider_uid`), entrambi obbligatori. V14 li rende facoltativi e aggiunge due colonne:

```sql
ALTER TABLE account ALTER COLUMN connection_id DROP NOT NULL;
ALTER TABLE account ALTER COLUMN provider_uid DROP NOT NULL;
ALTER TABLE account ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'ENABLE_BANKING';
ALTER TABLE account ADD COLUMN bank_name VARCHAR(100);
```

Togliere un `NOT NULL` è comodo ma pericoloso: da quel momento il database accetterebbe anche un conto
collegato **senza** collegamento. Per non perdere la garanzia, la scriviamo come **vincolo `CHECK`**: il
database accetta solo le due forme sensate.

```sql
ALTER TABLE account ADD CONSTRAINT ck_account_source CHECK (
    (source = 'ENABLE_BANKING' AND connection_id IS NOT NULL AND provider_uid IS NOT NULL)
    OR (source = 'MANUAL' AND connection_id IS NULL AND bank_name IS NOT NULL)
);
```

I conti già presenti diventano `ENABLE_BANKING` (il `DEFAULT`) e rispettano il vincolo, perché fino a V13
le due colonne erano obbligatorie. L'abbiamo verificato su un database con un conto collegato, portato alla
V13 e poi migrato; e abbiamo provato a inserire le due forme sbagliate (un conto manuale senza banca, un
conto collegato senza collegamento): il database le rifiuta entrambe con `violates check constraint
"ck_account_source"`.

Nel codice Java:

- `AccountSource` è l'enum `ENABLE_BANKING` / `MANUAL`;
- `Account.manual(...)` è un **metodo di fabbrica** (factory method): crea un conto manuale già nella forma
  giusta. La chiave esterna (`external_key`, unica) è `"manual:"` più un UUID casuale: un conto manuale non
  ha un identificativo della banca;
- `getBankName()` restituisce il nome della banca dal collegamento o, per un conto manuale, da `bank_name`:
  chi lo usa (`AccountDto`, la pagina Conti) non deve sapere di che tipo è il conto;
- la query dei conti diventa `LEFT JOIN FETCH a.connection`: con un `JOIN` semplice i conti senza
  collegamento sparirebbero dall'elenco.

**Una correzione al motore di classificazione.** Per riconoscere i trasferimenti tra i tuoi conti, il motore
usa anche il **nome dell'intestatario** che le banche mettono come nome del conto ("MARIO ROSSI"): un
bonifico che lo contiene probabilmente è verso un tuo conto (vedi `docs/07`). Il nome di un conto manuale
però lo scegli tu, e può essere qualsiasi cosa: "Carta Esselunga", per una carta prepagata. Se il motore lo
prendesse per un nome di persona, ogni spesa "Pagamento carta Esselunga" sembrerebbe un trasferimento.
Per questo `ClassificationService.ownerNames` salta i conti manuali. Il test
`theNameOfAManualAccountIsNotTakenAsTheOwnerName` lo verifica, e l'abbiamo controllato al contrario:
togliendo la correzione, il test fallisce (la spesa diventa `INTERNAL_TRANSFER`).

### 3. Il formato CSV, meno semplice di come sembra

CSV vuol dire "valori separati da virgole", ma ogni banca lo scrive a modo suo:

| Problema | Esempio | Come lo gestiamo (`CsvParser`, `TextDecoder`) |
|---|---|---|
| il **separatore** | in Italia di solito `;`, perché la virgola serve ai decimali | contiamo `;` `,` tabulazione `\|` nella prima riga (fuori dalle virgolette) e prendiamo il più frequente; l'utente può cambiarlo |
| le **virgolette** | `"Rossi; Mario"` è un campo solo, con dentro un `;` | regole dello standard RFC 4180: `""` dentro un campo è una virgoletta, e un campo tra virgolette può contenere anche un a capo |
| la **codifica** dei caratteri | Excel in italiano salva in Windows-1252: la "è" è un solo byte, `E8` | proviamo UTF-8 in modo **rigoroso**; se i byte non sono UTF-8 valido, leggiamo Windows-1252 |
| il **BOM** | alcuni programmi mettono tre byte invisibili all'inizio del file | li togliamo |
| fine riga | `\r\n` (Windows) o `\n` | accettiamo entrambi; le righe vuote si saltano |

Esistono librerie per leggere i CSV (ad esempio Apache Commons CSV). Qui ne abbiamo scritto uno piccolo
(un centinaio di righe, con i test) perché è un buon esercizio e il formato che ci serve è limitato. In un
progetto più grande una libreria matura sarebbe la scelta più prudente.

### 4. La mappatura: il programma propone, tu decidi

Ogni banca chiama e ordina le colonne in modo diverso. La regola del progetto è sempre la stessa: **il
motore non indovina in silenzio**. Quindi:

1. `ColumnGuesser` **propone** una mappatura guardando i nomi delle colonne (in italiano, inglese e
   tedesco: "Data operazione", "Importo", "Entrate"/"Uscite", "Amount", "Betrag"...) e le prime righe
   (che formato hanno le date, se i decimali sono con la virgola o con il punto);
2. la pagina mostra la proposta nei menu **e** un'anteprima: le prime righe come verranno importate;
3. se qualcosa non torna, cambi un menu e l'anteprima si aggiorna;
4. importi solo quando **tutte** le righe si leggono.

Alcune scelte prudenti di `ColumnGuesser` e `RowReader`:

- **"Data valuta" non è la valuta.** Nei CSV italiani "Data valuta" è una data; la valuta si chiama
  "Divisa". Il programma non prende mai "valuta" per la colonna della valuta. Se manca, si usa quella del
  conto;
- le parole brevi (3 lettere o meno, come "in" e "out") devono coincidere con il nome della colonna, non
  solo esserne una parte: "in" non deve far scegliere la colonna "Data inserimento";
- le date si leggono in modo **rigoroso** (`ResolverStyle.STRICT`): il 31/02 è un errore, non il 3 marzo.
  Se dopo la data c'è un'ora ("2026-10-03 10:12"), la ignoriamo;
- gli importi accettano simboli di valuta, spazi, separatori delle migliaia, il segno meno alla fine
  ("12,50-") e le parentesi ("(12,50)" = negativo). Un importo con **più di due decimali** è un errore:
  "1.234" con la virgola come separatore dei decimali è milleduecentotrentaquattro, ma con il punto sarebbe
  uno virgola due tre quattro. Invece di scegliere noi, diciamo di controllare il separatore;
- con due colonne (entrate e uscite) l'importo è entrata − uscita; le uscite possono essere scritte con o
  senza il meno.

La mappatura viaggia come JSON (il record `ImportMapping`), con le colonne indicate dal loro numero (da 0).
Data e importo non hanno `@NotNull`: mentre l'utente compone la mappatura, l'anteprima ne accetta una
incompleta e risponde "Indica la colonna dell'importo." tra gli errori, invece di un 400. L'importazione
invece la vuole completa.

### 5. Tutto o niente

Se anche una sola riga non si legge, l'importazione è **rifiutata** con l'elenco degli errori (al massimo
20, con il numero di riga del file: l'intestazione è la riga 1):

```json
{
  "status": 400,
  "detail": "2 righe non si possono leggere: correggi la mappatura o il file. Non è stato importato niente.",
  "errors": ["Riga 3: la data \"31/02/2026\" non è nel formato gg/mm/aaaa.", "Riga 4: l'importo \"dodici\" non è un numero."]
}
```

Perché non importare le righe buone e saltare le altre? Perché una riga saltata è un movimento che manca,
e un saldo o un budget sbagliati senza che tu lo sappia sono peggio di un file da correggere. Il campo
`errors` in più si aggiunge a un `ProblemDetail` con `setProperty`.

`importFile` è `@Transactional`: se qualcosa si rompe a metà (anche durante la classificazione), il
database torna com'era.

### 6. Niente doppioni: l'impronta di ogni riga

I file delle banche si sovrappongono: esporti "ultimi 90 giorni" oggi e di nuovo tra un mese. Le righe già
importate vanno riconosciute e saltate. Ma i CSV, di solito, non hanno un identificativo del movimento.

La soluzione: un'**impronta** (hash SHA-256) dei dati della riga, salvata come `dedup_key` del movimento
con il prefisso `csv:`:

```
impronta = SHA-256( data | importo | valuta | descrizione | controparte | occorrenza )
```

L'**occorrenza** risolve un caso vero: due caffè uguali nello stesso giorno, nello stesso file. Senza
occorrenza avrebbero la stessa impronta e il secondo sembrerebbe un doppione. Con l'occorrenza:

| riga | data, importo, testo | occorrenza | impronta |
|---|---|---|---|
| 3 | 05/10/2026, −1,20, Caffè Centrale | 1 | `csv:7a1f…` |
| 4 | 05/10/2026, −1,20, Caffè Centrale | **2** | `csv:c93e…` (diversa) |

Reimportando lo stesso file le impronte coincidono: tutte e due le righe vengono saltate. Le impronte già
presenti nel conto si leggono con **una sola query** (`findDedupKeysByAccount`), non una per riga.
L'impronta è unica **per conto** (vincolo `UNIQUE (account_id, dedup_key)` di V3): lo stesso file
importato in due conti diversi dà due serie di movimenti, una per conto.

Il limite (da conoscere): se la banca cambia il testo di un movimento tra un'esportazione e l'altra (ad
esempio aggiunge l'ora alla descrizione), per noi è una riga diversa e verrebbe importata di nuovo.

La riga originale si conserva come JSON nel `raw_json` del movimento (`{"Data": "05/10/2026", "Uscite":
"1,20", ...}`), come facciamo con i dati di Enable Banking. Il file invece **non** si conserva: lo manda il
browser a ogni richiesta.

### 7. Mandare un file al backend: le richieste "multipart"

Un file non sta dentro un JSON. Si usa lo stesso formato di un modulo HTML con un allegato,
`multipart/form-data`: il corpo è diviso in **pezzi**, ognuno con un nome, separati da una stringa
("boundary"). Le nostre richieste hanno due pezzi:

- `file`: il CSV;
- `mapping`: il JSON della mappatura.

Nel **backend** (`ImportController`):

```java
@PostMapping
public ImportResult importFile(@RequestPart("file") MultipartFile file,
        @Valid @RequestPart("mapping") ImportMapping mapping, @RequestParam Long accountId) { ... }
```

`@RequestPart` prende un pezzo per nome: per il file ci dà un `MultipartFile`; per la mappatura legge il
JSON e lo trasforma in un record, come farebbe `@RequestBody`. Perché il JSON venga riconosciuto come tale,
il pezzo deve dichiarare il tipo `application/json`.

Nel **frontend** (`WalletApi.importCsv`) il pezzo JSON è un `Blob` con quel tipo:

```ts
const form = new FormData();
form.append('file', file);
form.append('mapping', new Blob([JSON.stringify(mapping)], { type: 'application/json' }));
return this.http.post<ImportResult>('/api/imports', form, { params: { accountId } });
```

Attenzione: **non** impostare a mano l'header `Content-Type`. Lo scrive il browser, insieme al boundary;
scritto a mano, il boundary mancherebbe e il backend non saprebbe dividere i pezzi.

### 8. I limiti, e un errore trovato solo dal vero

Un file caricato è un dato che arriva da fuori: va limitato (vedi `docs/17`).

- **5 MB** per file (`spring.servlet.multipart.max-file-size`; 6 MB per tutta la richiesta). Un estratto
  conto di un anno è di poche centinaia di KB. Oltre, il server risponde **413 Content Too Large**: "Il file
  è troppo grande: al massimo 5 MB. Esporta un periodo più breve." La pagina lo dice anche prima di
  mandarlo;
- **50.000 righe** al massimo (`CsvImportService.MAX_ROWS`);
- si importa solo nei conti manuali, e il file non resta sul server.

Il messaggio in italiano per il 413 ci ha insegnato due cose.

**1. MockMvc non vede questo limite.** La prima versione aveva un `@ExceptionHandler` per
`MaxUploadSizeExceededException`. I test con MockMvc passavano, ma MockMvc non passa dal server web
(Tomcat), che è proprio chi controlla la dimensione mentre legge la richiesta. Provando con `curl` sul
backend vero, la risposta era `"detail": "Maximum upload size exceeded"`: il nostro metodo non veniva
chiamato. Per questo c'è `UploadLimitTest`, con `@SpringBootTest(webEnvironment = RANDOM_PORT)`: Spring
avvia Tomcat su una porta libera e il test gli manda una richiesta HTTP vera di 6 MB.

**2. Il perché.** Con `spring.mvc.problemdetails.enabled: true` Spring Boot registra un suo gestore degli
errori standard (una sottoclasse di `ResponseEntityExceptionHandler`), che gestisce anche questa eccezione
e viene consultato **prima** del nostro. La soluzione pulita: `ApiExceptionHandler` ora **estende**
`ResponseEntityExceptionHandler`. Boot registra il suo solo se non ce n'è già uno
(`@ConditionalOnMissingBean`), quindi resta solo il nostro, e cambiamo il messaggio sovrascrivendo un
metodo:

```java
@Override
protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e,
        HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
            "Il file è troppo grande: al massimo 5 MB. Esporta un periodo più breve.");
    return handleExceptionInternal(e, problem, headers, status, request);
}
```

Gli altri errori standard (400 di validazione, 404, 405...) restano esattamente come prima: è la stessa
classe che usava Boot.

### 9. Eliminare tanti movimenti: una query, non migliaia

Eliminando un conto manuale si eliminano anche i suoi movimenti. Un metodo "derivato" di Spring Data come
`long deleteByAccount(Account account)` funziona, ma **carica** ogni movimento e poi lo cancella **uno alla
volta**: con un file di 10.000 righe, 10.000 `DELETE`. Con `@Modifying` e una query scritta da noi è un
solo comando:

```java
@Modifying
@Query("DELETE FROM BankTransaction t WHERE t.account = :account")
int deleteByAccount(@Param("account") Account account);
```

Dopo la cancellazione `AccountService.deleteManual` riclassifica tutto: un movimento di un altro conto
poteva essere abbinato a uno di quelli eliminati come trasferimento, e ora non ha più l'altra metà.

---

## Parte B: la dashboard

### La pagina Conti

- Il modulo **"Nuovo conto manuale"**: banca, nome, IBAN facoltativo, valuta, ruolo. Gli stessi controlli del
  backend (`ManualAccountRequest`): qui dicono subito che cosa manca, là proteggono i dati;
- per i conti manuali, al posto della data del consenso c'è "conto manuale", e due comandi: **importa un
  file** (apre la pagina di importazione con il conto già scelto, `?conto=8`) ed **elimina** (con
  conferma, come per le categorie);
- la tabella ora scorre in orizzontale sul telefono (`.table-scroll`), come quelle delle altre pagine.

### La pagina Importa CSV

Tre riquadri: **1. Conto e file**, **2. Come leggere le colonne**, **3. Anteprima** (le righe lette e, sotto,
il file così com'è). Tre dettagli di Angular che vale la pena guardare in `import-page.ts`:

**`switchMap`: conta solo l'ultima risposta.** Ogni modifica a un menu chiede una nuova anteprima. Se cambi
due menu di fila, partono due richieste; se la prima risponde dopo la seconda, mostrerebbe un'anteprima
vecchia. Le richieste passano da un `Subject` e da `switchMap`, che **annulla** la richiesta precedente
quando ne parte una nuova:

```ts
this.previewRequests.pipe(
  switchMap((mapping) => this.api.previewImport(this.file()!, this.account.value!, mapping)),
  takeUntilDestroyed(),
).subscribe((preview) => ...);
```

**`emitEvent: false`: niente giri infiniti.** La prima anteprima porta la mappatura proposta, che mettiamo
nei menu. Ma cambiare i menu chiede un'anteprima... che rimette la mappatura nei menu... Con
`patchValue(mapping, { emitEvent: false })` il modulo cambia senza avvisare chi ascolta `valueChanges`.

**`[ngValue]` per i numeri e il null.** Le colonne sono numeri, e "nessuna" è `null`. Con `[value]` il
menu restituirebbe stringhe ("2", "null"); `[ngValue]` conserva il valore com'è (vedi anche `docs/24`).

Il pulsante **Importa N righe** si accende solo se ogni riga si legge (`errorCount === 0`). Dopo
l'importazione la pagina dice quanti movimenti sono nuovi e quanti erano già presenti, e ricorda di
controllare quelli "Da verificare": molti CSV, come quello di Trade Republic, non hanno una causale, e
senza testo il motore non indovina.

---

## Parte C: i test

**Backend**

| Test | Che cosa verifica |
|---|---|
| `CsvParserTest` (5) | separatore, virgolette e `""`, a capo dentro un campo, BOM e `\r\n`, virgoletta mai chiusa |
| `RowReaderTest` (8) | importi all'italiana e all'inglese, importi sbagliati, date con e senza ora, date impossibili, entrate/uscite, valuta |
| `ColumnGuesserTest` (4) | estratto italiano ("Data valuta" non è la valuta), entrate/uscite, file inglese, colonne sconosciute |
| `CsvImportControllerTest` (8) | anteprima con proposta (senza salvare), mappatura incompleta, importazione + reimportazione + file sovrapposto, due righe uguali, file rifiutato senza salvare niente, conto collegato rifiutato, mappature e file non validi, conto inesistente |
| `AccountControllerTest` (+4) | creazione di un conto manuale (IBAN senza spazi, niente consenso), dati non validi, eliminazione con riclassificazione, un conto collegato non si elimina |
| `ClassificationServiceTest` (+1) | il nome di un conto manuale non è il nome dell'intestatario |
| `UploadLimitTest` (1) | un file di 6 MB, su un server vero: 413 con il messaggio in italiano |

**Frontend:** `wallet-api.spec.ts` (i due pezzi della richiesta multipart, il pezzo JSON con il suo tipo),
`accounts-page.spec.ts` (modulo, conferma dell'eliminazione, niente "elimina" per i conti collegati),
`import-page.spec.ts` (solo conti manuali, proposta nei menu, nuova anteprima a ogni modifica, errori che
spengono il pulsante, file troppo grande non mandato, errore del backend mostrato).

Oltre ai test automatici, la verifica nel browser: conto creato dal modulo; file in Windows-1252 con
entrate e uscite in due colonne e due righe identiche; proposta giusta; un formato di data sbagliato che
spegne il pulsante; importazione (4 movimenti), reimportazione (0 nuovi, 4 saltati); file con una data
impossibile; pagina sul telefono senza scorrimento orizzontale; eliminazione del conto con i movimenti.

---

## Parte D: provalo

1. `git pull`, riavvia il backend (Flyway applica la V14) e la dashboard.
2. Pagina **Conti** → "Nuovo conto manuale": banca "Prova", nome "Conto di prova", valuta EUR.
3. Salva questo testo in un file `prova.csv`:
   ```
   Data operazione;Data valuta;Descrizione;Entrate;Uscite
   01/10/2026;01/10/2026;Stipendio ottobre;2.100,00;
   03/10/2026;03/10/2026;NETFLIX.COM;;12,99
   05/10/2026;05/10/2026;Caffè Centrale;;1,20
   05/10/2026;05/10/2026;Caffè Centrale;;1,20
   ```
4. Pagina **Importa CSV**: scegli il conto e il file. Controlla la proposta (entrate e uscite in due
   colonne) e importa: 4 movimenti.
5. Importa di nuovo lo stesso file: 0 nuovi, 4 già presenti.
6. Nella pagina **Movimenti** filtra per il conto: lo stipendio è un'entrata, NETFLIX una spesa.
7. Torna su **Conti** ed elimina il conto di prova.

### Esercizi

1. Cambia nel file la data di una riga in `31/02/2026` e prova a importarlo. Che cosa dice la pagina? Il
   database è cambiato?
2. Togli il campo `occurrence` dall'impronta (`CsvImportService.dedupKey`) e lancia
   `CsvImportControllerTest`. Quale test fallisce, e perché? Rimettilo.
3. Prova a eliminare un conto collegato con `curl -X DELETE http://localhost:8080/api/accounts/1`. Che
   risposta ottieni? Poi prova, in `psql`, a trasformarlo in conto manuale:
   `UPDATE account SET source = 'MANUAL' WHERE id = 1;`. Chi te lo impedisce?
4. Togli `extends ResponseEntityExceptionHandler` da `ApiExceptionHandler` e rimetti un
   `@ExceptionHandler(MaxUploadSizeExceededException.class)`. Lancia `UploadLimitTest`: che cosa succede?

### E Trade Republic?

Non c'è (ancora) una mappatura già pronta per Trade Republic: non volevamo scriverla senza aver visto un
file vero, perché il formato delle sue esportazioni può cambiare. La proposta automatica e i menu bastano
per qualsiasi file con data e importo. Se vuoi una mappatura pronta, servono solo la **riga di
intestazione** e una o due righe con i dati **inventati** (mai dati veri nel repository).
