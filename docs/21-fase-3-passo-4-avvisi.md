# 21. Fase 3, passo 4: gli avvisi

**Obiettivo:** WalletTracker ti dice quando qualcosa richiede la tua attenzione. In cima al
**Riepilogo** compare un pannello di avvisi, solo se c'è qualcosa da segnalare:

| Avviso | Urgenza | Azione |
|---|---|---|
| **il collegamento con una banca è scaduto** | ✕ rosso | "Ricollega ..." |
| **il collegamento scade entro 14 giorni** | ! giallo | "Ricollega ..." |
| **l'aggiornamento di un conto non è riuscito**, con il motivo | ! giallo | "Vai ai conti" |
| **budget superati** nel mese in corso | ! giallo | "Vai ai budget" |

"Ricollega" apre **Collega una banca** con il paese e la banca già scelti: basta premere **Collega**.

> Prerequisiti: passo 3 completato e `git pull` fatto. Nessuna migrazione nuova.

---

## Parte A: i concetti

### 1. Perché l'avviso più importante è il consenso

Quando colleghi una banca, le dai un **consenso** a leggere i conti. Le regole europee (PSD2) lo fanno
durare al massimo 180 giorni (alcune banche meno, ad esempio 90). Alla scadenza:
- WalletTracker **non può** rinnovarlo da solo: serve un nuovo accesso alla banca, con la verifica in
  due passaggi (SCA). È voluto: è la garanzia che a leggere i tuoi conti sia qualcuno che hai autorizzato
  tu, di recente;
- i movimenti **smettono di arrivare**, senza nessun errore evidente. La pagina sembra a posto, ma i
  numeri sono fermi.

Senza un avviso te ne accorgeresti dopo settimane. Per questo l'avviso parte **14 giorni prima**.

**Ricollegare non fa perdere niente.** Il nuovo collegamento porta gli stessi conti. `ConnectionService`
li riconosce dalla loro chiave stabile (`docs/13`) e li **sposta** sul nuovo collegamento: movimenti,
ruoli, categorie e correzioni restano dove sono.

### 2. Avvisi calcolati, non salvati

Avremmo potuto salvare gli avvisi in una tabella ("avviso creato il..., letto sì/no"). Invece li
**calcoliamo ogni volta** dallo stato attuale:
- la scadenza di ogni collegamento (`bank_connection.valid_until`);
- l'errore dell'ultima sincronizzazione di ogni conto (`account.last_sync_error`);
- i budget del mese in corso.

Vantaggi:
- **un avviso sparisce da solo** appena il problema è risolto: ricolleghi la banca e l'avviso non c'è
  più, senza doverlo "segnare come letto";
- **niente da tenere allineato**: non può esistere un avviso "vecchio" che dice una cosa ormai falsa;
- **niente migrazione**, niente tabella in più.

Lo svantaggio: non c'è una cronologia degli avvisi. Per un'app personale non serve.

### 3. Niente doppioni

Se un collegamento è scaduto, anche la sincronizzazione dei suoi conti fallisce. Due avvisi per lo stesso
problema confondono ("devo ricollegare o c'è un altro errore?"). Per questo l'avviso "aggiornamento non
riuscito" compare **solo per i conti con il consenso ancora valido**.

Anche i budget superati sono **un solo avviso** con tutte le categorie, non uno per budget.

### 4. Il backend manda i dati, la dashboard scrive le frasi

L'API non restituisce la frase "Il collegamento con ING scade tra 6 giorni". Restituisce i **dati**:

```json
{ "kind": "CONSENT_EXPIRING", "severity": "WARNING", "bank": "ING", "country": "IT",
  "date": "2026-10-23T10:00:00Z", "daysLeft": 6, "items": [], "account": null, "message": null }
```

