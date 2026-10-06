# 6. Fase 1, passo 3: i movimenti, senza doppioni

**Obiettivo:** salvare nel database i movimenti di tutti i conti. Ogni volta che rilanci il
programma, devono entrare solo i movimenti **nuovi**: niente doppioni, nemmeno con le pagine
ripetute di Trade Republic.

> Prerequisiti: passo 2 completato (le banche sono collegate e salvate) e `git pull` fatto.

---

## Parte A: il problema dei doppioni

Un'importazione non scarica mai "esattamente i movimenti nuovi". Ci sono quattro situazioni da
gestire:

| Situazione | Esempio | Rischio |
|---|---|---|
| **Sovrapposizione voluta** | ogni importazione riparte qualche giorno prima dell'ultimo movimento salvato, per non perdere nulla | lo stesso movimento arriva due volte |
| **Pagine ripetute** | Trade Republic manda ogni movimento due volte, identico | doppione |
| **Movimenti uguali ma distinti** | due caffè da 1,20 € nello stesso bar, lo stesso giorno | scartarne uno per errore |
| **Movimenti in attesa** | un acquisto Amazon `PDNG` oggi diventa `BOOK` domani, magari con una data diversa | contarlo due volte |

### La soluzione: l'impronta (`dedup_key`)

A ogni movimento diamo un'**impronta** che resta uguale tra un'importazione e l'altra. Prima di
salvare controlliamo se esiste già.

1. **Copie identiche:** se nella stessa risposta due movimenti hanno lo **stesso JSON**, ne teniamo
   uno solo. Risolve le pagine ripetute.
2. **Impronta:**
   - se la banca fornisce un identificativo (`entry_reference`), l'impronta è `ref:<identificativo>`;
   - altrimenti è `fp:<hash>`, dove l'hash SHA-256 è calcolato da data, importo, valuta, controparte
     e causale. Un **hash** trasforma un testo di qualsiasi lunghezza in una stringa fissa di 64
     caratteri: testi uguali danno hash uguali.
3. **Movimenti uguali ma distinti:** se nella stessa risposta due movimenti diversi (JSON diverso)
   hanno la stessa impronta, al secondo aggiungiamo `#2`, al terzo `#3`... I due caffè diventano
   `fp:abc...` e `fp:abc...#2`. Alla reimportazione si ripresentano nello stesso ordine, quindi le
   impronte coincidono e non si duplicano.
4. **Movimenti in attesa:** a ogni importazione cancelliamo quelli salvati e li sostituiamo con
   quelli attuali. Quando l'acquisto diventa contabilizzato, quello in attesa sparisce e resta solo
   quello definitivo.

### La rete di sicurezza: il vincolo `UNIQUE`

Nella tabella c'è:

```sql
CONSTRAINT uq_bank_transaction_account_dedup UNIQUE (account_id, dedup_key)
```

Il database stesso **rifiuta** due movimenti con la stessa impronta sullo stesso conto. Se un
giorno il codice avesse un bug, l'inserimento fallirebbe invece di creare un doppione silenzioso.
È un principio generale: le regole importanti si fanno rispettare anche nel database, non solo nel
codice.

---

## Parte B: il codice, file per file

### 1. `V3__movimenti.sql`: la tabella `bank_transaction`

Si chiama così perché `transaction` è una parola riservata in SQL. Le colonne più interessanti:

| Colonna | Tipo | Perché |
|---|---|---|
| `amount` | `NUMERIC(19,2)` | decimale **esatto**, con segno (negativo = uscita). Mai `float` per i soldi |
| `status` | `VARCHAR(10)` | `BOOKED` o `PENDING` (enum `TransactionStatus`) |
| `raw_json` | `JSONB` | il JSON originale della banca, così com'è (vedi sotto) |
| `dedup_key` | `TEXT` | l'impronta |

Ci sono anche:
- **il vincolo `UNIQUE`** descritto sopra;
- **un indice** su `(account_id, booking_date)`. Un indice è come quello analitico di un libro:
  rende veloce trovare i movimenti di un conto per data senza leggere tutta la tabella.

### 2. Perché conserviamo il JSON originale (`JSONB`)

