# Passaggio di consegne: rivedere la sezione Investimenti

File per una sessione nuova, dedicata a **ripensare che cosa mostra la sezione Investimenti** (la scheda "Investito"
del Riepilogo e la pagina `/investimenti`). Contiene: che cosa c'è oggi e come è calcolato, che cosa non convince
l'utente, l'analisi dei casi, le domande da fare all'utente prima di scrivere codice e un piano di lavoro.

**Non scrivere codice prima di aver chiarito con l'utente le domande della sezione 6.** L'utente vuole capire bene
il ragionamento, non solo vedere il risultato.

---

## 0. Da leggere prima

| File | Perché |
|---|---|
| `docs/investimenti.md` | la guida di quello che c'è oggi (parti A–G) |
| `docs/consegne-categorie-investimenti.md` | discussione collegata: categorie dei movimenti di investimento e cashback di Tinaba |
| `docs/tinaba.md` | il conto Tinaba: CSV senza segno, regole `V31` |
| `docs/03-modello-e-regole.md`, `docs/07-fase-1-passo-4-classificazione.md` | tipi dei movimenti e classificazione |
| `docs/fase-4-consegne.md`, sezione 6 | come far girare Docker, backend, frontend e Playwright nel cloud |

## 1. Regole che restano

- L'utente scrive in italiano, conosce bene Java; vuole guide passo passo in italiano in `docs/`, test, e una prova
  vera nel browser (Playwright) per tutto ciò che si vede. Interfaccia pensata prima per il telefono.
- **Niente dati personali** nel repository (guide, test, commenti): solo dati inventati (Banca Alfa, ACME SRL,
  MARIO ROSSI, IBAN `IT00X…`). I file che l'utente carica (CSV, PDF, screenshot) contengono dati veri: non copiarli.
- Non modificare migrazioni già applicate. Se serve una migrazione, prima di scegliere il numero guardare **tutti**
  i rami remoti (`git ls-tree -r --name-only origin/<ramo> -- backend/src/main/resources/db/migration`): la sessione
  della Fase 4 ne aggiunge in parallelo. Oggi l'ultima è `V31`.
- Non aprire PR se l'utente non lo chiede; commit con le righe di attribuzione richieste dall'ambiente.
- Con la Fase 4 l'app ha il PIN: nei test MockMvc l'autenticazione è automatica (`IntegrationTestConfiguration`);
  nel browser e con `curl` bisogna entrare con il PIN di sviluppo (`docs/46`).

---

## 2. Che cosa c'è oggi

### Il calcolo (backend, `backend/src/main/java/it/wallettracker/investment/`)

- `Investments.java`: funzioni pure, provate da `InvestmentsTest`.
  - `contribution(m)`, il **"versato"**: per i movimenti dei conti **non** di investimento con tipo
    `INVESTMENT_DEPOSIT`, `INVESTMENT_WITHDRAWAL`, `SECURITIES_BUY` o `SECURITIES_SELL`, vale `-importo` (un'uscita
    di 200 € verso gli investimenti è +200). Sui conti con ruolo `INVESTMENT` vale sempre 0.
  - `income(m)`: l'importo dei `INVESTMENT_INCOME` (cedole, dividendi, interessi).
  - `taxes(m)`: l'importo degli `INVESTMENT_TAX`.
  - `months(...)`: per mese versato, cumulato (che parte da tutta la storia precedente), rendite, tasse.
  - `incomeBy(...)`: le rendite raggruppate per categoria o per titolo.
- `InvestmentService.overview(period, currency, today)`: legge `BankTransactionRepository.findInvestmentMovements()`
  (solo i sei tipi di investimento, di tutti i conti) e costruisce `InvestmentDtos.Overview`.
- `InvestmentController`: `GET /api/investments?period=YEAR|TWO_YEARS|ALL&currency=EUR`.

**Perché fu scelto così** (commento di `Investments` e `docs/investimenti.md`, parte B): 200 € che vanno da ING a un
broker e poi in un ETF compaiono tre volte (uscita da ING, entrata sul broker, acquisto). Contando solo i conti
normali si conta una volta sola, e funziona anche per i conti che non si possono collegare (si vede solo l'uscita
da ING).

### Come nascono i tipi

- **Giroconti** (`ClassificationService.transferType`): da un conto normale a un conto `INVESTMENT` →
  `INVESTMENT_DEPOSIT` (su **tutte e due** le metà); al contrario → `INVESTMENT_WITHDRAWAL`; tra due conti di
  investimento → `INTERNAL_TRANSFER`.
