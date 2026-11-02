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
