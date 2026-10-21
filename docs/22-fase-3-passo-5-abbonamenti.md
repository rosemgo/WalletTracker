# 22. Fase 3, passo 5: gli abbonamenti ricorrenti

**Obiettivo:** sapere quanto spendi in abbonamenti (Netflix, Spotify, palestra, assicurazioni...) e
accorgerti dei rincari e dei pagamenti spariti. Nuova pagina **Abbonamenti**:
1. **suggerimenti**: WalletTracker trova le spese che si ripetono ogni mese o ogni anno con importi simili,
   e te le propone. Per ognuna scegli **"È un abbonamento"** (con il nome che preferisci) oppure **"no,
   non suggerirlo più"**;
2. **gli abbonamenti confermati**: importo attuale, costo al mese, ultimo e prossimo pagamento, e il
   **totale al mese e all'anno**;
3. **"prezzo cambiato: prima 12,99 €"** se l'ultimo pagamento è diverso da quello al momento della
   conferma;
4. **"nessun pagamento recente: forse l'hai disdetto?"** se il pagamento previsto non arriva. In quel caso
   l'abbonamento non conta nel totale.

In più, su un telefono il menu in alto ora **va a capo** invece di uscire dallo schermo.

> Prerequisiti: passo 4 completato e `git pull` fatto. Al primo avvio Flyway applica la migrazione
> **V11**.

---

## Parte A: i concetti

### 1. Suggerire, non indovinare

È il principio di tutto il progetto: **il motore non deve indovinare** (ricordi la carta di credito,
`docs/10`). Riconoscere un abbonamento è un'ipotesi: un bonifico mensile a una persona può essere l'affitto
del box (ricorrente, ma è un abbonamento?), il supermercato c'è ogni mese ma non lo è. Per questo:
- il programma **propone**, l'utente **decide**;
- gli abbonamenti **non cambiano niente** nella classificazione: tipi, categorie, riepilogo e budget
  restano come sono. Sono solo un altro modo di guardare le spese;
- **in caso di dubbio, la risposta è "no"**. Un suggerimento sbagliato fa perdere fiducia; uno mancato
  non fa danni, perché il movimento resta comunque nei Movimenti.

### 2. Lo stesso esercente in mesi diversi: la "chiave"

Per capire che due pagamenti vanno allo stesso esercente bisogna confrontare i testi. Ma i testi cambiano
ogni volta:

```
Pagamento carta del 03/09/2026 alle 10:12 presso NETFLIX.COM 4521
Pagamento carta del 03/10/2026 alle 09:58 presso NETFLIX.COM 4521
```

La **chiave** (`MerchantKey`) tiene solo quello che non cambia: tutto in maiuscolo, e via punteggiatura e
parole con cifre. Così spariscono date, ore, numeri e codici d'ordine, e i due testi diventano uguali
(la regola è stata rivista nel passo 7a, migrazione V16: vedi `docs/26`, sezione 6):

```
PAGAMENTO CARTA DEL ALLE PRESSO NETFLIX COM
```

Se il movimento ha la **controparte** (ad esempio "Spotify AB"), si usa quella, che è più pulita, purché non
sia il titolare del conto: alcune banche mettono lì il tuo nome e il negozio nella causale. Le
lettere accentate restano ("CAFFÈ CITTÀ"): l'espressione regolare usa `\p{L}`, "qualsiasi lettera di
qualsiasi alfabeto", e non `[A-Z]`, che conosce solo le 26 lettere inglesi.

### 3. Quando una serie di pagamenti è un abbonamento

`RecurringDetector` risponde sì solo se valgono **tutte** queste condizioni:

| Condizione | Mensile | Annuale | Perché |
|---|---|---|---|
| **abbastanza pagamenti** | almeno 3 | almeno 2 | con 2 pagamenti a un mese di distanza potrebbe essere un caso |
| **intervalli regolari**: l'intervallo tipico è nella fascia, e almeno 3 intervalli su 4 lo sono | 26–35 giorni | 350–380 giorni | un mese saltato non basta a escluderlo |
| **importi simili**: almeno 3 pagamenti su 4 entro il 20% dell'importo tipico | ✓ | ✓ | esclude il supermercato; un rincaro da 12,99 a 13,99 invece passa |
| **ancora attivo**: l'ultimo pagamento è di al massimo un periodo e mezzo fa | 45 giorni | 547 giorni | un abbonamento disdetto mesi fa non serve suggerirlo |

