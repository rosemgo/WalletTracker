# Il saldo dei conti

Fuori dalla numerazione, come `docs/investimenti.md` e `docs/tinaba.md`. Viene dalla consegna degli investimenti
(`docs/consegne-investimenti.md`, sezione 9): per mostrare la **liquidità** sui conti di investimento serve il saldo
vero dei conti, e fino a qui l'app non lo salvava.

| Parte | Cosa |
|---|---|
| A | il principio: un saldo non si calcola dai movimenti |
| B | i conti collegati: il saldo della banca |
| C | i conti manuali: il saldo scritto da te, e poi i movimenti |
| D | dove si vede: Conti, Importa, Investimenti |
| E | i test, e la prova nel browser |
| F | esercizi guidati |
| G | aggiunta: il saldo a mano anche per i conti collegati |
| H | aggiunta: la carta di credito non usa il saldo disponibile |
| I | aggiunta: il plafond disponibile della carta, a parte (`V35`) |
| J | aggiunta: una carta di credito ha solo il plafond, non un saldo |

---

## Parte A: il principio

Un saldo **non** si calcola sommando i movimenti dell'app. La storia di un conto nell'app non è mai completa:

- Enable Banking dà al massimo un anno di movimenti, spesso solo 90 giorni (`docs/08`);
- un file CSV parte da quando la banca lo fa partire;
- prima del primo movimento c'erano già dei soldi sul conto, e l'app non sa quanti.

La somma dei movimenti è quindi un numero **sbagliato**, e un saldo sbagliato presentato come vero è peggio di
nessun saldo. Per questo il saldo viene sempre da un valore vero:

| Conto | Da dove viene il saldo |
|---|---|
| collegato (Enable Banking) | dalla banca, a ogni aggiornamento. Se la banca non lo dà, non c'è |
| manuale (CSV) | lo scrivi tu una volta, dopo aver controllato l'ultimo movimento; poi si aggiorna con i movimenti importati dopo |

## La migrazione `V34`

```sql
ALTER TABLE account ADD COLUMN balance            NUMERIC(19, 2);
ALTER TABLE account ADD COLUMN balance_date       DATE;
ALTER TABLE account ADD COLUMN balance_source     VARCHAR(10) CHECK (balance_source IN ('BANK', 'MANUAL'));
ALTER TABLE account ADD COLUMN balance_updated_at TIMESTAMPTZ;
```

