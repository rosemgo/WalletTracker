# 7. Fase 1, passo 4: la classificazione automatica

**Obiettivo:** dare a ogni movimento un **tipo** (spesa, entrata, trasferimento tra i tuoi conti,
versamento negli investimenti, dividendo...), così che "quanto ho speso" conti solo le **spese vere**.

Il motore deve funzionare per **chiunque** installi WalletTracker, non solo per chi l'ha scritto. Le
regole complete e i principi sono in [`docs/03-modello-e-regole.md`](03-modello-e-regole.md): leggilo
prima di questa guida.

> Prerequisiti: passo 3 completato (movimenti importati) e `git pull` fatto.

---

## Parte A: motore e configurazione

Il punto chiave del passo è separare due cose:

| | Esempio | Dove vive |
|---|---|---|
| **Motore** | "due movimenti di importo opposto su due tuoi conti sono un trasferimento" | codice Java (`ClassificationService`), uguale per tutti |
| **Configurazione** | "una causale che inizia con `Div.su` è un dividendo"; "questo conto è di investimento"; "questo movimento l'ho corretto a mano" | database, diverso per ogni utente |

Perché le regole sono **dati** e non codice?
- **Si cambiano senza programmare:** nella Fase 2 la dashboard avrà una pagina "Regole".
- **Valgono per banche che non conosciamo:** chi usa Intesa aggiunge le regole per le causali di Intesa.
- **Il codice resta piccolo e testabile:** il motore sa solo *applicare* regole, non *quali* regole.

---

## Parte B: il codice

### 1. `V4__classificazione.sql`

- Aggiunge a `bank_transaction` le colonne del risultato:
  - `type` e `category`: il tipo e la categoria assegnati;
  - `rule_id`: la regola che ha deciso;
  - `transfer_peer_id`: il movimento dall'altra parte di un trasferimento;
  - `manual_type`: la correzione manuale.
- Crea la tabella **`classification_rule`** e la riempie con le **regole di partenza**
  (`builtin = true`). Aprila in IntelliJ: ogni riga è una regola leggibile.

### 2. `TransactionType` e `ClassificationRule`

`TransactionType` è l'enum dei tipi. `ClassificationRule` è l'entità di una regola, con il metodo
che la applica:

```java
public boolean matches(String text, BigDecimal amount) {
    if (amountSign == AmountSign.NEGATIVE && amount.signum() >= 0) return false;  // solo uscite
    if (amountSign == AmountSign.POSITIVE && amount.signum() <= 0) return false;  // solo entrate
    String haystack = text.toLowerCase();
    String needle = pattern.toLowerCase();
    return matchMode == MatchMode.STARTS_WITH ? haystack.startsWith(needle) : haystack.contains(needle);
}
```

Due enum annidati descrivono le opzioni: `MatchMode` (`CONTAINS`, `STARTS_WITH`) e `AmountSign`
(`ANY`, `NEGATIVE`, `POSITIVE`). Niente espressioni regolari: devono essere regole che chiunque può
scrivere da un modulo nella dashboard.

### 3. `ClassificationService`: il motore

Il metodo `classifyAll()`:
1. carica tutti i movimenti, le regole attive e i tuoi conti (indicizzati per IBAN);
2. **abbina i trasferimenti** (`pairTransfers`);
3. classifica ogni movimento con `classify(...)`, che prova gli 8 controlli in ordine (tabella in
   `docs/03`, sezione 4);

> **Una scelta di progetto: `TO_REVIEW`.** Un movimento senza alcun testo e senza abbinamento (succede
> con Trade Republic) potrebbe essere qualsiasi cosa: un acquisto di titoli, un pagamento con carta, un
> dividendo. Indovinare in base al tipo di conto vorrebbe dire scrivere nel codice le abitudini di una
> persona. Il motore quindi lo segna come **da verificare**: resta fuori dai totali, e la dashboard lo
> mostrerà in un elenco "da classificare".
4. restituisce quanti movimenti ci sono per tipo.

**Come funziona l'abbinamento** (`pairTransfers`):
1. separa le **uscite** dalle **entrate**;
2. raggruppa le entrate per importo e valuta (es. `"2000.00 EUR"`), così ogni uscita viene confrontata
   solo con le entrate dello stesso importo, non con tutte;
3. per ogni uscita, in ordine di data, cerca l'entrata **su un altro conto**, non ancora usata, più
   vicina nel tempo e al massimo a 3 giorni di distanza;
4. le due metà si "prenotano" a vicenda (`peers`), così un movimento non viene abbinato due volte.

Per evitare abbinamenti per **coincidenza**, ad esempio un acquisto da 20 € e un'entrata da 20 € da un
amico, si abbinano solo movimenti che **hanno l'aspetto di un trasferimento tra conti propri**
(`looksLikeOwnTransfer`). Il testo deve essere vuoto, come su Trade Republic, oppure contenere il
**nome dell'intestatario** di un tuo conto ("A favore di Mario Rossi", "Payment from Mario Rossi")
oppure l'**IBAN** di un tuo conto. I nomi vengono confrontati come **insiemi di parole**, così
"ROSSI MARIO" e "Mario Rossi" coincidono.

È un esempio di come una `Map` trasformi un problema "tutti contro tutti" in uno molto più veloce.

**Perché ricalcolare tutto ogni volta?** Il risultato dipende solo dai dati e dalle regole, non da
quello che è successo prima. Così una regola nuova vale subito anche per i movimenti vecchi, e non
ci sono stati intermedi difficili da capire. Con qualche migliaio di movimenti è questione di un attimo.