- **Acquisti e vendite di titoli**, dalle regole predefinite:

  | Fonte | Testo | Tipo | Migrazione |
  |---|---|---|---|
  | dossier titoli della banca (conto corrente) | `Compravendita Titoli` | `SECURITIES_BUY` / `SECURITIES_SELL` secondo il segno | `V4` |
  | Trade Republic | `Buy trade` / `Sell trade` | `SECURITIES_BUY` / `SECURITIES_SELL` | `V26` |
  | Tinaba | `versamento Robo advisor` | `SECURITIES_BUY` | `V31` |

- **Rendite e tasse**: `V4` (cedole, dividendi, ritenute, bollo del dossier), `V25`/`V26` (Trade Republic).

### Che cosa si vede (frontend)

- `frontend/src/app/investments/investments-page.{ts,html}`: tre pulsanti di periodo (12 mesi, 2 anni, Tutto).
  - Scheda principale: "Versato in totale" (o "Versato fino a <mese>" quando si scorre il grafico), confronto,
    grafico a linea del **cumulato** (`trend-chart.ts`), e sotto la frase **"Sono i soldi che hai messo negli
    investimenti, meno quelli che hai ripreso: non quanto valgono oggi."**
  - Seconda scheda: rendite del periodo, tasse, grafico a barre mensile, elenchi "Per tipo" e "Da dove".
  - "Gli ultimi versamenti": i movimenti con `contribution != 0`.
  - Stato vuoto: come far riconoscere i versamenti (ruolo del conto, regola, "Conta come").
- `frontend/src/app/investments/investments-card.ts`: la scheda "Investito" del Riepilogo, con "Versato
  quest'anno", "Rendite quest'anno" e una piccola linea del cumulato a 12 mesi.
- Test: `InvestmentsTest`, `InvestmentServiceTest` (backend); `investments.spec.ts` (frontend).
- Modello TS: `frontend/src/app/api/models.ts` (`Investments`, `InvestmentMonth`…). Service worker:
  `/api/investments**` è in `ngsw-config.json`.

---

## 3. Che cosa non convince l'utente (sue parole, riassunte)

1. La frase sotto il grafico principale è **sbagliata nel concetto**: i soldi "messi negli investimenti" non sono i
   bonifici verso un conto di investimento, ma gli **acquisti** veri (titoli, bond, ETF…). Allo stesso modo, ciò
   che esce da un investimento è una **vendita**, una cedola o un dividendo.
2. Il grafico principale somma i **bonifici verso i conti di investimento**. Se era questa l'intenzione, andava
   detto chiaramente. Un grafico così può restare, ma **secondario**, e con un'etichetta che dica che parla solo dei
   bonifici verso i conti di investimento.
3. **Da ragionare bene:** i soldi arrivati su un conto di investimento possono poi essere usati per altro:
   prelevati, spesi con la carta (Trade Republic ha una carta), mandati con un bonifico a un altro conto. In quei
   casi vanno tolti dal "versato"? Oggi no (vedi la tabella dei casi).

---

## 4. Analisi: sono tre grandezze diverse, non una

Oggi la pagina mescola tre cose sotto la parola "versato". Conviene separarle e chiamarle con il loro nome.

| Grandezza | Che domanda risolve | Come si calcola (proposta) |
|---|---|---|
| **A. Bonifici netti verso i conti di investimento** | quanti soldi ho spostato dai conti di tutti i giorni verso broker e conti titoli? | sui conti normali: −importo di `INVESTMENT_DEPOSIT` e `INVESTMENT_WITHDRAWAL` (è quasi il calcolo di oggi, **senza** gli acquisti e le vendite) |
| **B. Investito netto in titoli** | quanto ho comprato di titoli, meno quanto ho venduto? | su **tutti** i conti: −importo di `SECURITIES_BUY` e `SECURITIES_SELL` (un acquisto di −200 è +200; una vendita di +150 è −150) |
| **C. Liquidità ferma sui conti di investimento** | quanto è arrivato sui conti di investimento e non è (più) investito? | la somma di **tutti** i movimenti dei conti `INVESTMENT`, compresi acquisti, vendite, rendite, tasse, spese con la carta e prelievi. In pratica è il **saldo** di quei conti; quando la banca dà il saldo si può prendere quello |

Le rendite (cedole, dividendi, interessi) e le tasse restano come oggi, a parte.