**L'importo e l'intervallo "tipici" sono la mediana**, non la media. La mediana è il valore che sta in
mezzo una volta messi in ordine: per gli intervalli 29, 30, 31 e 300 giorni (un mese saltato a lungo)
la media sarebbe 97, la mediana è 30. Un valore strano non la sposta.

Si guarda agli **ultimi 13 mesi** (400 giorni): così un abbonamento annuale si vede due volte.

### 4. Le decisioni si ricordano

La tabella `subscription` (V11) non contiene gli abbonamenti "trovati", che si ricalcolano ogni volta
come gli avvisi del passo 4. Contiene le **tue decisioni**:

| `status` | Significato | Effetto |
|---|---|---|
| `CONFIRMED` | "sì, è un abbonamento" | compare nell'elenco, con nome, frequenza e importo al momento della conferma |
| `DISMISSED` | "no, non suggerirlo più" | non viene più suggerito |

Ogni decisione si può **annullare**: "togli" per un abbonamento, "suggeriscilo di nuovo" per un esercente
ignorato (in fondo alla pagina).

Il database garantisce due cose:
- `UNIQUE (merchant_key, currency)`: una sola decisione per esercente. Decidere due volte → **409
  Conflict**. Dalla V21 la decisione è per esercente **e importo** (sezione 6);
- `CHECK (status = 'DISMISSED' OR (name IS NOT NULL AND frequency IS NOT NULL AND expected_amount > 0))`:
  un abbonamento confermato ha sempre nome, frequenza e importo. È un vincolo "condizionale": vale solo
  per le righe `CONFIRMED`.

### 5. Rincari e disdette

Per ogni abbonamento confermato il backend cerca l'**ultimo pagamento** tra i movimenti:
- il **costo** si calcola dall'importo dell'ultimo pagamento, non da quello della conferma, perché conta il
  prezzo di oggi;
- se l'ultimo importo è **diverso** da quello della conferma → "prezzo cambiato";
- il **prossimo pagamento** previsto è l'ultimo più un mese (o un anno). Se oggi è passato da più di 7
  giorni (30 per un annuale) → "forse l'hai disdetto?". Il margine serve perché le banche contabilizzano
  con qualche giorno di ritardo. Un abbonamento in questo stato **non conta nel totale**.

"Forse disdetto" può avere anche un'altra causa: hai cambiato la carta con cui paghi, e il nuovo conto
non è collegato. Oppure la banca ha cambiato il testo dei movimenti, e la chiave non coincide più. In quel
caso togli l'abbonamento: tornerà tra i suggerimenti con la chiave nuova.

### 6. Più abbonamenti dallo stesso esercente (V21)

**Il caso vero.** Un abbonamento Fastweb mobile da 7,99 € al mese non veniva suggerito. Gli addebiti, con
il conto ING:

| Data | Importo | Che cos'è |
|---|---|---|
| 30/07 | 7,99 € | la SIM |
| 31/08 | 7,99 € | la SIM |
| 04/09 | 23,95 € | l'ultima bolletta della linea di casa (pagata una volta) |
| 30/09 | 7,99 € | la SIM |

La causale è un addebito SDD: "Addebito SDD CORE Scad. 30/09/2026 Imp. 7.99 Creditor id. ... FASTWEB S.p.A.
Id Mandato ...". La chiave (sezione 2) toglie date, importi e codici con cifre, e si ferma a 60 caratteri: tutti
gli addebiti Fastweb hanno **la stessa chiave**. Per il programma erano una serie sola, con un intervallo di 4
giorni tra il 31/08 e il 04/09: 2 intervalli regolari su 3, meno dei 3 su 4 richiesti. Niente abbonamento.

**La soluzione, in due parti.**

1. **Riconoscere** (`RecurringDetector.detectAll`): prima la serie intera, come sempre (così un rincaro da 12,99 a
   13,99 resta un abbonamento solo). Se non lo è, la serie si divide in **gruppi di importi simili**
   (`amountGroups`: in ordine di importo, un pagamento entra nel gruppo se è entro il 20% del più piccolo) e ogni
   gruppo si guarda da solo. {7,99 7,99 7,99} è un abbonamento; {23,95} no.
