# 3. Modello e regole

Questo documento descrive **come ragiona WalletTracker**: i principi, il modello dei dati e le regole
del motore di classificazione. È il riferimento per il codice: se una regola cambia, si aggiorna prima qui.

---

## 1. Principi

1. **Un'installazione = un utente.** Come Firefly III e Actual Budget, WalletTracker è *self-hosted*:
   ognuno installa la propria copia, con la propria applicazione Enable Banking e il proprio database.
   La modalità gratuita di Enable Banking legge solo i conti del titolare dell'applicazione: un servizio
   unico per più persone richiederebbe un contratto commerciale (e significherebbe gestire i dati
   bancari di altri).
2. **Il motore è uguale per tutti, la configurazione è di ognuno.** Nel codice non ci sono nomi di
   banche né abitudini di una persona. I conti, le regole e le correzioni manuali stanno nel database,
   e si modificano dalla dashboard (Fase 2).
3. **Si guarda prima il movimento, poi il conto.** Un dividendo è un dividendo anche su Revolut;
   una spesa al supermercato è una spesa anche se fatta da un conto di investimento.
4. **La correzione manuale vince sempre.** Le regole automatiche sbagliano, prima o poi: l'utente
   deve poter correggere un singolo movimento senza scrivere regole.
5. **Configurazione semplice.** L'obiettivo è che una persona senza conoscenze informatiche possa
   installare WalletTracker e configurarlo dalla dashboard: collegare le banche, scegliere i ruoli dei
   conti, gestire le regole.

---

## 2. Conti e ruoli

Ogni conto ha un ruolo (enum `AccountRole`), che si sceglie dopo averlo collegato:

| Ruolo | Significato | Effetto sulla classificazione |
|---|---|---|
| `UNASSIGNED` | appena collegato, ruolo non ancora scelto | come un conto normale |
| `MAIN` | conto principale (es. dove arriva lo stipendio) | come un conto normale |
| `SPENDING` | conto usato per le spese | come un conto normale |
| `INVESTMENT` | conto di investimento | dà il nome ai trasferimenti (versamento/prelievo) e interpreta i movimenti **senza testo** |
| `EXCLUDED` | conto da ignorare (di prova, vuoto) | tutti i suoi movimenti sono `IGNORED` |

`MAIN`, `SPENDING` e `UNASSIGNED` oggi si comportano allo stesso modo: la distinzione serve alla
dashboard, per raggruppare e mostrare i conti.

---

## 3. Tipi di movimento

Enum `TransactionType`. Solo `EXPENSE` e `INCOME` contano come spese ed entrate vere.

| Tipo | Significato |
|---|---|
| `EXPENSE` | spesa |
| `INCOME` | entrata (stipendio, rimborso, regalo ricevuto...) |
| `INTERNAL_TRANSFER` | soldi spostati tra due tuoi conti |
| `INVESTMENT_DEPOSIT` | soldi spostati da un conto normale a un conto di investimento |
| `INVESTMENT_WITHDRAWAL` | soldi spostati da un conto di investimento a un conto normale |
| `SECURITIES_BUY` / `SECURITIES_SELL` | acquisto / vendita di titoli |
| `INVESTMENT_INCOME` | cedole, dividendi, interessi |
| `INVESTMENT_TAX` | ritenute, imposta di bollo, capital gain, Tobin tax |
| `IGNORED` | da ignorare (importo zero, conto escluso, correzione manuale) |

---

## 4. Il motore di classificazione

Implementato in `ClassificationService`. Per ogni movimento si prova in quest'ordine, e **la prima
risposta vince**:

| # | Controllo | Risultato |
|---|---|---|
| 1 | il conto è `EXCLUDED` | `IGNORED` |
| 2 | c'è una **correzione manuale** | il tipo scelto dall'utente |
| 3 | l'importo è zero (es. verifica della carta) | `IGNORED` |
| 4 | **trasferimento abbinato**: su un altro tuo conto c'è un movimento con stesso importo, segno opposto e stessa valuta, al massimo a 3 giorni di distanza | trasferimento (vedi sotto) |
| 5 | il movimento cita l'**IBAN di un altro tuo conto** (causale o JSON originale) | trasferimento |
| 6 | una **regola** della tabella `classification_rule` corrisponde | tipo e categoria della regola |
| 7 | **nessun testo** (né causale né controparte) e conto `INVESTMENT` | uscita → `SECURITIES_BUY`, entrata → `INVESTMENT_INCOME` |
| 8 | ultima risorsa | uscita → `EXPENSE`, entrata → `INCOME` |

**Che tipo di trasferimento?** Dipende da dove partono e arrivano i soldi:
- verso un conto `INVESTMENT` da un conto normale → `INVESTMENT_DEPOSIT`;
- da un conto `INVESTMENT` verso un conto normale → `INVESTMENT_WITHDRAWAL`;
- negli altri casi → `INTERNAL_TRANSFER`.

La classificazione viene **ricalcolata da zero** su tutti i movimenti dopo ogni importazione: una
regola nuova o modificata vale subito anche per il passato. Le correzioni manuali non si perdono,
perché stanno in una colonna separata (`manual_type`).