### 4. `PocRunner`

Dopo l'importazione, il programma riclassifica tutto e stampa:

```
=== Classificazione di tutti i movimenti salvati ===
  EXPENSE                142
  INCOME                 6
  INTERNAL_TRANSFER      18
  INVESTMENT_DEPOSIT     9
  ...

Ultimi movimenti di Conto Arancio (MAIN):
  2026-09-26   -2000.00 EUR  BOOKED   INVESTMENT_DEPOSIT     Bonifico istantaneo ... Trasferimento a mio conto
  2026-09-25    2776.86 EUR  BOOKED   INCOME                 Bonifico ... STIPENDIO MESE DI SETTEMBRE 2026
```

### 5. I test: `ClassificationServiceTest`

15 scenari, scelti apposta **diversi** dalle abitudini di una sola persona:

| Test | Scenario |
|---|---|
| `transferBetweenTwoNormalAccountsIsInternal` | trasferimento tra due conti normali |
| `transferToAnInvestmentAccountIsADepositEvenWithoutDescriptions` | versamento verso un broker che non manda descrizioni |
| `transferFromAnInvestmentAccountIsAWithdrawal` | prelievo da un conto di investimento |
| `aPurchaseFromAnInvestmentAccountIsStillAnExpense` | **spesa** al supermercato da un conto di investimento |
| `aDividendOnASpendingAccountIsInvestmentIncome` | **dividendo** su un conto di spesa |
| `theIbanOfAnotherOwnAccountInTheDescriptionMeansTransfer` | l'IBAN di un tuo conto nella causale |
| `theCreditCardMonthlySettlementIsAnInternalTransfer` | saldo mensile della carta di credito |
| `aManualCorrectionAlwaysWins` | la correzione manuale vince sulle regole |
| `zeroAmountsAndExcludedAccountsAreIgnored` | importi zero e conti esclusi |
| `withoutAnyMatchOutgoingIsExpenseAndIncomingIsIncome` | ultima risorsa |
| `aPurchaseIsNotPairedWithAnUnrelatedIncomeOfTheSameAmount` | un acquisto da 20 € e un'entrata da 20 € da un amico: nessun abbinamento per coincidenza |
| `aTransferMentioningTheOwnerNameIsPairedWhateverTheWordOrder` | "ROSSI MARIO" e "Mario Rossi" sono la stessa persona |
| `transactionsTooFarApartAreNotPaired` | importi uguali ma troppo distanti nel tempo: non è un trasferimento |
| `withoutAnyTextAndNoPairTheTransactionIsToReviewOnAnyAccount` | movimenti senza testo: `TO_REVIEW` su qualsiasi conto |
| `aManualCorrectionResolvesATransactionToReview` | una correzione manuale risolve un movimento da verificare |

---

## Parte C: cosa fare

1. **Aggiorna:**
   ```powershell
   git status
   git pull
   docker compose up -d
   cd backend
   .\mvnw.cmd test          # 37 test
   ```
2. **Lancia il programma** e scegli un collegamento salvato. All'avvio Flyway applica `V4`; dopo
   l'importazione vedrai la classificazione di **tutti** i movimenti, di tutte le banche.
3. **Controlla in IntelliJ** e cerca gli errori di classificazione:

   ```sql
   -- Spese e entrate VERE per mese (finalmente!)
   SELECT date_trunc('month', booking_date) AS mese,
          SUM(amount) FILTER (WHERE type = 'INCOME')  AS entrate,
          SUM(amount) FILTER (WHERE type = 'EXPENSE') AS spese
   FROM bank_transaction
   WHERE status = 'BOOKED'
   GROUP BY mese ORDER BY mese;

   -- Quanti movimenti per tipo e per conto
   SELECT a.name, t.type, COUNT(*), SUM(t.amount)
   FROM bank_transaction t JOIN account a ON a.id = t.account_id
   GROUP BY a.name, t.type ORDER BY a.name, t.type;

   -- Le regole e quante volte sono state usate
   SELECT r.name, r.pattern, r.result_type, COUNT(t.id) AS usata
   FROM classification_rule r LEFT JOIN bank_transaction t ON t.rule_id = r.id
   GROUP BY r.id ORDER BY r.priority;
   ```

4. **Prova una correzione manuale** (nella Fase 2 sarà un menu a tendina nella dashboard):

   ```sql
   UPDATE bank_transaction SET manual_type = 'IGNORED' WHERE id = <id del movimento>;
   ```
   Rilancia il programma: quel movimento resterà `IGNORED`, qualunque cosa dicano le regole.

5. **Mandami** (anche solo a parole) i movimenti classificati male. Sono quelli che ci dicono quali
   regole mancano o quali controlli vanno rivisti.

---

## Cosa hai imparato

- La separazione tra **motore** (codice) e **configurazione** (dati): il principio che rende un
  programma utilizzabile da persone diverse.
- Le **regole come dati** in una tabella, con priorità.
- Un algoritmo di **abbinamento** con `Map`, raggruppamento e ordinamento.
- **Ricalcolo deterministico**: stesso input, stesso risultato.
- Come scrivere **test per scenari** che non sono i tuoi.

## Prossimi passi

- **Passo 5 (fine Fase 1):** la sincronizzazione automatica a intervalli regolari, senza lanciare il
  programma a mano.
- **Fase 2:** il server web e la dashboard, compresa la **configurazione guidata**: collegare le banche,
  scegliere i ruoli dei conti, correggere i movimenti e gestire le regole, tutto dalla dashboard.