2. **Decidere** per esercente **e importo**. Prima un abbonamento era "esercente + valuta": uno solo per
   esercente. Con due contratti dallo stesso creditore (SIM e linea di casa) se ne poteva confermare uno solo. La
   **V21** cambia il vincolo:

   ```sql
   ALTER TABLE subscription DROP CONSTRAINT uq_subscription_key_currency;
   ALTER TABLE subscription ADD CONSTRAINT uq_subscription_key_currency_amount
       UNIQUE (merchant_key, currency, expected_amount);
   ```

   e anche un "no" ora salva l'importo: "no" alla bolletta non nasconde la SIM.

**Quando un importo è "della stessa serie" di una decisione?** Entro il 50% (`SubscriptionService.SAME_SERIES`).
È più largo del 20% con cui si riconosce una serie, per un motivo: un abbonamento confermato può rincarare
(12,99 → 15,99 è il 23%) e deve restare lui, con l'avviso del rincaro. Due contratti diversi di solito
differiscono molto di più (7,99 e 23,95: il triplo). Una regola così ("entro il 50%") non si scrive con un vincolo
del database: la controlla il codice (`Subscription.covers`), e il vincolo nuovo impedisce solo i doppioni esatti.

**I pagamenti di ogni abbonamento** (`SubscriptionService.paymentsOf`): quelli dell'esercente con un importo della
stessa serie; se l'esercente ha più abbonamenti confermati, ogni pagamento va a quello con l'importo più vicino.
Così l'"ultimo pagamento" della SIM è 7,99 €, non la bolletta da 23,95 €.

**E i "no" di prima?** Le righe `DISMISSED` salvate prima della V21 non hanno l'importo (`expected_amount` è
NULL): valgono per **tutti** gli importi, come prima. Nella pagina compaiono come "tutti gli importi".

Un dettaglio sul rimborso: Fastweb ha anche restituito 1,22 € con un bonifico. Non disturba: gli abbonamenti
guardano solo le **uscite**, e il bonifico ha comunque un'altra chiave ("BONIFICO N BIC ORDINANTE...").

---

## Parte B: il backend

| File | Cosa fa |
|---|---|
| `db/migration/V11__abbonamenti.sql` | la tabella delle decisioni, con i vincoli |
| `subscription/MerchantKey.java` | la chiave dell'esercente e il nome da proporre |
| `subscription/RecurringDetector.java` | il riconoscimento: classe **pura**, senza Spring né database |
| `subscription/Frequency.java` | `MONTHLY` e `YEARLY`, con le loro fasce di giorni |
| `subscription/Subscription.java`, `SubscriptionRepository.java` | la decisione salvata |
| `subscription/SubscriptionService.java` | raggruppa le spese, chiama il riconoscimento, calcola costi, rincari e ritardi |
| `subscription/SubscriptionController.java`, `SubscriptionDtos.java` | le API e i loro record |

### Le API

```
GET    /api/subscriptions              {subscriptions: [...], totals: [...], dismissed: [...]}
GET    /api/subscriptions/suggestions  [{merchantKey, name, frequency, typicalAmount, occurrences, ...}]
POST   /api/subscriptions              {merchantKey, currency, name, frequency, expectedAmount}  → 201
POST   /api/subscriptions/dismissed    {merchantKey, currency}                                   → 201
DELETE /api/subscriptions/{id}                                                                    → 204
```

La `merchantKey` arriva dal browser, quindi va **validata**:
`@Pattern(regexp = "[\\p{L} ]{2,60}")` accetta solo lettere e spazi, da 2 a 60 caratteri, cioè
esattamente la forma che produce `MerchantKey`. Un `<script>` riceve 400.

### Una classe pura: perché conviene

`RecurringDetector.detect(payments, today)` riceve una lista di pagamenti e "oggi", e restituisce un
risultato. Non legge il database, non usa Spring, non guarda l'orologio. Per questo:
- i suoi **9 test girano in 0,2 secondi**, contro i ~20 dei test con Spring e PostgreSQL. Così possiamo
  provare tanti casi: rincaro, mese saltato, supermercato, intervalli irregolari, annuale, disdetto...;
- **si legge da sola**: tutta la logica "che cos'è un abbonamento" sta in una pagina;
- `SubscriptionService` fa il resto: legge i movimenti, li raggruppa, salva le decisioni.

È una divisione comune nei progetti veri: la **logica** in classi pure, facili da provare; il
**collegamento** con database e web in classi sottili intorno.

### Un `Optional` per "forse"

```java
public static Optional<Pattern> detect(List<Payment> payments, LocalDate today)
```

`Optional<Pattern>` dice già dal tipo che la risposta può mancare: o un `Pattern` (è un abbonamento,
ecco come si ripete) o `Optional.empty()` (non lo è). Chi chiama non può dimenticarsene, come succede
con un `null`:

```java
RecurringDetector.detect(group.payments(), today).ifPresent(pattern -> suggestions.add(...));
```

### I test

| Test | Cosa verifica |
|---|---|
| `RecurringDetectorTest` (9, puri) | mensile regolare con il prossimo pagamento; ordine dei pagamenti indifferente; rincaro e mese saltato; supermercato no; intervalli irregolari no; 2 pagamenti mensili non bastano; annuale con 2 pagamenti; disdetto da mesi no; la mediana |
| `MerchantKeyTest` (6, puri) | date e numeri spariscono; controparte preferita; lettere accentate; niente chiave senza testo utile; testi lunghi tagliati; il nome proposto senza date |
| `SubscriptionControllerTest` (5) | Netflix suggerito e supermercato no; conferma → elenco con rincaro, costo, prossima data, totali, e non più suggerito; senza pagamenti recenti → in ritardo e fuori dal totale; "non suggerire più", 409 per la doppia decisione, annullamento; richieste non valide → 400 |

---

## Parte C: il frontend

| File | Cosa fa |
|---|---|
| `subscriptions/subscriptions-page.ts` / `.html` | totali, elenco, suggerimenti, esercenti ignorati |
| `api/models.ts`, `api/wallet-api.ts` | i tipi e i cinque metodi delle API |
| `app.routes.ts`, `app.html` | la pagina `/abbonamenti`, nel menu dopo Budget |
| `styles.css` | suggerimenti; menu che va a capo |

Alcuni dettagli da notare:

- **Il nome di un suggerimento è modificabile.** Il nome proposto è il testo dell'ultimo pagamento senza
  date e numeri: per Spotify "Spotify AB" va bene, per Netflix su ING è "Pagamento carta del alle presso
  NETFLIX.COM". Scrivi "Netflix" prima di confermare. Il campo usa ancora una **variabile del template**
  (`#nameInput`, come nel passo 2): il pulsante legge `nameInput.value`. Un nome vuoto mantiene quello
  proposto.
- **`<details>` e `<summary>`**: gli esercenti ignorati stanno in un elemento HTML che si apre e si chiude
  da solo, senza una riga di TypeScript. Restano a portata di mano senza occupare spazio.
- **Il menu che va a capo**: `flex-wrap: wrap` su `header` e `nav`. Con 8 voci, su un telefono la barra
  usciva dallo schermo e allargava tutta la pagina. Ora le voci vanno sulla riga sotto. È il problema
  segnalato nei passi precedenti.

### I test

| Test | Cosa verifica |
|---|---|
| `SubscriptionsPage` (4) | totale, elenco con rincaro, prossimo pagamento e abbonamento "forse disdetto" in grigio; conferma con il nome scritto (senza spazi) e ricarica; nome vuoto → nome proposto; "non suggerire più" e "suggeriscilo di nuovo" |

---

## Parte D: provalo

1. `git pull`, riavvia il backend (Flyway applica la V11) e la dashboard.
2. Pagina **Abbonamenti**: guarda i suggerimenti. Per ognuno scegli "È un abbonamento" (correggi il
   nome) oppure "no, non suggerirlo più".
3. Guarda il totale: è quello che ti aspettavi? Spesso è più alto di quanto si pensi.
4. Se un abbonamento che hai non viene suggerito, controlla nei Movimenti: forse ha meno di 3 pagamenti
   negli ultimi 13 mesi, oppure gli importi cambiano troppo (oltre il 20%). Due addebiti diversi dallo stesso
   creditore non sono più un problema (sezione 6).

Test:

```
cd backend;  .\mvnw.cmd test            # 121 test
cd frontend; npm test -- --watch=false  # 56 test
```

### Esercizi

1. Aggiungi la frequenza **settimanale** (6–8 giorni) in `Frequency`. Che cosa devi cambiare in
   `RecurringDetector`? Scrivi prima il test. Poi pensaci: quali spese settimanali potrebbero diventare
   falsi abbonamenti?
2. Aggiungi un avviso (passo 4) "abbonamento forse disdetto" per gli abbonamenti con `late = true`.
   Quale classe del backend deve chiamare quale?
3. Nel test `theSupermarketEveryMonthIsNotASubscription`, cambia gli importi finché il supermercato
   diventa un "abbonamento". Di quanto devono essere simili? Ti sembra la soglia giusta?