### Le regole

Ogni regola dice: *"se il testo del movimento (causale + controparte) contiene / inizia con X, ed
eventualmente l'importo è positivo/negativo, allora il tipo è Y e la categoria è Z"*. Le regole si
provano in ordine di **priorità** crescente.

La migrazione `V4__classificazione.sql` crea delle **regole di partenza** (`builtin = true`), scritte
a partire dalle causali reali viste nella Fase 0. Valgono su **qualsiasi conto**:

| Regola | Testo | Tipo |
|---|---|---|
| Cedola, dividendo, interessi | inizia con `Ced.su`, `Div.su`, `Interessi Portaf` | `INVESTMENT_INCOME` |
| Ritenute, bolli, capital gain, Tobin tax | `Rit.ced`, `Rit.div`, `Ritenuta Fiscale`, `Imposta Sostitutiva`... | `INVESTMENT_TAX` |
| Compravendita titoli | contiene `Compravendita Titoli` | `SECURITIES_BUY` (−) / `SECURITIES_SELL` (+) |
| Saldo della carta di credito | contiene `Estratto conto carta di credito` | `INTERNAL_TRANSFER` |
| Trasferimento a un proprio conto | contiene `Trasferimento a mio conto` | `INTERNAL_TRANSFER` |
| Costi del conto | `Canone Mensile`, `Imposta di bollo` | `EXPENSE` (categoria "Costi bancari") |

Chiunque usi altre banche può aggiungere le proprie regole (dalla dashboard, nella Fase 2). Chi vuole
contribuire al progetto può proporre nuove regole di partenza.

---

## 5. Particolarità delle banche (osservate nella Fase 0)

Sono gestite nel motore in modo **generico**, cioè valgono per qualsiasi banca abbia lo stesso
comportamento:

| Comportamento | Banca dove l'abbiamo visto | Come lo gestiamo |
|---|---|---|
| più conti nella stessa sessione, alcuni senza IBAN (carte) | ING | identificativo del conto = `identification_hash` / IBAN+valuta / uid |
| più conti con lo **stesso IBAN** (uno per valuta) | Revolut | la chiave del conto include la valuta |
| movimenti da 0,00 € (verifiche della carta) | Revolut | `IGNORED` |
| massimo **100 movimenti** per risposta, senza `continuation_key` | ING (carta di credito) | il periodo viene diviso a metà, ricorsivamente |
| storico lungo solo subito dopo il login, poi max 90 giorni | Fineco, ING | se la banca rifiuta il periodo, si riprova con 89 giorni |
| letture in background quasi assenti | Trade Republic | header PSU quando l'utente è presente |
| **pagine ripetute**, date richieste ignorate, 6 decimali | Trade Republic | copie identiche eliminate, nessuna divisione del periodo, arrotondamento a 2 decimali |
| **nessuna descrizione**: solo data, importo, direzione | Trade Republic | abbinamento dei trasferimenti + controllo 7 (nessun testo su conto di investimento) |
| consenso di 90 giorni invece di 180 | Trade Republic | avviso di scadenza (Fase 3) |

Sulla mancanza di descrizioni di Trade Republic: è un **limite noto** della sua interfaccia PSD2,
segnalato anche da altri progetti che usano Enable Banking. L'alternativa per avere i dettagli
(titolo, ISIN, tipo di operazione) è l'**import del file di esportazione delle transazioni** che
Trade Republic offre nell'app (Impostazioni → Estratti/Documenti → Esportazione transazioni) e, dal
2026, anche dal sito web. È pianificato nella Fase 3.

---

## 6. Eliminazione dei doppioni

Implementata in `TransactionImportService` (dettagli in `docs/06-fase-1-passo-3-movimenti.md`):

1. **copie identiche** nella stessa risposta (stesso JSON): se ne tiene una;
2. **impronta**: `ref:<entry_reference>` se la banca lo fornisce, altrimenti `fp:<SHA-256 di data,
   importo, valuta, controparte, causale>`;
3. **movimenti uguali ma distinti** (JSON diversi, stessa impronta): numero progressivo `#2`, `#3`...;
4. **movimenti in attesa**: sostituiti a ogni importazione;
5. **vincolo `UNIQUE (account_id, dedup_key)`** nel database come rete di sicurezza.

Data di inizio dell'importazione: la prima volta `FIRST_IMPORT_DAYS`, poi l'ultimo movimento salvato
meno 10 giorni; se la banca rifiuta il periodo, gli ultimi 89 giorni. Se una risposta contiene 100
movimenti o più, il periodo viene diviso a metà (solo se la banca rispetta le date richieste).

---

## 7. Sincronizzazione (Fase 1, passo 5)

| Modalità | Quando | Header PSU | Limite |
|---|---|---|---|
| automatica | a intervalli regolari | no | ~4 letture al giorno per conto (alcune banche meno) |
| "Aggiorna ora" | l'utente preme il pulsante nella dashboard | sì | nessun limite giornaliero |

Un errore su un conto non blocca gli altri: si salta quel conto e si riprova al giro successivo.
