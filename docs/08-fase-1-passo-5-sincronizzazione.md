# 8. Fase 1, passo 5: la sincronizzazione automatica

**Obiettivo:** WalletTracker aggiorna i conti **da solo**, a intervalli regolari, senza che tu lanci il
programma a mano. Apri la dashboard (Fase 2) e trovi i movimenti già importati e classificati.

> Prerequisiti: passo 4 completato e `git pull` fatto.

---

## Parte A: i concetti

### 1. Da "programma che finisce" a "servizio sempre acceso"

| | Fino al passo 4 (profilo `poc`) | Da questo passo (senza profilo) |
|---|---|---|
| Chi lo avvia | tu, quando vuoi aggiornare | tu una volta (poi, nella Fase 4, Docker all'accensione del PC) |
| Quanto dura | importa, stampa e **termina** | **resta acceso** finché non lo fermi (Ctrl+C) |
| Sei presente? | sì: header PSU, nessun limite giornaliero | no: niente header PSU, **~4 letture al giorno per conto** |

Il profilo `poc` resta: serve ancora per **collegare le banche** (il login sulla banca lo devi fare tu),
finché nella Fase 2 non lo farà la dashboard.

### 2. Il limite delle letture in background

La PSD2 permette a un servizio come il nostro di leggere un conto **senza di te** circa **4 volte al
giorno** (alcune banche meno). Oltre, la banca risponde `429 Too Many Requests`. Per questo:

- ogni conto viene aggiornato al massimo ogni **6 ore** (`interval`), cioè 4 volte al giorno;
- la data dell'ultimo tentativo sta **nel database** (`account.last_sync_at`), non in memoria: se
  riavvii l'applicazione dieci volte al giorno, il limite resta rispettato lo stesso.

### 3. Due tempi diversi: `interval` e `check-every`

- **`interval` (6 ore)**: ogni quanto aggiornare **ciascun conto**.
- **`check-every` (30 minuti)**: ogni quanto il programma **controlla** se c'è un conto da aggiornare.
  Il controllo legge solo il database e non chiama la banca, quindi non costa nulla.

Perché non un semplice "ogni 6 ore"? Perché un PC si spegne, va in sospensione, viene riavviato. Un
esempio:

```
08:00  avvio, primo controllo: i conti non sono mai stati letti → li aggiorna tutti
08:30  controllo: ultimo aggiornamento 30 minuti fa → niente da fare
...
13:00  il PC va in sospensione
19:10  il PC si risveglia
19:30  controllo: ultimo aggiornamento alle 08:00, più di 6 ore fa → li aggiorna
```

Con un timer "ogni 6 ore" fisso, dopo il risveglio avresti potuto aspettare ore.

### 4. Gli errori: un conto non blocca gli altri

| Cosa succede | Esempio | Cosa fa WalletTracker |
|---|---|---|
| la banca risponde con un errore | `429` (troppe letture), `422`... | salta il conto e scrive il motivo in `last_sync_error` |
| nessuna risposta | rete assente, Enable Banking irraggiungibile | come sopra |
| consenso scaduto | sono passati i 90/180 giorni | non chiama nemmeno la banca; `last_sync_error` = "consenso scaduto il ...: ricollega ..." |
| errore imprevisto | un bug | come sopra, e nel log c'è il dettaglio completo (stack trace) |

In tutti i casi la data del tentativo viene salvata: il conto verrà **riprovato al prossimo
intervallo**, non subito. Riprovare subito dopo un `429` servirebbe solo a prenderne un altro.

### 5. Cosa non si può automatizzare

Il **login sulla banca** (SCA) ogni 90 o 180 giorni. Lo impone la PSD2: nessun programma può farlo al
posto tuo. Quando il consenso scade, il conto lo segnala (punto 4); nella Fase 3 arriverà un avviso
qualche giorno prima della scadenza.

---

## Parte B: il codice

Le classi nuove stanno nel package `it.wallettracker.sync`.

### 1. `V5__stato_sincronizzazione.sql`

Aggiunge due colonne alla tabella `account`:
- `last_sync_at`: quando è stata **tentata** l'ultima importazione, riuscita o no;
- `last_sync_error`: il motivo dell'ultimo errore, `NULL` se è andata bene. Nella Fase 2 la dashboard
  lo mostrerà accanto al conto.

### 2. `AccountRepository.recordSync`: una query scritta a mano

Finora le query le ricavava Spring dai nomi dei metodi. Qui scriviamo noi la query in **JPQL**, un
linguaggio simile a SQL che usa i nomi delle **classi e dei campi Java** (`Account`, `lastSyncAt`)
invece di tabelle e colonne:

```java
@Modifying
@Transactional
@Query("UPDATE Account a SET a.lastSyncAt = :at, a.lastSyncError = :error WHERE a.id = :id")
void recordSync(@Param("id") Long id, @Param("at") Instant at, @Param("error") String error);
```

- `@Modifying`: la query modifica i dati (non è una `SELECT`);
- `@Transactional`: ogni modifica deve avvenire dentro una transazione;
- `:at`, `:error`, `:id` sono i **parametri**, collegati agli argomenti con `@Param`.

Perché non `accountRepository.save(account)`? Perché `save` riscrive **tutte** le colonne del conto con
i valori dell'oggetto in memoria. Se nel frattempo avessi cambiato il ruolo del conto dalla dashboard,
il ruolo vecchio lo sovrascriverebbe. La query aggiorna **solo** le due colonne della sincronizzazione.

### 3. `SyncProperties`: le impostazioni

Un `record` con `@ConfigurationProperties`, come `EnableBankingProperties`. In `application.yml`:

```yaml
wallettracker:
  sync:
    enabled: true
    interval: ${SYNC_INTERVAL:6h}
    check-every: 30m
    initial-delay: 10s
```

Spring converte da solo `"6h"` e `"30m"` in oggetti `java.time.Duration`. L'intervallo si può cambiare
dal file `.env` (`SYNC_INTERVAL=8h`), ma **non scendere sotto le 6 ore**.

In `application-poc.yml` c'è `enabled: false`: il programma della Fase 0 aggiorna i conti da solo, con
te presente, e poi deve terminare.

### 4. `SyncService`: il cuore

Due metodi pubblici:

- **`syncAccount(account, psu)`** aggiorna **un** conto: importa, salva l'esito con `recordSync` e lo
  restituisce in un record `AccountSync(result, error)`. Gli errori non vengono rilanciati: chi chiama
  trova il motivo in `error()`. Lo usano sia la sincronizzazione automatica (con `psu = null`) sia
  `PocRunner` (con gli header PSU); nella Fase 2 lo userà il pulsante "Aggiorna ora".
- **`syncAllInBackground()`** fa un **giro**: per ogni collegamento e ogni conto non escluso, se l'ultimo
  tentativo è più vecchio di `interval` (`isDue`), lo aggiorna senza header PSU. Alla fine, se almeno un
  conto è stato aggiornato, riclassifica tutto. Restituisce un `Round(updated, failed, notDue)`.

**I `catch` in ordine.** In `syncAccount`:

```java
} catch (RestClientResponseException e) {   // la banca ha risposto con un errore (es. 429)
} catch (RestClientException e) {           // nessuna risposta (rete assente...)
} catch (RuntimeException e) {              // qualsiasi altra cosa: un bug
}
```

Java usa il **primo** `catch` che corrisponde, quindi si va dal più specifico al più generico.
`RestClientResponseException` è una sottoclasse di `RestClientException`, che è una sottoclasse di
`RuntimeException`: nell'ordine inverso, il primo `catch` prenderebbe tutto.

**Perché `syncAccount` NON è `@Transactional`.** È un dettaglio sottile ma importante:
1. `importAccount` ha la sua transazione. Se la banca risponde con un errore, l'eccezione la **annulla**
   (rollback): nessun movimento salvato a metà;
2. l'esito lo salviamo **dopo**, con `recordSync`, in una transazione separata.

Se invece `syncAccount` fosse `@Transactional`, l'importazione entrerebbe nella **stessa** transazione.
Un'eccezione dentro `importAccount`, anche se poi la "catturiamo" con `catch`, segnerebbe l'intera
transazione come "da annullare": alla fine Spring annullerebbe anche il salvataggio dell'esito e
lancerebbe `UnexpectedRollbackException`. Regola pratica: **non catturare le eccezioni di un metodo
`@Transactional` dentro un'altra transazione**, se vuoi che il resto venga salvato.

**Il logger.** Al posto di `System.out.println` usiamo un *logger* (SLF4J, incluso in Spring Boot):

```java
private static final Logger log = LoggerFactory.getLogger(SyncService.class);
log.info("{}: {} nuovi, {} già presenti, {} in attesa", account.getName(), ...);
```

Ogni messaggio ha data, ora, **livello** (`DEBUG`, `INFO`, `WARN`, `ERROR`) e la classe che l'ha
scritto. Le `{}` vengono sostituite dagli argomenti. Quali livelli mostrare si decide in
`application.yml` (`logging.level`), senza toccare il codice: in `application-poc.yml`, ad esempio,
per la sincronizzazione mostriamo solo gli `ERROR`, perché gli esiti li stampa già `PocRunner`.

### 5. `SyncScheduler`: il timer

```java
@Component
@EnableScheduling
@ConditionalOnBooleanProperty("wallettracker.sync.enabled")
public class SyncScheduler {

    @Scheduled(initialDelayString = "${wallettracker.sync.initial-delay}",
            fixedDelayString = "${wallettracker.sync.check-every}")
    public void syncDueAccounts() { ... }
}
```

- **`@EnableScheduling`** accende in Spring i metodi programmati: Spring crea un **thread** (un
  "filo di esecuzione" parallelo al resto del programma) che li esegue al momento giusto. È anche il
  motivo per cui l'applicazione **non termina**: finché quel thread è attivo, Java resta acceso.
- **`@Scheduled`** ha tre modi di dire "quando":
  | Attributo | Significato |
  |---|---|
  | `fixedDelay` | N minuti dopo la **fine** del giro precedente (quello che usiamo: due giri non si sovrappongono mai) |
  | `fixedRate` | ogni N minuti dall'**inizio** del giro precedente |
  | `cron` | a orari precisi, es. `"0 0 7 * * *"` = ogni giorno alle 7:00 |
- **`@ConditionalOnBooleanProperty`**: la classe esiste solo se la proprietà vale `true`. Con il profilo
  `poc` (o nei test) il timer non viene nemmeno creato.
- Se il metodo lancia un'eccezione, Spring la scrive nel log e al giro successivo riprova.

### 6. `PocRunner`

Ora importa con `syncService.syncAccount(account, psu)`. Così anche gli aggiornamenti fatti a mano
salvano `last_sync_at`, e la sincronizzazione automatica non rilegge subito un conto appena aggiornato.

### 7. I test

Nei test la sincronizzazione automatica è **spenta**, con il file
`src/test/resources/config/application.yml`. Spring Boot legge sia `application.yml` sia
`config/application.yml`, e il secondo vince; questo file esiste solo nelle classi di test, quindi
l'applicazione vera non lo vede.

| Test | Cosa verifica |
|---|---|
| `backgroundSyncReadsWithoutPsuHeadersAndRecordsTheOutcome` | in background niente header PSU; esito salvato; movimenti classificati |
| `aFailingAccountDoesNotStopTheOthers` | un `429` su un conto non ferma gli altri |
| `afterAFailureTheAccountWaitsForTheNextInterval` | dopo un errore il conto non viene riletto subito |
| `onlyAccountsNotUpdatedForAWholeIntervalAreRead` | un conto aggiornato da meno di 6 ore non viene riletto |
| `excludedAccountsAndExpiredConsentsAreNotRead` | conti esclusi e consensi scaduti: nessuna chiamata alla banca |
| `SyncSchedulerTest` | con un controllo ogni secondo, Spring chiama il giro da solo più volte |

L'ultimo test **riaccende** la sincronizzazione solo per sé, con
`@SpringBootTest(properties = "wallettracker.sync.enabled=true", ...)`, e usa `verify(..., timeout(6000))`
di Mockito: "aspetta fino a 6 secondi che il metodo venga chiamato".

---

## Parte C: cosa fare

1. **Aggiorna e lancia i test:**
   ```powershell
   git status
   git pull
   docker compose up -d
   cd backend
   .\mvnw.cmd test          # 43 test
   ```
2. **Lancia il programma della Fase 0** come al solito (`-Dspring-boot.run.profiles=poc`), per
   aggiornare i conti con te presente. All'avvio Flyway applica `V5`.
3. **Prepara la prova.** Hai appena aggiornato tutti i conti, quindi per le prossime 6 ore la
   sincronizzazione automatica non avrebbe niente da fare. Per vederla subito al lavoro, in IntelliJ
   azzera le date:
   ```sql
   UPDATE account SET last_sync_at = NULL;
   ```
   Non barare con un `interval` più corto per "fare prima": con un intervallo di pochi minuti
   l'applicazione rileggerebbe i conti ogni 30 minuti e supereresti il limite delle banche.
4. **Avvia la sincronizzazione automatica**, cioè l'applicazione **senza profilo**:
   ```powershell
   .\mvnw.cmd spring-boot:run
   ```
   Dopo una decina di secondi vedrai nel log una riga per conto e un riepilogo:
   ```
   ... INFO  ... SyncService   : Conto Arancio: 2 nuovi, 31 già presenti, 0 in attesa
   ... WARN  ... SyncService   : Conto Trade Republic: importazione non riuscita: HTTP 429 ...
   ... INFO  ... SyncScheduler : Sincronizzazione: 7 conti aggiornati, 1 con errori, 0 non ancora da aggiornare
   ```
   Un `429` su Trade Republic è possibile: in background concede pochissime letture. È esattamente il
   caso che il passo gestisce: gli altri conti vanno avanti, e Trade Republic verrà riprovato fra 6 ore.
5. **Controlla lo stato dei conti** in IntelliJ:
   ```sql
   SELECT name, role, last_sync_at, last_sync_error FROM account ORDER BY name;
   ```
6. **Lascialo acceso** per qualche ora, se vuoi, e guarda i nuovi giri nel log. Per fermarlo: `Ctrl+C`.

> Per ora la sincronizzazione funziona solo finché il PC è acceso e il programma è aperto. Nella Fase 4
> l'applicazione girerà in un container Docker che parte da solo all'accensione
> (`restart: unless-stopped`), su un PC di casa o un piccolo server.

---

## Cosa hai imparato

- La differenza tra un programma che **termina** e un **servizio** sempre acceso.
- **`@Scheduled`** e `@EnableScheduling`: `fixedDelay`, `fixedRate` e `cron`.
- Come rispettare un **limite esterno** (le letture PSD2) salvando lo stato nel database.
- Una query **JPQL** scritta a mano con `@Query` e `@Modifying`, e perché è meglio di `save()` qui.
- Le **transazioni e le eccezioni**: perché un metodo che cattura gli errori non deve essere
  `@Transactional`.
- Il **logging** con SLF4J e i suoi livelli.
- **Componenti condizionali** (`@ConditionalOnBooleanProperty`) e impostazioni solo per i test.

## Prossimi passi

Con questo passo la **Fase 1 è completa**. Nella **Fase 2** arrivano il server web, le API REST e la
dashboard React, con la configurazione guidata (collegare le banche, scegliere i ruoli dei conti,
correggere i movimenti, gestire le regole) e il pulsante **"Aggiorna ora"**, che userà
`syncAccount` con gli header PSU.