La frase, il formato della data e il link li costruisce la dashboard. È la stessa divisione dei compiti
vista finora: il backend conosce le **regole** (quando un consenso è "in scadenza", che cosa è
urgente), la dashboard conosce la **presentazione** (parole, colori, date in italiano). Un domani un'app
per telefono o una mail settimanale potrebbero usare la stessa API, ognuna con le sue parole.

Non tutto però può stare nella dashboard: il **motivo** di un errore di sincronizzazione arriva così
com'è (`message`), perché lo conosce solo il backend.

### 5. I giorni si contano sul calendario

Il consenso di ING scade il 23 ottobre alle 9:00, e oggi è il 17 ottobre alle 10:00. Mancano 5 giorni e
23 ore: "tra 5 giorni" o "tra 6"? Per una persona sono **6 giorni** (dal 17 al 23). Per questo il backend
non conta le ore:

```java
long daysLeft = ChronoUnit.DAYS.between(today, LocalDate.ofInstant(connection.getValidUntil(), zone));
```

Trasforma prima i due istanti in **date** (nel fuso orario del PC) e poi conta i giorni tra le date. La
prima versione usava `Duration.between(...).toDays()`, che conta i giorni interi di 24 ore e dava 5: un
errore trovato guardando la pagina con dati di prova, e ora coperto da un test.

---

## Parte B: il backend

| File | Cosa fa |
|---|---|
| `alert/AlertDto.java` | un avviso: tipo (`AlertKind`), urgenza (`Severity`) e i dati che servono a quel tipo |
| `alert/AlertService.java` | calcola gli avvisi e li ordina dal più urgente |
| `alert/AlertController.java` | `GET /api/alerts` |

Alcuni dettagli da notare:

- **`alerts(Instant now)`**: come `expectedSoFar` nel passo 3, "adesso" è un **parametro**. Il metodo
  pubblico `alerts()` passa `Instant.now()`, mentre i test passano un istante fisso
  (`2026-10-17T10:00:00Z`). Così un test su "scade tra 5 giorni" funziona per sempre, non solo il giorno
  in cui è stato scritto.
- **L'ordine**: `Comparator.comparing(AlertDto::severity)` ordina secondo l'ordine dell'enum (prima
  `CRITICAL`, poi `WARNING`), poi `thenComparing(AlertDto::daysLeft, nullsLast(...))` mette prima la
  scadenza più vicina. Gli avvisi senza giorni (sincronizzazione, budget) vanno in fondo.
- **I budget superati** riusano `BudgetService.budgets(...)`: lo stato `OVER` lo decide una sola classe,
  in un solo punto.
- **`findAllWithConnection()`**: per leggere la banca di ogni conto serve il collegamento. Il
  `JOIN FETCH` li carica con **una sola query** invece di una per conto (il problema N+1, `docs/09`).

### I test (`AlertServiceTest`)

| Test | Cosa verifica |
|---|---|
| `consentsAreReportedWhenExpiredOrExpiringAndTheMostUrgentComesFirst` | 100 giorni: niente; 13 e 5 giorni: in scadenza; −3: scaduto, rosso, per primo; poi il più vicino |
| `daysAreCountedOnTheCalendarNotInHours` | 5 giorni e 23 ore = 6 giorni |
| `aFailedSyncIsReportedOnlyIfTheConsentIsStillValid` | errore su un conto valido: avviso; su un conto scaduto: solo l'avviso di scadenza |
| `budgetsOverTheLimitThisMonthAreReportedTogether` | un solo avviso con le categorie superate, via `GET /api/alerts` |
| `withNothingToReportThereAreNoAlerts` | nessun avviso inventato |

---

## Parte C: il frontend

| File | Cosa fa |
|---|---|
| `alerts/alerts-panel.ts` / `.html` | il pannello: una frase e un'azione per ogni tipo di avviso |
| `summary/summary-page.*` | mostra `<app-alerts-panel />` in cima |
| `link/link-page.*` | legge `?bank=...&country=...` e prepara il ricollegamento |
| `api/models.ts`, `api/wallet-api.ts` | `Alert`, `alerts()` |