Il record `Transaction` legge solo i campi che conosciamo. Il JSON originale contiene **tutto**,
anche i campi che oggi ignoriamo. Ci serve per tre motivi:

- **Trade Republic:** non conosciamo ancora i campi utili per le descrizioni, ora potremo guardarli;
- **regole nuove:** quando miglioreremo la classificazione, potremo rielaborare i movimenti vecchi
  senza scaricarli di nuovo;
- **controllo:** se un importo sembra sbagliato, si verifica cosa ha mandato davvero la banca.

`JSONB` è il tipo JSON di PostgreSQL: lo salva in formato binario e permette di interrogarlo con
SQL (vedi gli esercizi).

Per poterlo salvare, il client ora restituisce `RawTransaction`, una coppia:

```java
public record RawTransaction(Transaction transaction, String json) { }
```

`transaction` contiene i campi già convertiti, `json` il testo originale. Nel client, ogni
movimento viene letto prima come `JsonNode` (JSON generico) e poi convertito.

### 3. L'entità `BankTransaction`

È simile ad `Account`. Due annotazioni nuove:

```java
@Column(name = "amount", nullable = false, precision = 19, scale = 2)
private BigDecimal amount;

@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "raw_json", nullable = false)
private String rawJson;
```

- **`precision`/`scale`:** devono corrispondere a `NUMERIC(19,2)`, altrimenti la validazione di
  Hibernate blocca l'avvio.
- **`@JdbcTypeCode(SqlTypes.JSON)`:** dice a Hibernate che questa stringa va salvata come JSON
  (`JSONB`) e non come testo.

### 4. `TransactionImportService`: il cuore del passo

Il metodo `importAccount(account, psu)` esegue questi passi:

1. **Calcola da quale data scaricare** (se la banca rifiuta il periodo con `WRONG_TRANSACTIONS_PERIOD`, riprova una volta con gli ultimi 89 giorni): dall'ultimo movimento contabilizzato salvato, meno 10
   giorni di margine (`OVERLAP_DAYS`). Alla prima importazione, un anno fa (`FIRST_IMPORT_DAYS`);
   la banca può darne meno.
2. **Scarica** i movimenti dal client.
3. **Cancella** i movimenti in attesa salvati, e chiama subito `flush()` (vedi il riquadro qui sotto).
4. **Toglie le copie identiche.**
5. Per ogni movimento: **calcola l'impronta**, salta quelli già presenti, salva gli altri.
6. **Restituisce un riepilogo** (`ImportResult`): ricevuti, copie ripetute, nuovi, già presenti, in attesa.

> **Il `flush()` e l'ordine delle operazioni.** Dentro una transazione Hibernate non esegue
> subito le modifiche: le accumula e le manda al database alla fine (*flush*), in un ordine suo, con
> **gli inserimenti prima delle cancellazioni**. Senza `flush()`, un nuovo movimento in attesa con la
> stessa impronta di uno da cancellare verrebbe inserito mentre il vecchio è ancora lì, e il vincolo
> `UNIQUE` lo rifiuterebbe. `flush()` forza le cancellazioni subito.

Le funzioni di supporto (`dateOf`, `counterpartyOf`, `descriptionOf`, `dedupKeyOf`) sono piccole e
commentate. Gli importi vengono anche riportati a 2 decimali, perché Trade Republic ne manda 6.

### 4b. Da quale data si importa, e il limite dei 100 movimenti

La data di inizio viene scelta in **tre casi**, e il riepilogo stampa quale si è verificato:

| Caso | Quando | Data di inizio | Messaggio |
|---|---|---|---|
| 1 | il conto non ha movimenti salvati | oggi − `FIRST_IMPORT_DAYS` | `prima importazione: ultimi 365 giorni` |
| 2 | il conto ha già movimenti salvati | ultimo movimento salvato − `OVERLAP_DAYS` | `ultimo movimento salvato (2026-10-01) meno 10 giorni` |
| 3 | la banca risponde `WRONG_TRANSACTIONS_PERIOD` | oggi − `DAYS_WITHOUT_RECENT_SCA` | `la banca ha rifiutato il periodo richiesto: ultimi 89 giorni` |