### I casi, uno per uno

Ogni riga è una storia inventata; le colonne dicono come cambiano A, B, C e il "versato" di oggi.

| # | Storia | Oggi | A | B | C |
|---|---|---|---|---|---|
| 1 | bonifico di 200 da ING al broker, che resta lì in liquidità | +200 | +200 | 0 | +200 |
| 2 | poi il broker compra un ETF da 200 | 0 | 0 | +200 | −200 |
| 3 | acquisto di 200 nel dossier titoli **dentro** il conto corrente ING | +200 | 0 | +200 | 0 |
| 4 | vendita di titoli per 150 sul broker, i soldi restano lì | 0 | 0 | −150 | +150 |
| 5 | poi bonifico di 150 dal broker a ING | −150 | −150 | 0 | −150 |
| 6 | dividendo di 10 sul broker | 0 (va nelle rendite) | 0 | 0 | +10 |
| 7 | spesa di 30 con la carta del broker (Trade Republic) | **0** | 0 | 0 | −30 |
| 8 | prelievo di 50 al bancomat dal conto del broker | **0** | 0 | 0 | −50 |
| 9 | bonifico di 200 dal broker a un **altro** conto di investimento | 0 | 0 | 0 | 0 (si sposta tra i due) |
| 10 | Tinaba: bonifico da ING (200), poi "Nuovo versamento Robo advisor" (200) | +200 | +200 | +200 | 0 |
| 11 | un PAC su un broker che **non** si collega e di cui non si importa nulla | +200 al mese | +200 | **0: gli acquisti non si vedono** | non si sa |

Che cosa si impara:

- I casi **7 e 8** sono il dubbio dell'utente: con il calcolo di oggi quei soldi restano "versati" per sempre.
  - Con **A** restano anche loro: A dice solo "quanto ho spostato verso i conti di investimento", ed è giusto così
    **se l'etichetta lo dice**.
  - Con **B** non c'entrano: non sono acquisti.
  - Con **C** si vedono: la liquidità scende.
  - Domanda da fare: la spesa con la carta del broker deve sottrarsi da qualcosa nella pagina Investimenti, o basta
    che sia una spesa normale (lo è già: `EXPENSE` su un conto `INVESTMENT`)?
- Il caso **3** mostra perché B va calcolata su **tutti** i conti: l'acquisto nel dossier di ING è un investimento
  vero anche se il conto non è di investimento.
- Il caso **11** è il limite di B: dove gli acquisti non si vedono, B li perde. Per quei conti A è l'unica
  informazione (vedi la sezione 5).
- Il caso **10** mostra che per Tinaba B funziona già: la regola `V31` fa del "versamento Robo advisor" un
  `SECURITIES_BUY`. Non sono i singoli ETF comprati dal robo, ma è il momento in cui i soldi entrano nel
  portafoglio, e per l'utente è "l'acquisto".

### Le vendite non sono "entrate" come le cedole

L'utente dice: "un'entrata di un investimento è quando vendo, o quando ricevo una cedola o un dividendo". Va
chiarito nella guida, con delicatezza:

- una **cedola** o un **dividendo** è un guadagno: soldi in più;
- una **vendita** restituisce soprattutto il **capitale** che c'era dentro, più (o meno) un guadagno. Quanto è
  guadagno l'app non lo sa: servirebbe il prezzo di carico di ogni titolo, che i movimenti bancari non danno.
  Indizio parziale: la tassa sul capital gain (`INVESTMENT_TAX`, categoria "Capital gain") dice che c'è stata una
  plusvalenza, ma non quanto (è il 26% o il 12,5%, secondo il titolo).
- Proposta: le vendite stanno in B, che **scende**, e magari in un elenco "Vendite" accanto agli acquisti; **non**
  si sommano alle rendite.

---

## 5. Da dove arrivano i dati: per ogni fonte, che cosa si vede

| Fonte | Bonifici (A) | Acquisti e vendite (B) | Saldo o tutti i movimenti (C) |
|---|---|---|---|
| conto corrente con dossier titoli (Compravendita Titoli) | — | sì | — |
| Trade Republic (collegato) | sì | sì (`Buy trade` / `Sell trade`). **Da verificare:** le esecuzioni dei piani di accumulo hanno un testo diverso? Nessuna regola oggi le copre | sì |
| Tinaba (CSV) | sì | sì, il "versamento Robo advisor" | sì, il conto ha tutti i movimenti; il PDF mensile ha il saldo |
| broker non collegato e nessun file | sì, dal lato del conto corrente | no | no |