### `@switch`: una frase per ogni tipo

```html
@switch (alert.kind) {
  @case ('CONSENT_EXPIRED') { <strong>Il collegamento con {{ alert.bank }} è scaduto.</strong> ... }
  @case ('CONSENT_EXPIRING') { ... scade {{ when(alert.daysLeft) }} ... }
  @case ('SYNC_FAILED') { ... }
  @case ('BUDGET_OVER') { ... }
}
```

`@switch` è il fratello di `@if`: sceglie un blocco in base al valore. È il modo naturale di dire "per
ogni tipo, una frase diversa". La funzione `when()` sceglie le parole giuste per i giorni: "tra 6
giorni", "domani", "oggi".

### Un componente dentro un'altra pagina

`AlertsPanel` è un componente a sé, come il grafico del passo 1. Il Riepilogo lo usa con una riga,
`<app-alerts-panel />`, e il pannello carica da solo i suoi dati in `ngOnInit`. Vantaggi:
- il Riepilogo non deve sapere niente degli avvisi;
- domani lo stesso pannello si può mettere in un'altra pagina con una riga;
- ha i suoi test, separati da quelli del Riepilogo, che usa un `alerts: () => of([])` finto.

Se gli avvisi non arrivano (backend spento), il pannello non mostra niente e non dà errori: il resto
della pagina segnala già il problema.

### Il ricollegamento: parametri nell'indirizzo, ancora

Il link "Ricollega ING" porta a `/collega?bank=ING&country=IT`. La pagina **Collega una banca** li legge
all'avvio, come faceva la pagina Regole nel passo 2, e con le stesse precauzioni (un indirizzo lo può
scrivere chiunque):
- il **paese** viene accettato solo se è nell'elenco dei paesi della pagina;
- il nome della **banca** diventa solo il **testo della ricerca**: non avvia niente e non salva niente.
  Il collegamento parte solo quando premi **Collega**, come sempre.

In più compare un messaggio che spiega che conti e movimenti restano.

### I test

| Test | Cosa verifica |
|---|---|
| `AlertsPanel` (3) | niente pannello senza avvisi; la frase giusta per ognuno dei quattro tipi, rosso per lo scaduto, data in italiano, link "Ricollega" con banca e paese, link ai budget; "domani" e "oggi" |
| `LinkPage` (+2) | dall'avviso: paese e banca già scelti, messaggio sul rinnovo; un paese sconosciuto viene ignorato |

---

## Parte D: provalo

1. `git pull`, riavvia il backend e la dashboard.
2. Apri il **Riepilogo**. Se non vedi avvisi, è una buona notizia: nessun consenso scade nei prossimi 14
   giorni e tutte le sincronizzazioni sono andate bene.
3. Nella pagina **Conti** trovi la scadenza di ogni collegamento: quando mancheranno meno di 14 giorni,
   l'avviso comparirà da solo.
4. Per vederne uno subito: crea un budget piccolo (ad esempio 1 € per una categoria che hai usato questo
   mese) e torna al Riepilogo. Poi eliminalo.

Test:

```
cd backend;  .\mvnw.cmd test            # 101 test
cd frontend; npm test -- --watch=false  # 52 test
```

### Esercizi

1. Cambia `CONSENT_WARNING_DAYS` da 14 a 30. Quale test devi aggiornare? Prova prima a indovinarlo.
2. Aggiungi un avviso `TO_REVIEW`: "Hai N movimenti da verificare" con il link ai Movimenti (suggerimento:
   un nuovo valore in `AlertKind`, un `count` nella query, un `@case` nel pannello). Poi chiediti: serve
   davvero, visto che il Riepilogo lo dice già sopra il grafico? Due avvisi per la stessa cosa aiutano o
   confondono?
3. Perché l'avviso "aggiornamento non riuscito" mostra il motivo così com'è arriva dal backend, invece di
   una frase scritta dalla dashboard come gli altri?