`NUMERIC(19, 2)`, come gli importi dei movimenti: mai `double` per i soldi. Il file è
`backend/src/main/resources/db/migration/V34__saldo_dei_conti.sql`; nell'entità `Account` i campi `balance`,
`balanceDate`, `balanceSource` (l'enum `BalanceSource`: `BANK` o `MANUAL`) e `balanceUpdatedAt`.

---

## Parte B: i conti collegati, il saldo della banca

Enable Banking ha `GET /accounts/{uid}/balances`. Il client lo sapeva già chiamare (`EnableBankingClient.getBalances`),
ma lo usava solo il programma di prova della Fase 0 (`PocRunner`).

### Quale saldo

Una banca può mandare più saldi dello stesso conto, ognuno con un tipo dello standard ISO 20022:

| Tipo | Che cosa | Si usa? |
|---|---|---|
| `ITBD`, `CLBD` | **contabile**, in giornata o a fine giornata: solo i movimenti contabilizzati | sì, per primi |
| `XPCD` | **previsto**: contabile più i movimenti in attesa | se manca il contabile |
| `ITAV`, `CLAV`, `FWAV` | **disponibile**: può comprendere un fido, quindi non sono i soldi del conto | se manca anche il previsto |
| altri (`OPBD`, `OTHR`…) | | solo se non c'è nient'altro |

Il contabile è il "saldo contabile" dell'app della banca, ed è quello che torna con i movimenti. Dentro un gruppo
vince il più recente; a parità di giorno, l'ordine della tabella. Contano solo i saldi nella valuta del conto. Se la
banca non dice a quale giorno si riferisce, vale oggi.

La scelta è una funzione pura, `BankBalance.choose(balances, currency, today)`, provata da `BankBalanceTest`.

### Quando si legge

In `SyncService.syncAccount`, **dopo** i movimenti:

```java
ImportResult result = importService.importAccount(account, psu);
accountRepository.recordSync(account.getId(), Instant.now(), null);
...
refreshBalance(account, psu, Instant.now());
```

Tre regole:

1. **Il saldo è un "di più".** Se la banca risponde con un errore (anche un 429, troppe letture), il saldo di prima
   resta, e la sincronizzazione è **riuscita** lo stesso: i movimenti sono già salvati.
2. **Se la banca risponde senza un saldo utilizzabile**, il saldo si toglie: meglio niente che un numero vecchio
   mostrato come attuale.
3. **In background al massimo una volta in 20 ore** (`BACKGROUND_BALANCE_INTERVAL`). Ogni lettura senza l'utente
   presente conta nel limite PSD2 di circa 4 al giorno per conto (`docs/08`), e i movimenti vengono prima. Con
   "Aggiorna ora" l'utente è presente (gli header PSU), e il saldo si legge sempre.

Il salvataggio usa `AccountRepository.recordBalance`, una `UPDATE` che tocca solo le colonne del saldo, in una
transazione sua: lo stesso motivo di `recordSync` (`syncAccount` non è `@Transactional`, vedi il suo commento).

---

## Parte C: i conti manuali, il saldo scritto da te

Per un conto manuale (Tinaba, Trade Republic da file) la banca non dà niente all'app. I CSV di Tinaba e di Trade
Republic non hanno una colonna del saldo. Allora il saldo lo scrivi tu, **una volta**:

1. l'app mostra l'**ultimo movimento importato** del conto ("28 ottobre 2026 · Ricevuto da Mario Rossi · 200,00 €");
2. apri l'app della banca, trovi quel movimento e leggi il saldo **dopo** di lui;
3. lo scrivi.

Da lì il saldo si aggiorna da solo: il saldo scritto più i movimenti importati **dopo**.

### Che cosa vuol dire "dopo"

Quando scrivi il saldo, l'app salva:

- `balance`: il saldo che hai scritto;
- `balance_date`: il giorno dell'ultimo movimento (oggi, se il conto non ne ha ancora);
- `balance_updated_at`: l'istante in cui l'hai scritto.

Il saldo di oggi è `balance` più la somma dei movimenti contabilizzati che hanno:

- una data **successiva** a `balance_date`, **oppure**
- la **stessa** data, ma sono stati importati **dopo** `balance_updated_at`.

```java
@Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM BankTransaction t
        WHERE t.account.id = :accountId
          AND t.status = it.wallettracker.transaction.TransactionStatus.BOOKED
          AND (t.bookingDate > :date OR (t.bookingDate = :date AND t.importedAt > :at))
        """)
BigDecimal sumAfter(...);
```

Perché la seconda condizione? Un file nuovo può portare un movimento **dello stesso giorno** dell'ultimo che avevi
controllato, che prima non c'era: non era dentro il saldo che hai scritto, quindi va aggiunto. Quelli dello stesso
giorno già importati prima invece li avevi davanti, e sono già nel saldo.

E un file con **più storia** (movimenti più vecchi)? Hanno una data precedente: non contano, erano già dentro il saldo.

Il calcolo sta in `CurrentBalance.of` (che cosa mostrare) e in `AccountService.dtoOf` (le due query). Il giorno
mostrato è quello dell'ultimo movimento contato.

### L'API

```
PUT /api/accounts/{id}/balance        {"balance": 1234.56}     null lo toglie
```

- solo per un conto **manuale**: per uno collegato risponde **409** ("Il saldo di un conto collegato lo dà la
  banca"). Due saldi diversi per lo stesso conto non avrebbero senso;
- al massimo due decimali (`@Digits(integer = 17, fraction = 2)`), altrimenti 400.

`GET /api/accounts` ha per ogni conto un campo `balance`:

```json
"balance": { "amount": 164.33, "date": "2026-10-31", "source": "MANUAL", "startDate": "2026-10-28" }
```

`startDate` c'è solo per un conto manuale: è il giorno del saldo che hai scritto. Senza saldo, `balance` è `null`.

---

## Parte D: dove si vede

### Conti

Una colonna **Saldo**:

- conto collegato: "2.310,45 € · al 31 ott 2026 · dalla banca"; senza saldo, "la banca non lo dà" (o "al prossimo
  aggiornamento" se il conto non è mai stato aggiornato);
- conto manuale: "117,59 € · al 28 ott 2026 · scritto da te", oppure, dopo altri movimenti, "dal tuo saldo del
  28 ott, più i movimenti dopo"; senza saldo, "non ancora scritto". Sotto, **scrivi il saldo** o **correggi il
  saldo**.

Il modulo (`BalanceForm`, in `frontend/src/app/accounts/balance-form.ts`) mostra l'ultimo movimento, con la ricerca dei
movimenti che c'era già (`GET /api/transactions?accountId=…&size=1`, ordinata dal più recente). Per correggere, il
campo parte dal saldo di oggi.

**Gli importi si scrivono come vuoi** (`parseBalance`): "1.234,56", "1234,56", "1234.56", "-12,30 €". Un punto seguito
da tre cifre senza virgola separa le migliaia ("1.234" è milleduecentotrentaquattro). Più di due decimali, o testo,
danno l'errore "Scrivi un importo, come 1.234,56".

### Importa

Dopo un'importazione, sotto "Importazione completata", la sezione **Il saldo del conto**:

- se il conto non ha ancora un saldo, il modulo compare subito;
- se ce l'ha, il saldo aggiornato con i movimenti appena importati: "164,33 € al 31 ottobre 2026. È quello che vedi
  nell'app della banca? **No, correggilo**". È il controllo pratico che il file sia stato letto bene: se il saldo non
  torna, manca un movimento o un segno è sbagliato (`docs/tinaba.md`).

### Investimenti: la liquidità

La scheda **Liquidità sui conti di investimento** (`InvestmentDtos.Cash`, campo `cash` di `GET /api/investments`):
per ogni conto con il ruolo Investimenti nella valuta scelta, il suo saldo, e in alto la somma di quelli conosciuti.
Un conto senza saldo c'è lo stesso, con "saldo non scritto: scrivilo in Conti" (manuale) o "la banca non dà il
saldo". La scheda non compare se non ci sono conti di investimento.

Sono i soldi arrivati sui conti di investimento e non investiti in titoli: il caso 1 della tabella di
`docs/investimenti.md`, parte I (bonifico al broker, nessun acquisto).

---

## Parte E: i test, e la prova nel browser

Backend:

- `BankBalanceTest` (puri): il contabile vince sul disponibile; tra i contabili il più recente, e a parità di giorno
  quello in giornata; senza contabili il previsto, poi il disponibile, poi qualsiasi; un'altra valuta o niente di
  utilizzabile non dà saldo;
- `SyncServiceTest`: dopo i movimenti il saldo della banca si salva; in background non si rilegge prima di 20 ore,
  con "Aggiorna ora" sì; un errore sul saldo non fa fallire la sincronizzazione e lascia il saldo di prima; una banca
  che non dà saldo lo toglie;
- `AccountBalanceTest`: il saldo scritto parte dall'ultimo movimento; dopo, un movimento dello stesso giorno e uno
  nuovo contano, uno vecchio no (350 + 46,74 − 50 = 346,74); un conto senza movimenti parte da oggi; un conto
  collegato risponde 409; tre decimali danno 400; null toglie il saldo;
- `InvestmentServiceTest`: la liquidità ha solo i conti di investimento, con il saldo della banca o senza.

Frontend: la colonna Saldo della pagina Conti (banca e manuale, l'errore su un importo sbagliato, il salvataggio);
`parseBalance`; dopo un'importazione il modulo che compare e il saldo salvato; la scheda della liquidità.

Nel browser (telefono e PC), con dati inventati (Banca Alfa con il saldo della banca, Tinaba, Trade Republic e Fondo
Beta come conti manuali):

| Passo | Atteso | Visto |
|---|---|---|
| Conti, conto corrente | 2.310,45 € al 31 ott 2026 · dalla banca | sì |
| Tinaba, "scrivi il saldo" | l'ultimo movimento: 28 ottobre 2026 · Ricevuto da Mario Rossi · 200,00 € | sì |
| scrivo "117,59" | 117,59 € al 28 ott 2026 · scritto da te | sì |
| importo un CSV di Tinaba con un cashback di 46,74 € del 31 ottobre | 164,33 € al 31 ottobre 2026, "È quello che vedi nell'app della banca?" | sì |
| Investimenti, liquidità | Tinaba 164,33 €; Trade Republic e Fondo Beta "saldo non scritto" | sì |

Nessuna pagina più larga dello schermo, nessun errore nella console. Un ritocco trovato così: sul PC il modulo
nella colonna stretta andava a capo a ogni parola; ora la colonna si allarga quando il modulo è aperto.

**Non provato:** il saldo di una banca vera. Nel cloud Enable Banking non è raggiungibile: il saldo del conto
corrente della prova è stato scritto nel database. Alla prima sincronizzazione vera, il log dice quale tipo è stato
scelto ("saldo 1234.56 (CLBD) al 2026-10-31"); se la tua banca non lo dà, la pagina Conti dice "la banca non lo dà".

---

## Parte F: esercizi guidati

1. **Il saldo di ING.** Dopo "Aggiorna ora", cerca nel log del backend la riga "saldo … (…)". Quale tipo ha scelto
   l'app? Confronta la cifra con l'app di ING.
2. **Lo stesso giorno.** In `AccountBalanceTest`, togli la condizione `t.importedAt > :at` da `sumAfter` (lasciando
   solo `t.bookingDate > :date`). Quale numero cambia, e perché è sbagliato?
3. **Il controllo dopo l'importazione.** Importa un file di Tinaba, scrivi il saldo, poi importa un file con un
   movimento in più. Il saldo che l'app propone è quello dell'app di Tinaba? Se no, che cosa manca?
4. **Un saldo calcolato?** Perché non basta `SUM(amount)` di tutti i movimenti del conto? Prova a immaginare un conto
   aperto nel 2020 e collegato all'app nel 2026.

---

## Parte G: il saldo a mano anche per i conti collegati

**Il problema, trovato usando l'app.** Nella liquidità dei conti di investimento Trade Republic e i due conti Fineco
dicevano "la banca non dà il saldo", e la cifra era 0 €. Trade Republic con Enable Banking il saldo non lo dà; per
Fineco va controllato (sotto). Un saldo scritto a mano si poteva mettere solo ai conti manuali.

**La scelta: scriverlo, e poi aggiornarlo con i movimenti.** Per un conto collegato senza saldo della banca, ora si
può scrivere il saldo come per un conto manuale, e da lì si aggiorna con i movimenti che arrivano dopo con la
sincronizzazione. Perché aggiornarlo e non lasciarlo fermo:

- un saldo fermo sarebbe giusto solo il giorno in cui lo scrivi, e poi sempre più sbagliato, senza dirlo;
- la sincronizzazione porta **tutti** i movimenti da lì in avanti (rilegge sempre qualche giorno in più, senza
  doppioni: `docs/08`), quindi partire da un valore vero e aggiungerli è esatto;
- la data mostrata è quella dell'ultimo movimento contato, e "dal tuo saldo del …, più i movimenti dopo" dice da dove
  viene: se non torna con la banca, si corregge in un attimo.

Un limite: si contano solo i movimenti **contabilizzati**. Uno in attesa che la banca contabilizza con una data
**precedente** al giorno del saldo scritto non viene aggiunto. Succede di rado; la correzione è riscrivere il saldo.

**Le regole, nel codice:**

- `AccountService.changeBalance`: si può scrivere per ogni conto, **tranne** quando la banca dà già il saldo (409):
  due saldi per lo stesso conto non avrebbero senso;
- `SyncService.refreshBalance`:
  - se la banca dà un saldo, **sostituisce** quello scritto a mano;
  - se non lo dà, uno scritto a mano **resta** (prima la sincronizzazione lo avrebbe tolto);
  - in background, per un conto con il saldo scritto a mano, il saldo non si chiede: non si consumano letture per una
    risposta che non arriva. Con "Aggiorna ora" sì: se un giorno la banca lo dà, prende il suo posto;
- pagina Conti: "scrivi il saldo" per ogni conto che non ha il saldo della banca; Investimenti: "la banca non dà il
  saldo: scrivilo in Conti".

**Fineco: la banca il saldo lo dà?** Enable Banking lo chiede alla banca a ogni "Aggiorna ora" (e in background al
massimo una volta al giorno). Nel log del backend, dopo un "Aggiorna ora", per ogni conto c'è una di queste righe:

| Riga nel log | Che cosa vuol dire |
|---|---|
| `…: saldo 1234.56 (CLBD) al 2026-11-01` | la banca lo dà: da qui si vede |
| `…: la banca non dà un saldo utilizzabile` | la banca risponde, ma senza saldo (o in un'altra valuta) |
| `…: saldo non letto: …` | la richiesta è fallita (per esempio 429, troppe letture): si riprova al prossimo aggiornamento |

Se non c'è nessuna di queste righe, il conto non è stato aggiornato dopo questa modifica.

**I test.** `AccountBalanceTest.aLinkedAccountWithoutTheBankBalanceCanHaveOneWritten` (un conto collegato: 500 €
scritti, poi un movimento di +12,50 € dalla sincronizzazione, 512,50 €);
`SyncServiceTest.aBalanceWrittenByTheUserIsKeptAndTheBankOneReplacesIt` (in background non si chiede; una risposta
vuota lo lascia; un saldo della banca lo sostituisce). Nel browser: il conto corrente collegato senza saldo della
banca, "scrivi il saldo", 2.310,45 €, "scritto da te".

---

## Parte H: la carta di credito non usa il saldo disponibile

**Trovato con i conti veri.** Dopo il primo "Aggiorna ora" il log diceva, per la carta di credito ING:

```
saldo 2500.0 (ITAV) al 2026-11-02
```

La banca, per la carta, manda solo il saldo **disponibile** (`ITAV`), e l'app lo usava perché non c'era un contabile
(parte B). Ma per una carta il disponibile è quanto si può ancora spendere, cioè il **plafond** meno le spese: 2.500 €
tondi sono il plafond intero, non il saldo della carta.

**La correzione.** `BankBalance.choose(..., withAvailable)`: per un conto con il ruolo **Carta di credito**
(`SyncService.refreshBalance`) i saldi disponibili (`ITAV`, `CLAV`, `FWAV`) non si usano mai. Se la banca dà solo
quelli, la carta resta senza saldo ("la banca non lo dà"), e si può scrivere a mano (parte G). Il saldo sbagliato
salvato prima sparisce al primo aggiornamento: è un saldo della banca, e la banca non ne dà più uno utilizzabile.

Per gli altri conti il disponibile resta l'ultima scelta: Revolut, per esempio, manda solo quello, e per un conto
senza fido coincide con il saldo.

**I test.** `BankBalanceTest.forACreditCardTheAvailableBalanceIsNeverUsed`,
`SyncServiceTest.aCreditCardDoesNotShowItsAvailableCreditAsBalance`.

---

## Parte I: il plafond disponibile della carta, a parte (`V35`)

Con la parte H il disponibile di una carta non è più il suo saldo. Ma è un'informazione utile: quanto si può ancora
spendere. Allora si salva **a parte**, con il suo nome.

- `V35__plafond_carta.sql`: le colonne `available_credit` e `available_credit_date` di `account`;
- `BankBalance.available`: tra i saldi della banca, il disponibile più recente (`ITAV`, `CLAV`, `FWAV`);
- `SyncService.refreshBalance`: per un conto con il ruolo **Carta di credito**, a ogni lettura del saldo salva anche il
  plafond (`AccountRepository.recordAvailableCredit`); se la banca non lo dà più, si toglie;
- `AccountDto.availableCredit`: c'è solo per le carte di credito (un conto corrente con un fido non lo mostra: il suo
  disponibile resta, se serve, l'ultima scelta per il saldo).

Nella pagina Conti, sotto il saldo della carta, separato da una riga:

> **Plafond disponibile** · 2.500,00 € · al 2 nov 2026 · dalla banca

Il saldo della carta resta quello che è: "la banca non lo dà" se manca, e si può scrivere a mano (parte G).

**I test.** `BankBalanceTest.theAvailableCreditOfACardIsTheMostRecentAvailableBalance`;
`SyncServiceTest.aCreditCardDoesNotShowItsAvailableCreditAsBalance` (il plafond salvato a parte, e un conto che non è
una carta che non lo ha); `AccountBalanceTest.aCreditCardShowsTheAvailableCredit` (nell'API solo con il ruolo Carta di
credito); `accounts-page.spec.ts`. Nel browser, telefono e PC: la riga del plafond sotto "la banca non lo dà".

---

## Parte J: una carta di credito ha solo il plafond, non un saldo

**La domanda dell'utente.** Con la parte I la carta mostrava il plafond, ma anche "la banca non lo dà" e "scrivi il
saldo". Che cos'è il saldo di una carta di credito?

Sarebbe **quanto hai speso e non ti è ancora stato addebitato**: il debito che il conto corrente pagherà a fine mese.
Si potrebbe ricavare come plafond totale meno plafond disponibile, ma la banca il plafond totale non lo manda. E
scriverlo a mano non avrebbe senso: cambia a ogni spesa e torna a zero a ogni addebito. Le spese della carta, una per
una, sono già nei movimenti.

**La scelta.** Per un conto con il ruolo **Carta di credito** c'è solo il **plafond disponibile**:

- `SyncService.refreshAvailableCredit`: per una carta si legge solo il plafond (il disponibile della banca), mai un
  saldo, anche se la banca mandasse un contabile; un saldo salvato prima di questa scelta si toglie. Il plafond cambia
  a ogni spesa: si rilegge a ogni "Aggiorna ora", e in background al massimo una volta al giorno (se la banca l'ha
  già dato per oggi, non si richiede);
- `AccountService.changeBalance`: per una carta risponde 409 ("Per una carta di credito non c'è un saldo: si mostra
  il plafond disponibile");
- pagina Conti: la cella della carta (sul telefono con l'etichetta "Plafond") mostra solo "Plafond disponibile ·
  2.500,00 € · al 2 nov 2026 · dalla banca", oppure "la banca non dà il plafond"; niente "scrivi il saldo".

Nel log, al posto della riga del saldo, per una carta c'è `…: plafond disponibile 2500.00 al 2026-11-02`.

Con questa parte la variante di `BankBalance.choose` senza i saldi disponibili (parte H) non serve più, e non c'è più:
una carta non arriva mai alla scelta del saldo.

**I test.** `SyncServiceTest.aCreditCardDoesNotShowItsAvailableCreditAsBalance` (anche con un contabile della banca,
nessun saldo; il plafond salvato; in background, lo stesso giorno, non si rilegge);
`AccountBalanceTest.aCreditCardShowsTheAvailableCredit` (e il 409); `accounts-page.spec.ts` (solo il plafond, nessun
pulsante). Nel browser, telefono e PC: la carta con il solo plafond; l'API rifiuta un saldo scritto a mano.

**Trovato aggiornando l'app.** Subito dopo l'aggiornamento la carta diceva "la banca non dà il plafond": non era vero,
il plafond non era ancora stato **letto** (nessuna sincronizzazione dopo l'aggiornamento: "Ultimo aggiornamento" era
ancora quello di prima). Prima la versione precedente mostrava il vecchio saldo salvato, 2.500 €, e sembrava
funzionare. La scritta ora dice "plafond non ancora letto: premi «Aggiorna ora»", vera in tutti e due i casi: se la
banca il plafond non lo dà, resta così anche dopo.

---

## Parte K: la liquidità nel Riepilogo

Richiesta dell'utente: vedere nel Riepilogo **quanti soldi ci sono sui conti**, in tutto. La scheda **"Liquidità"**
(`overview/liquidity-card.ts`) somma i saldi di adesso, quelli che si vedono nella pagina Conti.

**Che cosa conta.**

| Conto | Nel totale? | Perché |
|---|---|---|
| conto principale, di spesa, senza ruolo | sì | sono i soldi di tutti i giorni |
| conto di investimento | sì | il suo saldo sono i soldi fermi lì, non investiti in titoli (parte D): liquidità anche lei |
| carta di credito | no | non ha un saldo ma un plafond (parte J): non sono soldi tuoi |
| conto escluso | no | l'hai escluso tu, da tutto |
| conto senza saldo | no | non sappiamo quanto c'è: la scheda dice "1 conto senza saldo non è nel totale" |

**Le valute: un totale per ognuna, senza conversioni.** 100 € e 100 $ non fanno 200 di niente: la scheda mostra un
totale per valuta, uno accanto all'altro ("7.344 €" e "520 USD": in italiano il dollaro si scrive con il codice), e
sotto i conti di ogni valuta con il suo codice (EUR, USD). Prima la valuta con più conti, di solito l'euro. Con una
valuta sola i codici non compaiono. Niente cambio in euro, apposta: servirebbe un servizio esterno per i tassi (un
collegamento in più verso terzi, che l'app oggi non ha), e la cifra cambierebbe ogni giorno anche senza movimenti. Se
un giorno servisse, si aggiungerebbe come stima dichiarata ("≈ … in tutto").

La scheda non segue il menu "Valuta" del Riepilogo, che è per le spese del mese e compare solo se nel mese ci sono
movimenti in più valute: con quel menu un conto in dollari fermo sarebbe sparito (era così nella prima versione della
scheda, corretto su segnalazione dell'utente).

**Dove sta.** Sul PC nella colonna di sinistra, sotto la scheda del grafico. Sul telefono dopo "Spese per categoria",
che sta subito sotto il grafico perché lo spiega (richiesta dell'utente; vedi `docs/36`, aggiunta "L'ordine sul
telefono"). Nel modello
(`overview-page.html`) le due schede stanno in un contenitore, `overview-main`, che nella griglia occupa le quattro righe
della colonna di sinistra: così la liquidità sta subito sotto il grafico, e non sotto la scheda più lunga della colonna
a destra. Non è legata al mese guardato: i saldi sono quelli di adesso, e ogni conto dice di che giorno è il suo ("al
6 nov"). La scheda intera porta alla pagina Conti.

**Da dove arrivano i dati.** Nessuna API nuova: la stessa `GET /api/accounts` della pagina Conti, attraverso
`AccountsStore`. La scheda la richiede a ogni apertura del Riepilogo (`refresh`), perché i saldi cambiano a ogni
aggiornamento dalla banca e a ogni importazione. Il totale si calcola nella dashboard: è una somma di pochi numeri già
arrivati.

**I test.** `liquidity-card.spec.ts`: il totale con il conto di investimento ma senza la carta e il conto escluso; un
totale per valuta, euro e dollari, con i conti sotto il loro codice; i conti dal più ricco; il conto senza saldo
detto a parte; nessuna scheda senza saldi.

## Parte L: il grafico della liquidità

Richiesta dell'utente: un grafico che mostri come cambia nel tempo la liquidità sui conti. Fino a qui l'app conosceva
solo il saldo di **adesso** di ogni conto (parti B e C): per una storia servivano i saldi dei giorni passati.

**Due strade, tutte e due.** Valutate con l'utente (che ha scelto la terza):

| | Come | Pro | Contro |
|---|---|---|---|
| A, ricostruzione | il saldo vero di oggi, meno i movimenti, giorno per giorno all'indietro | grafico pieno da subito | se manca un movimento, tutti i giorni prima sono sbagliati: contro la parte A ("mai dai soli movimenti") |
| B, fotografie | ogni giorno l'app salva il saldo di ogni conto | valori veri | il grafico parte vuoto |
| **C, tutte e due** | le fotografie sono i valori veri; i giorni senza si stimano dalla fotografia successiva | utile subito, e dice che cosa è stimato | più lavoro |

La C non tradisce la parte A: una stima parte sempre da un saldo **vero** (una fotografia, o il saldo di oggi), e il
grafico la disegna diversa, tratteggiata, con la parola "stima" quando la si guarda.

**Le fotografie (`V41`, tabella `balance_snapshot`).** Un conto, un giorno, un importo. `BalanceSnapshotScheduler` ogni
ora (mentre l'app è accesa) salva il saldo **della banca** di ogni conto che conta nella liquidità, lo stesso della
pagina Conti (`AccountService.currentBalance`), **sotto il giorno a cui è vero** (`trueOn`): il giorno che dice la banca.
Nello stesso giorno l'ultima fotografia sostituisce la precedente.

**I saldi scritti a mano non si fotografano** (Trade Republic, i conti caricati da file, parti C e G). Il loro saldo è
quello scritto più i movimenti importati dopo: fotografato ogni giorno, un conto aggiornato con un file una volta alla
settimana avrebbe avuto sei giorni "veri" senza i movimenti non ancora caricati, e una fotografia vince sulla stima.
Invece si ricalcola a ogni richiesta con gli stessi movimenti: dal giorno del saldo scritto in poi ogni giorno è il saldo
scritto più i movimenti fino a quel giorno, esatto quanto il saldo del conto, e **non è una stima** (`Anchor.trustedFrom`);
prima di quel giorno sì. Un file caricato dopo corregge da solo anche i giorni passati.

Perché non semplicemente "oggi": in background la banca si rilegge al massimo ogni 20 ore (parte B, regola 3), mentre i
movimenti arrivano ogni 6. Il saldo letto ieri sera non contiene i movimenti di oggi: salvato come "saldo di oggi"
sarebbe stato un valore vero sbagliato. Salvato sotto ieri è esatto, e oggi si stima (vedi sotto). Ogni ora e non
una volta al giorno perché il computer può essere spento: basta che l'app sia accesa un momento perché il giorno abbia
il suo valore. Eliminando un conto manuale se ne vanno anche le sue fotografie. Nei test il timer è spento
(`wallettracker.balance-history.enabled`), come la sincronizzazione.

**La storia (`BalanceHistoryService`, `GET /api/accounts/balance-history?currency=EUR`).** Contano gli stessi conti
della scheda Liquidità (parte K): niente carte di credito né conti esclusi, solo quelli con un saldo, una valuta alla
volta. Da quando: il primo movimento (o la prima fotografia, o il giorno di un saldo) di uno dei conti, al massimo un
anno fa. Per ogni conto i valori veri sono le fotografie e il saldo di adesso, al giorno a cui è vero. Ogni altro giorno
si stima con i movimenti contabilizzati:

- **prima** dell'ultimo valore vero, all'indietro: il valore del giorno dopo meno i movimenti del giorno dopo (oppure la
  fotografia di quel giorno, se c'è). Prima del primo movimento di un conto il suo saldo resta fermo: non sappiamo
  niente di diverso;
- **dopo** l'ultimo valore vero (il saldo della banca è di ieri), in avanti: il giorno prima più i movimenti del giorno.

Esempio: oggi 1.000 €, ieri lo stipendio (+1.500), l'altro ieri una spesa (−200). Ieri sera: 1.000 €; l'altro ieri sera
1.000 − 1.500 = −500 €; il giorno prima −500 + 200 = −300 €. Un giorno è **stimato** se almeno un conto, quel giorno, non
ha un valore vero. La stima resta per i giorni prima di questa funzione, per quelli con l'app spenta tutto il giorno, e
per i giorni dopo l'ultimo saldo letto dalla banca. Il calcolo è una funzione senza database (`reconstruct`), provata da
`BalanceHistoryReconstructTest`; `BalanceHistoryServiceTest` prova fotografie, conti contati ed endpoint.

**Le valute.** Una alla volta, senza conversioni (parte K): un grafico con 11.000 € e 700 $ sulla stessa scala avrebbe
la linea dei dollari piatta sul fondo, e un cambio servirebbe un servizio esterno e cambierebbe la cifra ogni giorno. Le
fotografie si salvano per tutti i conti, in ogni valuta. Con più valute, sopra il grafico dei pulsanti (uno per ogni
valuta dei conti con un saldo, nell'ordine della scheda) scelgono quale tracciare, ognuna con la sua scala; di partenza
la principale. Scorrendo, cambia il totale della valuta del grafico, gli altri restano quelli di adesso. Una valuta che
non ha ancora due giorni da disegnare mostra "Storico non ancora disponibile per CHF", e i pulsanti restano.

**Nella scheda Liquidità del Riepilogo.** Sotto il totale, il grafico della valuta scelta (di partenza la prima della scheda),
con il componente degli investimenti (`TrendChart`), a cui si sono aggiunti due parametri: `estimated` (per ogni giorno,
se è stimato: un tratto tra due giorni veri è pieno, ogni altro tratteggiato, anche un buco in mezzo ai giorni salvati)
e `zeroBased` (qui false: la scala parte poco sotto il valore più basso, altrimenti con
10.000 € sul conto le variazioni sarebbero una riga piatta). Scorrendolo, il totale mostra quel giorno, e sotto "Il 20
luglio 2026 · stima dai movimenti". La legenda ha "Stima dai movimenti" se c'è un tratto stimato, e "Saldi salvati" solo se c'è
un tratto pieno (due giorni salvati di fila). Nessuna spiegazione sotto il grafico: i testi della scheda sono per chi
usa l'app, e la legenda basta. Con meno di due giorni il grafico non
c'è. Per il grafico la scheda non è più tutta un collegamento (un tocco per scorrere avrebbe aperto Conti): a Conti porta
"Vedi i conti" in alto.

**Un conto alla volta.** Toccando un conto nell'elenco della scheda, il grafico mostra solo quel conto
(`GET /api/accounts/balance-history?currency=EUR&accountId=7`); sopra il grafico il suo nome, il saldo del giorno scelto
scorrendo, e "Tutti i conti" per tornare al totale (anche toccando di nuovo il conto, o una valuta). Il totale in cima
resta quello di adesso. Serve a confrontare la linea di un conto con il suo estratto: se il totale non torna, si trova
subito il conto i cui movimenti nell'app non tornano con quelli della banca (un doppione, un movimento mancante).

## Parte M: il saldo della banca segue i movimenti arrivati dopo

**Il caso, trovato con il grafico della liquidità.** Il 16 novembre la linea di Fineco ··3288 stava circa 1.500 € sotto
l'estratto da marzo a ottobre. I movimenti nell'app erano giusti, uno per uno. Il motivo: Fineco aveva dato il saldo,
2.595,30 € "al 16 novembre", **prima** che arrivasse la cedola di quel giorno (+1.666,86 €, −208,36 € di ritenuta). La
sincronizzazione scarica i movimenti ogni 6 ore ma rilegge il saldo al massimo ogni 20 (parte B, regola 3): la cedola è
arrivata, il saldo no. Lo storico prendeva 2.595,30 € per il valore di fine giornata e, andando indietro, toglieva la
cedola da tutti i giorni prima; la pagina Conti e la scheda Liquidità mostravano un saldo vecchio di 1.458,50 €.

**La regola, la stessa dei saldi scritti a mano (parte C).** Il saldo di adesso è il saldo dato dalla banca più i
movimenti contabilizzati arrivati **dopo** la sua lettura: quelli con una data successiva al giorno del saldo, e quelli
dello stesso giorno importati dopo il momento della lettura (`balance_updated_at`). `AccountService.currentBalance` fa
la stessa somma (`sumAfter`) per i due tipi di saldo; il giorno mostrato diventa quello dell'ultimo movimento, se è più
recente. Quando la banca ridà il saldo, il suo valore prende il posto del calcolo e si riparte da lì.

Un movimento con una data **precedente** al giorno del saldo, arrivato dopo, non si aggiunge: la banca lo aveva già nel
suo saldo (era contabilizzato prima), ed è l'app che l'ha scaricato in ritardo.

**Nello storico** (`BalanceHistoryService.anchorOf`): il valore vero è quello di fine del giorno del saldo, cioè il saldo
di adesso meno i movimenti con una data successiva. Per Fineco il 16 novembre: 2.595,30 + 1.458,50 = 4.053,80 €; il 15,
2.595,30 €. Le fotografie salvano questo valore, aggiornato a ogni ora se arriva un altro movimento di quel giorno.

**Le prove.** `AccountBalanceTest.aBankBalanceFollowsTheMovementsThatArriveAfterItWasRead` (il caso di Fineco, un
movimento vecchio arrivato tardi, la nuova lettura che riparte da sé) e
`BalanceHistoryServiceTest.aMovementArrivedAfterTheBankBalanceBelongsToItsDayNotToTheDaysBefore`.

## Parte N: il controllo dei saldi

Richiesta dell'utente, dopo il caso della parte M: avere la certezza che nell'app non manchi nessun movimento. Nessuna
app che legge i conti tramite le banche può prometterlo da sola (uno storico rifiutato dopo 89 giorni senza login, un
consenso scaduto, un movimento consegnato in ritardo). Si può però **verificarlo** ogni volta: è la riconciliazione che
si fa a mano con gli estratti conto, fatta dall'app a ogni saldo nuovo.

**La regola.** Il saldo nuovo deve essere quello di prima più i movimenti arrivati in mezzo, con la regola di `sumAfter`
(parti C e M): dopo il primo saldo (una data successiva, o lo stesso giorno importati dopo la sua lettura) e dentro il
secondo (una data precedente, o lo stesso giorno importati prima della sua lettura). Al centesimo.

- **Torna:** tutti i movimenti di quel periodo ci sono. Non si salva niente.
- **Non torna:** manca un movimento o ce n'è uno in più. Si salva la coppia di saldi (`V42`, tabella `balance_check`).

**Quando.** In `SyncService.refreshBalance`, a ogni saldo nuovo della banca, dopo aver importato i movimenti e prima di
salvarlo; in `AccountService.changeBalance`, a ogni saldo scritto dall'utente. Solo tra due saldi dello stesso tipo (due
della banca, o due scritti): il primo saldo di un conto non ha niente con cui confrontarsi.

**L'avviso** (`BALANCE_MISMATCH`, come gli altri in `AlertService`, non salvato): si ricalcola a ogni lettura degli avvisi
da ogni coppia salvata. Se poi il movimento mancante arriva, dalla sincronizzazione o da un file caricato in Importa,
la coppia torna e l'avviso sparisce da solo. Dice il conto, il periodo, la differenza e il verso ("in più": manca
un'entrata o c'è un'uscita doppia; "in meno": il contrario), con il collegamento ai movimenti di quel conto in quel
periodo. Si chiude con la crocetta; un'altra differenza è un avviso nuovo. I conti esclusi non danno avvisi.

**Che cosa non vede.** Un movimento che la banca aveva già nel saldo ma che arriva nell'app dopo la lettura, con la
stessa data (parte M): per qualche ora è contato due volte nel saldo di adesso, e alla lettura successiva il controllo
lo segnala come differenza anche se intanto il saldo è tornato giusto. È raro, perché il saldo si legge subito dopo i
movimenti; l'avviso in quel caso si chiude con la crocetta.

**Le prove.** `BalanceCheckTest`: due saldi della banca che tornano e uno che non torna, con l'avviso e il movimento
mancante che lo fa sparire; la cedola dello stesso giorno arrivata dopo la lettura (conta) e il movimento arrivato prima
(no); due saldi scritti a mano; il primo saldo, senza niente da confrontare. `alerts-panel.spec.ts`: il testo
dell'avviso e il collegamento ai movimenti.

## Parte O: i saldi che comprendono i movimenti in attesa

**Il caso, trovato con il controllo dei saldi.** Ogni giorno il Riepilogo aveva un avviso "i movimenti di Revolut
··1995 non tornano con il saldo della banca". La differenza era sempre la somma dei pagamenti con carta **in attesa**
(il 19 novembre 2026: 15,02 + 3,64 = 18,66 €). Revolut non dà il saldo contabile (parte B), solo il previsto (`XPCD`) o
il disponibile, che comprendono i movimenti in attesa; il controllo invece li confrontava con i soli movimenti
contabilizzati. L'avviso non spariva mai: Revolut contabilizza il giorno dopo, fuori dal periodo controllato. E il saldo
di adesso contava due volte un pagamento in attesa: una dentro il saldo di Revolut, una quando arrivava contabilizzato
dopo la lettura (parte M).

**La regola: si lavora sempre con il saldo contabile.** Quando la banca dà un saldo previsto o disponibile
(`BankBalance.includesPending`), la sincronizzazione salva anche la somma dei movimenti in attesa di quel momento, appena
importati (`V44`, colonna `balance_pending`). Il saldo contabile è il saldo letto meno quella somma
(`Account.getBookedBalance`), e da lì parte tutto: il saldo di adesso (più i movimenti contabilizzati arrivati dopo,
parte M), lo storico e le fotografie (parte L), il controllo (parte N, `compare` riceve i movimenti in attesa del saldo
nuovo). Un pagamento in attesa si conta così una volta sola, quando viene contabilizzato.

**Quale saldo si mostra.** Il saldo del conto (pagina Conti, scheda Liquidità, il punto di oggi del grafico) è il
contabile **più i movimenti in attesa di adesso**: i pagamenti in attesa contano già come spese (Riepilogo, Analisi,
budget), e un saldo che non li togliesse sarebbe incoerente con loro; è anche il numero delle app delle banche. Se il
conto ha movimenti in attesa, sotto c'è una riga "Contabile: 63,46 €". Vale per ogni conto, anche per una banca che dà
il saldo contabile. All'inizio la scelta era stata l'inverso (il contabile come saldo, "con i movimenti in attesa" nella
riga), per coerenza con Fineco e ING; l'utente ha fatto notare che il saldo di Revolut era quello giusto, e la coerenza
con le spese ha deciso. Lo storico, le fotografie e il controllo dei saldi restano sul contabile
(`CurrentBalance.bookedAmount`): i movimenti in attesa cambiano a ogni sincronizzazione (si cancellano e si
reimportano), il contabile no. Nel grafico solo il punto di oggi aggiunge i movimenti in attesa di adesso: quelli dei
giorni prima intanto sono stati contabilizzati, e sono già nei movimenti.

**La migrazione V44** cancella anche i controlli fatti con la regola vecchia (per Revolut segnalavano una differenza che
non c'era) e le fotografie dei saldi dei giorni scorsi (per Revolut avevano dentro i movimenti in attesa): sono valori
calcolati, quei giorni tornano stimati dai movimenti e le fotografie ripartono dalla prossima ora.

**Che cosa resta.** Un saldo "disponibile" può comprendere un fido: l'app non lo può togliere. Per Revolut non è così.

**Le prove.** `BankBalanceTest.theExpectedAndTheAvailableBalancesIncludeThePendingMovements`,
`AccountBalanceTest.aBalanceWithThePendingMovementsShowsThemAndTheBookedOneApart` (il caso di Revolut, poi i
pagamenti contabilizzati che contano una volta sola),
`BalanceHistoryServiceTest.todayHasThePendingMovementsLikeTheAccountsPageThePhotoIsBooked`, `BalanceCheckTest.aBalanceWithThePendingMovementsIsComparedWithoutThem`,
`accounts-page.spec.ts` (la riga nella pagina Conti).