Conseguenza da discutere con l'utente: se il grafico principale diventa B, un conto come l'ultima riga sparisce dal
grafico. Le strade:

1. B soltanto, e un avviso: "Per <conto> si vedono solo i bonifici: importa i suoi movimenti per vedere gli
   acquisti".
2. B, più A per i soli conti di cui non si vede nessun acquisto (rischio: mescolare di nuovo due grandezze).
3. B come grafico principale, A come grafico secondario, sempre tutti e due, con etichette chiare. È la proposta
   più semplice da capire.

---

## 6. Domande da fare all'utente prima di scrivere codice

Con `AskUserQuestion`, una alla volta o in gruppo, spiegando il caso concreto (la tabella della sezione 4 aiuta):

1. **Grafico principale:** "Investito netto in titoli" (B, acquisti meno vendite)? Proposta: sì.
2. **Grafico secondario:** "Bonifici verso i conti di investimento" (A), con l'etichetta che dica proprio questo?
   Lo vuole cumulato (linea) o mese per mese (barre)?
3. **Liquidità (C):** la vuole vedere ("non investito sui conti di investimento")? Se sì, dal saldo della banca o
   calcolata dai movimenti? Calcolata è esatta solo se i movimenti del conto ci sono tutti dall'apertura.
4. **Spese e prelievi dai conti di investimento** (casi 7 e 8): basta che siano spese normali, o li vuole vedere
   anche nella pagina Investimenti?
5. **Vendite:** un elenco "Vendite" (con titolo e data) accanto alle rendite, tenuto separato?
6. **Conti di cui si vedono solo i bonifici** (sezione 5): quale delle tre strade?
7. **Scheda del Riepilogo:** "Investito quest'anno" (B) e "Rendite quest'anno"? Oppure altro?
8. **Cashback di Tinaba:** è la domanda aperta di `docs/consegne-categorie-investimenti.md`; si decide insieme,
   perché tocca le rendite di questa pagina.

---

## 7. Piano di lavoro (dopo le risposte)

### Backend

1. `Investments`: al posto di `contribution` tre misure, ognuna con il suo test in `InvestmentsTest`:
   - `transfers(m)`: A;
   - `invested(m)`: B (su tutti i conti; tipi `SECURITIES_BUY` e `SECURITIES_SELL`);
   - se serve, `cash(m)`: C (tutti i movimenti dei conti `INVESTMENT`, tranne i tipi `IGNORED` e `TO_REVIEW`).
   Rifare la tabella della sezione 4 come test, un caso per riga: è il modo migliore per non sbagliare i segni.
2. `findInvestmentMovements()` legge solo i sei tipi di investimento. Per C servono **tutti** i movimenti dei
   conti `INVESTMENT`: una seconda query (`WHERE t.account.role = INVESTMENT`), oppure il saldo del conto se c'è.
3. `InvestmentDtos`: `Month` e `Overview` con nomi che dicano la verità (`invested`, `investedCumulative`,
   `transfers`, `transfersCumulative`…), più eventualmente `sales` e `recentPurchases`. API e frontend sono nello
   stesso repository: si cambiano insieme, nessuna compatibilità da tenere.
4. `InvestmentServiceTest`: il caso della carta del broker (7) e del dossier (3) con dati inventati.
5. Attenzione:
   - un conto con il ruolo `EXCLUDED` non deve contare: i suoi movimenti sono già `IGNORED`, ma una query per
     ruolo (punto 2) deve escluderli lo stesso;
   - i movimenti `PENDING`: oggi contano; decidere se va bene;
   - le valute: oggi si sceglie una valuta e si filtra; resta così.

### Frontend