Quindi `FIRST_IMPORT_DAYS` conta **solo** quando il conto è vuoto. Per sperimentare con valori
diversi, cancella prima i movimenti del conto:
`DELETE FROM bank_transaction WHERE account_id = <id>;`.

**Il limite dei 100 movimenti.** La carta di credito ING restituisce al massimo 100 movimenti per
richiesta, e non manda una `continuation_key` per le pagine successive. Il resto andrebbe perso in
silenzio. Il metodo `fetchInWindows` lo gestisce così:

```
richiesta 1: 07/07 → 06/10   → 100 movimenti: forse troncata, divido a metà
  richiesta 2: 07/07 → 22/08 →  64 movimenti: ok
  richiesta 3: 23/08 → 06/10 → 100 movimenti: forse troncata, divido ancora
    richiesta 4: 23/08 → 14/09 → 51 ok
    richiesta 5: 15/09 → 06/10 → 58 ok
```

È una **ricorsione**: il metodo chiama sé stesso su periodi sempre più piccoli, finché ogni risposta
ha meno di 100 movimenti. Le copie al confine tra due periodi vengono eliminate dalla regola 1.

C'è una protezione: si divide **solo se la banca rispetta le date richieste**. Trade Republic le
ignora e restituirebbe sempre gli stessi movimenti, quindi dividere moltiplicherebbe solo le
richieste. Il numero di richieste fatte compare nel riepilogo (`Richieste alla banca: 5`).

### 5. `PocRunner`

Per ogni conto ora:
- **salta** i conti con ruolo `EXCLUDED`;
- stampa i saldi;
- **importa** i movimenti e stampa il riepilogo;
- mostra gli **ultimi 15 movimenti salvati nel database**, non più quelli appena scaricati.

```
=== Conto: Conto Arancio | IBAN: IT60X... | EUR | ruolo: MAIN ===
Saldo CLBD: 1234.56 EUR
Importazione dal 2026-09-21 (ultimo movimento salvato (2026-10-01) meno 10 giorni)
Richieste alla banca: 1, ricevuti 14, copie ripetute 0, nuovi 2, già presenti 12, in attesa 0
Ultimi movimenti salvati:
  2026-10-05     -23.40 EUR  BOOKED   ESSELUNGA PAGAMENTO POS
  ...
```

### 6. I test: `TransactionImportServiceTest`

Usano un database vero (Testcontainers) ma un **Enable Banking finto**:

```java
@MockitoBean
EnableBankingClient client;
...
when(client.getTransactions(eq("uid-1"), any(), any(), any())).thenReturn(transactions);
```

`@MockitoBean` sostituisce, solo per questo test, il vero client con un oggetto **Mockito**:
non fa chiamate HTTP e restituisce quello che gli diciamo con `when(...).thenReturn(...)`. Così ogni
test costruisce esattamente lo scenario che vuole verificare.

C'è un test per ogni regola:

| Test | Cosa verifica |
|---|---|
| `savesTransactionsWithSignedAmountAndOriginalJson` | importi con segno e JSON originale salvato |
| `importingTheSameDataTwiceCreatesNoDuplicates` | reimportare gli stessi dati non crea doppioni |
| `repeatedPagesAreSavedOnce` | le pagine ripetute (Trade Republic) entrano una volta sola; 6 decimali → 2 |
| `identicalLookingButDistinctTransactionsAreBothSaved` | i "due caffè" vengono salvati entrambi, e non duplicati alla reimportazione |
| `pendingTransactionsAreReplacedWhenTheyGetBooked` | un movimento in attesa viene sostituito da quello contabilizzato |
| `nextImportStartsFromTheLastSavedTransactionMinusTheOverlap` | l'importazione riparte dall'ultimo movimento salvato, meno il margine |

---

## Parte C: cosa fare

1. **Aggiorna e controlla:**
   ```powershell
   git status
   git pull
   docker compose up -d
   cd backend
   .\mvnw.cmd test        # devono passare 22 test
   ```
