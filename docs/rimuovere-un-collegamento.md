# Rimuovere un collegamento, tenendo la storia

Fuori dalla numerazione, nella 1.3.0. Come si toglie una banca collegata senza perdere i movimenti, e perché
ricollegandola non nascono doppioni.

| Parte | Cosa |
|---|---|
| A | il bisogno |
| B | la scelta: la storia non si cancella |
| C | che cosa fa "Rimuovi collegamento" |
| D | ricollegare: stessi conti, nessun doppione |
| E | il codice |
| F | i test, e la prova nel browser |
| G | in più: il POC dopo la Fase 4 |

---

## Parte A: il bisogno

Fino alla 1.2 l'app eliminava solo i **conti manuali**. Per una banca collegata non c'era niente: né "elimina", né
"scollega". Non era un divieto ragionato, era una funzione che mancava. Restavano così:

- i collegamenti **vuoti**, nati dai tentativi con banche non ancora nella lista di Enable Banking
  (`docs/modalita-ristretta.md`): con 0 conti, comparivano lo stesso nell'elenco del POC;
- i collegamenti di banche che non si usano più, con il consenso ancora aperto su Enable Banking.

Un'altra cosa da sapere: **cancellare un collegamento dal pannello di Enable Banking non avvisa l'app.** Enable Banking
non manda notifiche: il collegamento resta nel database fino alla scadenza del consenso, e le letture falliscono.

## Parte B: la scelta, la storia non si cancella

I movimenti passati sono dati tuoi: da loro dipendono budget, analisi, abbonamenti, investimenti e i giroconti
abbinati con gli altri conti. Cancellarli per "scollegare" una banca farebbe perdere tutto questo. Quindi:

| Collegamento | Che cosa succede |
|---|---|
| **con conti** | resta, segnato come **rimosso**: i conti e i movimenti restano come storia, l'app non li legge più |
| **senza conti** | si **cancella**: non c'è niente da tenere |

In tutti e due i casi il consenso su Enable Banking si chiude.

Per **nascondere** un conto senza togliere il collegamento resta il ruolo "Escluso" (l'app non lo legge e non lo
conta). Per **cancellare** davvero i movimenti di un conto collegato non c'è ancora un pulsante: è una scelta voluta,
perché è un'operazione senza ritorno.

## Parte C: che cosa fa "Rimuovi collegamento"

Nella pagina **Conti** c'è la sezione **"Collegamenti alle banche"**: ogni collegamento, con quanti conti ha e il suo
stato ("valido fino al…", "consenso scaduto il…", "rimosso il…: i conti restano come storia"). Accanto a ognuno,
**"Rimuovi collegamento"**, con una conferma che dice che cosa succederà:

> L'app smetterà di leggere il conto di Banca Alfa e chiuderà il consenso su Enable Banking. **Il conto e i movimenti
> restano** come storia. Se un giorno ricolleghi la banca, tornano gli stessi, senza doppioni.

Dopo la rimozione:

1. **Enable Banking**: l'app chiede di chiudere la sessione (`DELETE /sessions/{id}`), cioè di revocare il consenso
   dato in banca. Se Enable Banking non risponde, il collegamento si rimuove lo stesso e la pagina dice di chiudere
   il consenso dal pannello. Se risponde "non la conosco" (404, per esempio perché l'hai già cancellata dal pannello)
   va bene così. Un consenso già scaduto non si manda nemmeno.
2. **Database**: il collegamento ha `removed_at` (migrazione `V40`), oppure, se è vuoto, non c'è più.
3. **Da allora**, per un collegamento rimosso:
   - la sincronizzazione lo salta (né letture né errori);
   - niente avvisi "consenso in scadenza" o "scaduto";
   - non compare tra i collegamenti validi (il POC non lo propone);
   - nella tabella dei conti, al posto della scadenza: "collegamento rimosso: storico conservato".

## Parte D: ricollegare, stessi conti e nessun doppione

Ricollegare una banca rimossa è come rinnovare un consenso, che l'app sa già fare:

1. **Il conto si riconosce.** Ogni sessione di Enable Banking dà agli stessi conti un `uid` nuovo, quindi l'app non
   riconosce i conti dall'`uid` ma da una **chiave stabile** (`ConnectionService.externalKeyOf`): l'impronta di Enable
   Banking (`identification_hash`), oppure banca + IBAN + valuta. Un conto già noto non si crea di nuovo: si
   **sposta** sul collegamento nuovo (`Account.moveTo`), con il suo `uid` nuovo.
2. **Il collegamento rimosso**, rimasto senza conti, si cancella (`saveSession`, come per un rinnovo).
3. **I movimenti non raddoppiano.** Restano attaccati allo stesso conto, e ogni movimento ha la sua impronta
   (`dedup_key`, per esempio `ref:` + il riferimento della banca). Alla prima lettura la banca ridà anche quelli già
   salvati: si riconoscono e si saltano, e si aggiungono solo i nuovi.

Il test `relinkingARemovedBankBringsBackTheSameAccountsWithoutDuplicates` prova proprio questa sequenza.

## Parte E: il codice

| Dove | Che cosa |
|---|---|
| `V40__collegamento_rimosso.sql` | la colonna `removed_at` |
| `BankConnection` | `removedAt`, `isRemoved()`, `markRemoved()`; `isValid()` è falso se rimosso |
| `ConnectionRemovalService` (nuovo) | l'elenco dei collegamenti e la rimozione; la chiamata a Enable Banking è **fuori** dalla transazione, così una risposta lenta non tiene bloccato il database |
| `EnableBankingClient.deleteSession` | `DELETE /sessions/{id}` |
| `ConnectionController` | `GET /api/connections`, `DELETE /api/connections/{id}` |
| `SyncService`, `AlertService` | saltano i collegamenti rimossi |
| `AccountDto.connectionRemovedAt` | la tabella dei conti lo dice |
| `connections-section.ts` (nuovo) | la sezione della pagina Conti |

## Parte F: i test, e la prova nel browser

Backend (`ConnectionRemovalServiceTest`, con Enable Banking finto e database vero):

- rimuovere tiene conto e movimenti, chiude la sessione, toglie il collegamento dai validi, dagli avvisi e dalla
  sincronizzazione;
- ricollegare ridà lo stesso conto, cancella il collegamento rimosso, e dei movimenti ridati dalla banca aggiunge solo
  quello nuovo;
- un collegamento senza conti si cancella;
- se Enable Banking non risponde, il collegamento si rimuove e la risposta lo dice (`revoked: false`);
- una sessione che Enable Banking non conosce più conta come chiusa; un consenso scaduto non si manda.

`ConnectionControllerTest`: le due API, e `connectionRemovedAt` nei conti. Frontend: la conferma, la rimozione, lo
stato "rimosso" nella sezione e nella tabella.

Nel browser (dati inventati, larghezza di un telefono): due collegamenti, "Banca Alfa" con un conto e "Banca Beta"
vuoto. Rimosso il primo: "il conto resta come storia", e la tabella dice "collegamento rimosso: storico conservato".
Rimosso il secondo: cancellato. Senza Enable Banking configurato compare, giustamente, l'avviso di chiudere il consenso
dal pannello.

## Parte G: in più, il POC dopo la Fase 4

Il programma della Fase 0 (`docs/02`) non partiva più: il profilo `poc` avvia l'app senza server web, e la
configurazione di Spring Security della Fase 4 (`docs/46`) chiedeva `HttpSecurity`, che senza server web non esiste.
Ora `SecurityConfig.securityFilterChain` ha `@ConditionalOnWebApplication`: si crea solo con il server web (l'app
normale non cambia). `WithoutWebServerTest` avvia l'app come il POC.

Il POC inoltre, come la dashboard, **non salva più un collegamento senza conti**: spiega che la banca va prima
collegata dal pannello di Enable Banking.