1. `investments-page`: grafico principale B con la sua frase vera ("Acquisti di titoli meno vendite. Non è quanto
   valgono oggi: per quello servirebbero le quotazioni."), grafico secondario A con l'etichetta "Bonifici verso i
   conti di investimento (meno quelli tornati indietro)", eventuale C.
2. "Gli ultimi versamenti" diventa "Gli ultimi acquisti e vendite" (e/o un elenco separato per i bonifici).
3. Stato vuoto: spiegare anche come far riconoscere gli **acquisti** (regole, tipo "Acquisto titoli").
4. `investments-card.ts`: le cifre scelte nella domanda 7.
5. `investments.spec.ts` aggiornato. Il grafico a linea (`trend-chart.ts`) e quello a barre (`bar-chart`) si
   riusano come sono.
6. Prova nel browser con dati inventati: un conto corrente, un broker con ruolo Investimenti, bonifici, acquisti,
   una vendita, un dividendo, una spesa con la carta del broker. Controllare i numeri a mano contro la tabella.

### Guida

- Aggiornare `docs/investimenti.md`: le parti B, D ed E dicono "versato" nel senso vecchio. Meglio una parte
  nuova ("Parte I: tre grandezze, non una") con la tabella dei casi, e correggere le altre dove serve.
- Esercizi guidati (parte G) da rivedere.

### Probabilmente nessuna migrazione

A meno che non serva una regola nuova (per esempio i piani di accumulo di Trade Republic) o le categorie della
discussione sul cashback. In quel caso, numero libero su tutti i rami.

---

## 8. Stato del ramo al momento della consegna

Ramo `claude/quirky-brahmagupta-c4kk7j`. Commit principali della sezione:

- `75ba034`: Investimenti, scheda nel Riepilogo e pagina con versato e rendite;
- `032a742`: regole, la prova dice chi vince (parte H della guida);
- `b984b62`: Tinaba, modo "senza segno" e regole `V31`;
- `426458c`: consegna sulle categorie degli investimenti e sul cashback.

Test al momento della consegna: backend 375 (tutti verdi), frontend 226 (tutti verdi).

---

## 9. Decisioni e progetto (sessione successiva)

### Le risposte dell'utente

| Domanda (sezione 6) | Decisione |
|---|---|
| 1. grafico principale | **B, "Investito in titoli"**: acquisti meno vendite, su tutti i conti; linea del cumulato |
| 2. grafico secondario | **A, "Bonifici verso i conti di investimento"**, meno quelli tornati indietro; **barre** mese per mese |
| 3. liquidità (C) | **più avanti**, dopo i **saldi dei conti** (vedi sotto): mai calcolata dai movimenti, perché la storia non è mai completa |
| 4. spese e prelievi dai conti di investimento | **restano spese normali**: nella pagina Investimenti non compaiono e non toccano né B né A |
| 5. acquisti e vendite | oltre al netto, **separati**: "Acquistato" e "Rientrato dalle vendite" del periodo, ognuno con la sua cifra, e l'elenco dei movimenti; **mai** sommati alle rendite (che sono solo cedole, dividendi e interessi) |
| 6. conti di cui si vedono solo i bonifici | strada 3 (B e A sempre, con etichette chiare) **più** un avviso per quei conti |
| 7. scheda del Riepilogo | **"Investito in titoli quest'anno"** e **"Rendite quest'anno"**, con la piccola linea di B a 12 mesi |
| 8. cashback di Tinaba | deciso a parte: è un'**Entrata** in "Rimborsi fiscali e bonus" (`V33`), non entra in questa pagina |

Restano come oggi: i movimenti in attesa (`PENDING`) contano; si sceglie una valuta e si filtra; nessuna migrazione.

### Backend

**`Investments`**: `contribution` sparisce, al suo posto due misure con il nome giusto.

```java
/** B. Quanto il movimento aggiunge all'investito in titoli: un acquisto di -200 è +200, una vendita di +150 è -150. */
public static BigDecimal invested(Movement m) {
    return switch (m.type()) {
        case SECURITIES_BUY, SECURITIES_SELL -> m.amount().negate();   // su TUTTI i conti (caso 3: il dossier di ING)
        default -> BigDecimal.ZERO;
    };
}

/** A. Quanto il movimento aggiunge ai bonifici verso i conti di investimento. */
public static BigDecimal transfers(Movement m) {
    if (m.onInvestmentAccount()) {
        return BigDecimal.ZERO;                                        // l'altra metà dello stesso giroconto
    }
    return switch (m.type()) {
        case INVESTMENT_DEPOSIT, INVESTMENT_WITHDRAWAL -> m.amount().negate();
        default -> BigDecimal.ZERO;
    };
}
```

B non conta mai due volte: un acquisto è un movimento solo, su un conto solo. `income` e `taxes` restano come sono.

**`InvestmentDtos`**, nomi che dicono la verità (API e frontend si cambiano insieme):

- `Month(month, invested, investedCumulative, transfers, income, taxes)`;
- `Overview`: `investedTotal`, `investedThisYear`, `investedThisMonth`, `investedInPeriod`, `boughtInPeriod`,
  `soldInPeriod`, `transfersInPeriod`, `incomeThisYear`, `incomeInPeriod`, `taxesInPeriod`, `months`,
  `incomeByType`, `incomeBySource`, `recentTrades` (gli ultimi acquisti e vendite), `onlyTransfers` (l'avviso).

**L'avviso "si vedono solo i bonifici"** (`onlyTransfers`): i nomi dei conti con ruolo Investimenti che hanno
versamenti ma, in tutta la storia, nessun acquisto né vendita; più una voce "senza conto" se ci sono versamenti sui
conti normali che non si sono abbinati a nessun conto (classificati da una regola o a mano: il conto di arrivo non
è nell'app). Serve sapere se un movimento ha l'altra metà: `Movement` riceve anche `accountName` e `paired`.

**Test.** In `InvestmentsTest` la tabella della sezione 4 diventa un test con un caso per riga (colonne "B" e
"A"): è il modo per non sbagliare i segni. In `InvestmentServiceTest`, con dati inventati: il dossier di Banca Alfa
(caso 3), la carta del broker (caso 7: non cambia niente), un broker con solo bonifici (l'avviso).

### Frontend (prima per il telefono)

Dall'alto in basso, in `investments-page`:

1. **Investito in titoli** (scheda principale): la cifra (o "fino a <mese>" scorrendo), il confronto, la linea del
   cumulato (`trend-chart`), sotto "Acquisti 2.000 € · Vendite 150 €" del periodo, e la frase: *"Acquisti di
   titoli meno vendite. Non è quanto valgono oggi: per quello servirebbero le quotazioni."*
2. **Bonifici verso i conti di investimento**: il totale del periodo e le barre mese per mese (`bar-chart`), con la
   frase: *"Soldi spostati dai conti di tutti i giorni verso broker e wallet, meno quelli tornati indietro. Non
   dice quanti sono stati investiti."*
3. **L'avviso**, se `onlyTransfers` non è vuoto: *"Per <conto> si vedono solo i bonifici: importa i suoi
   movimenti per vedere gli acquisti."*
4. **Cedole, dividendi e interessi**: come oggi.
5. **Gli ultimi acquisti e vendite** e, a parte, **gli ultimi bonifici verso i conti di investimento** (quelli che
   fanno le barre del punto 2: all'utente piace vedere i movimenti dietro il grafico). Ogni elenco ha "Vedi tutti",
   che porta a Movimenti con il filtro del tipo.
6. **Stato vuoto**: spiega anche come far riconoscere gli acquisti (regole, tipo "Acquisto titoli").

`investments-card` (Riepilogo): "Investito in titoli quest'anno", "Rendite quest'anno", la linea di B a 12 mesi.
`investments.spec.ts` aggiornato; prova nel browser con Playwright e dati inventati (conto corrente, broker con
ruolo Investimenti, bonifici, acquisti, una vendita, un dividendo, una spesa con la carta del broker), controllando
i numeri a mano contro la tabella della sezione 4.

### Guida

Parte nuova in `docs/investimenti.md` ("Parte I: due grandezze, non una"), con la tabella dei casi; parti B, C, D,
E e gli esercizi della parte G corretti dove dicono "versato" nel senso vecchio.

### Piani di accumulo di Trade Republic

L'utente non ne ha: nessuna regola nuova. Se un giorno ne avrà, le esecuzioni andranno controllate (testo diverso
da "Buy trade"?).

### Dopo: i saldi dei conti (poi la liquidità, C)

Un passo a parte, dopo la sezione Investimenti, perché tocca conti, sincronizzazione e importazione:

- **conti collegati**: Enable Banking ha `GET /accounts/{uid}/balances` (`EnableBankingClient.getBalances`, oggi
  usato solo da `PocRunner`). La sincronizzazione lo legge e lo salva con la sua data; la pagina Conti lo mostra.
  Se per un conto la banca non lo dà, il saldo **non** si mostra (calcolarlo dai movimenti sarebbe sbagliato:
  la storia non è mai completa);
- **conti manuali** (CSV): dopo un'importazione, se il file non ha il saldo, l'app chiede "Qual è il saldo
  attuale?", mostrando l'ultimo movimento importato da confrontare con la banca. Da lì in avanti il saldo si
  aggiorna con i movimenti importati dopo quella data (esatto, perché parte da un valore vero);
- poi C: la liquidità dei conti di investimento è il loro saldo.