2. **Lancia il programma** e scegli i collegamenti salvati uno alla volta:
   ```powershell
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=poc"
   ```
   La prima importazione di ogni conto scarica fino a un anno di storico. **Rilancia** subito con lo
   stesso collegamento: devi vedere `nuovi 0` e `già presenti` uguale al numero dei ricevuti.
   - Se un conto non ti interessa, assegnagli prima il ruolo `EXCLUDED` (esercizio del passo 2).
   - Su Trade Republic dovresti vedere `copie ripetute` maggiore di zero.
3. **Guarda i dati in IntelliJ:** tabella `bank_transaction`. In `flyway_schema_history` ci sono
   ora 3 righe: V1, V2 e V3.

### Esercizi in SQL

Apri una *Query Console* in IntelliJ e prova queste query. Sono un buon allenamento, e un'anteprima
di quello che farà la dashboard.

```sql
-- Quanti movimenti per conto
SELECT a.name, a.role, COUNT(t.id) AS movimenti
FROM account a LEFT JOIN bank_transaction t ON t.account_id = a.id
GROUP BY a.id, a.name, a.role
ORDER BY movimenti DESC;

-- Entrate e uscite per mese, su tutti i conti
SELECT date_trunc('month', booking_date) AS mese,
       SUM(amount) FILTER (WHERE amount > 0) AS entrate,
       SUM(amount) FILTER (WHERE amount < 0) AS uscite
FROM bank_transaction
WHERE status = 'BOOKED'
GROUP BY mese
ORDER BY mese;
```

La seconda query **non** è ancora un conto delle spese vere: include anche i trasferimenti tra i
tuoi conti. Riconoscerli è il lavoro del prossimo passo.

```sql
-- Trade Republic: cosa c'è nel JSON originale? (->> estrae un campo come testo)
SELECT t.booking_date, t.amount, t.raw_json
FROM bank_transaction t JOIN account a ON a.id = t.account_id
JOIN bank_connection c ON c.id = a.connection_id
WHERE c.aspsp_name = 'Trade Republic'
ORDER BY t.booking_date DESC
LIMIT 5;
```

**Mandami il risultato di quest'ultima query**, cancellando nomi, IBAN e importi se preferisci. Mi
serve per capire quali campi usa Trade Republic per descrivere i movimenti.

---

## Problemi comuni

| Sintomo | Causa | Soluzione |
|---|---|---|
| `HTTP 429` durante l'importazione | limite di richieste della banca (vedi Fase 0) | il programma invia già gli header PSU; se succede comunque, riprova più tardi |
| `HTTP 422 WRONG_TRANSACTIONS_PERIOD` (es. Fineco) | senza un login recente la banca concede solo gli ultimi 90 giorni (regola PSD2) | il programma ora riprova da solo con 89 giorni. Per avere lo storico completo, ricollega la banca (`0`): l'importazione subito dopo il login può andare più indietro |
| la prima importazione di un conto riceve pochi movimenti | la banca concede meno di un anno di storico | normale: dipende dalla banca |
| `duplicate key value violates unique constraint "uq_bank_transaction_account_dedup"` | due movimenti con la stessa impronta: non dovrebbe succedere | mandami il messaggio completo: è proprio il caso che il vincolo deve far emergere |

---

## Cosa hai imparato

- Perché i doppioni sono inevitabili e come si gestiscono: **impronta**, **hash**, **numero
  progressivo**, **sostituzione dei movimenti in attesa**.
- **Vincoli `UNIQUE`** e **indici** nel database.
- **`JSONB`** per conservare dati originali interrogabili.
- **`NUMERIC`** e `BigDecimal` per i soldi; `setScale` per arrotondare.
- Il **flush** di Hibernate e l'ordine delle operazioni in una transazione.
- **Mockito** e `@MockitoBean` per sostituire una dipendenza nei test.

## Prossimo passo

**Passo 4: la classificazione automatica.** Useremo le regole di `docs/03-modello-e-regole.md`:
- **ruoli dei conti** e **trasferimenti interni**, anche con l'abbinamento tra movimenti di conti
  diversi (stesso importo, segno opposto);
- le **causali di Fineco** per cedole, dividendi e tasse.

Poi arriveranno la sincronizzazione automatica (Fase 1, ultimo passo) e la dashboard (Fase 2).
