# 10. La carta di credito: il saldo non è una spesa

**Obiettivo:** contare le spese della carta di credito **una volta sola**.

Con una carta di credito ci sono due tipi di movimenti:

| Movimento | Dove | Cos'è |
|---|---|---|
| gli acquisti (supermercato, Amazon...) | sul conto della carta | le **spese vere**: la banca ti anticipa i soldi |
| l'addebito mensile, es. `Estratto conto carta di credito al 20261010: -630,39` | sul conto corrente | il **rimborso dell'anticipo**: paghi in un colpo solo gli acquisti del mese |

Se contassimo entrambi, i 630,39 € risulterebbero spesi **due volte**. L'addebito mensile riceve quindi un
tipo apposito, **`CARD_SETTLEMENT`**, che, come i trasferimenti, resta fuori dai totali.

> Prerequisiti: `git pull` fatto.

---

## Parte A: come il motore riconosce il saldo

Prima di tutto, nella dashboard (o per ora con le API) dai al conto della carta il ruolo **`CREDIT_CARD`**.
Dopodiché il saldo viene riconosciuto in tre modi, dal più generale al più specifico.

### 1. La carta mostra il pagamento: abbinamento

Alcune banche mostrano il pagamento **anche sulla carta**, come entrata (+630,39 €). Allora basta
l'abbinamento dei trasferimenti che già conosci: un'uscita da un tuo conto e un'entrata sulla carta,
stesso importo, al massimo 3 giorni di distanza → entrambe `CARD_SETTLEMENT`.

A differenza dei trasferimenti normali, qui il testo non conta. Su una carta le entrate sono solo pagamenti
del saldo o rimborsi; anche in caso di coincidenza con un rimborso, i totali non cambierebbero, perché
acquisto e rimborso si annullano.

### 2. La carta NON mostra il pagamento: il totale

Le carte ING, ad esempio, mostrano solo gli acquisti e i rimborsi. Allora il motore usa un'altra
osservazione: **il saldo è la somma dei movimenti della carta di un periodo**, da un estratto conto al
successivo.

```
Movimenti della carta              Somma "tornando indietro" dall'ultimo
 15/09  SUPERMERCATO    -45,30           -210,00  ← uguale all'addebito: è il saldo!
 23/09  NEGOZIO ONLINE -120,00           -164,70
 27/09  RIMBORSO        +15,30           -44,70
 30/09  RISTORANTE      -60,00           -60,00
                                    
Conto corrente
 10/10  ADDEBITO ...   -210,00
```

Per ogni uscita dai tuoi conti, il motore prova ogni movimento della carta come **ultimo del periodo**.
Da lì torna indietro un movimento alla volta, sommando, e controlla se la somma è **esattamente** uguale
all'uscita, al centesimo. Per evitare coincidenze:
- servono **almeno 2 movimenti** della carta: un singolo acquisto da 20 € e un'uscita da 20 € dal conto
  corrente non sono un saldo;
- il periodo copre **al massimo 45 giorni**;
- l'addebito arriva **entro 45 giorni** dall'ultimo movimento del periodo.

### 3. La regola di partenza

La regola `Estratto conto carta di credito` (migrazione V4) ora dà `CARD_SETTLEMENT` invece di
`INTERNAL_TRANSFER`: l'ha aggiornata la migrazione **V6**. Funziona anche senza il ruolo `CREDIT_CARD`,
ma solo con la causale di ING. Le altre banche hanno causali diverse: per loro servono il ruolo e i
controlli 1 e 2.

**Un limite del controllo 2.** La banca chiude il periodo dell'estratto conto con le sue regole. Se un
acquisto fatto il 9 viene contabilizzato l'11, la banca potrebbe metterlo nel periodo precedente mentre
noi, che ordiniamo per data contabile, nel successivo. In quel caso la somma non torna. Restano la regola
(per ING) e la correzione manuale.

---

## Parte B: il codice

- **`AccountRole.CREDIT_CARD`** e **`TransactionType.CARD_SETTLEMENT`**: due nuovi valori negli enum.
  Non servono colonne nuove nel database, perché ruoli e tipi sono salvati come testo.
- **`V6__saldo_carta_di_credito.sql`**: aggiorna la regola di partenza, **solo se è ancora quella
  originale**. Se l'avessi modificata tu, non la tocca: una migrazione non deve cancellare le scelte
  dell'utente.
- **`ClassificationService`**:
  - `transferType`: un trasferimento da o verso una carta è `CARD_SETTLEMENT`;
  - `pairTransfers`: un'entrata su una carta è sempre candidata all'abbinamento (controllo 1);
  - `findCardSettlements` e `matchesACardPeriod`: il controllo 2, con un doppio ciclo;
  - in `classify`, il nuovo controllo **4b**, subito dopo l'abbinamento.

**Un dettaglio Java.** Per confrontare due `BigDecimal` si usa `compareTo`, non `equals`:

```java
new BigDecimal("10.5").equals(new BigDecimal("10.50"))          // false: scala diversa (1 e 2 decimali)
new BigDecimal("10.5").compareTo(new BigDecimal("10.50")) == 0  // true: stesso valore
```

### I test

| Test | Scenario |
|---|---|
| `theCreditCardSettlementIsRecognisedByTheStarterRule` | la causale di ING, anche senza ruolo |
| `aSettlementEqualToTheCardTotalIsRecognisedWhateverTheText` | addebito con un testo qualsiasi, uguale alla somma di acquisti e rimborso della carta; gli acquisti restano `EXPENSE` |
| `aPaymentShownAlsoOnTheCardIsPairedAsASettlement` | banca che mostra il pagamento anche sulla carta |
| `aSinglePurchaseOfTheSameAmountIsNotASettlement` | un solo acquisto dello stesso importo: nessun saldo |
| `withoutTheCreditCardRoleTheTotalIsNotChecked` | senza il ruolo il controllo 2 non si applica |

---

## Parte C: cosa fare

1. **Aggiorna e lancia i test:**
   ```powershell
   git pull
   docker compose up -d
   cd backend
   .\mvnw.cmd test          # 54 test
   ```
2. **Avvia l'applicazione** (`.\mvnw.cmd spring-boot:run`). Flyway applica V6.
3. **Dai il ruolo `CREDIT_CARD` alla carta** (conto 3), da un'altra finestra di PowerShell:
   ```powershell
   Invoke-RestMethod -Method Put -Uri http://localhost:8080/api/accounts/3/role `
       -ContentType "application/json" -Body '{"role": "CREDIT_CARD"}'
   ```
   Il cambio di ruolo riclassifica subito tutti i movimenti.
4. **Controlla gli addebiti mensili** nel browser:
   <http://localhost:8080/api/transactions?type=CARD_SETTLEMENT>.
   Ogni `Estratto conto carta di credito` deve esserci.
5. **Esercizio: il controllo 2 funziona anche sui tuoi dati?** La regola riconosce già gli addebiti ING,
   quindi il controllo 2 non si vede. Per scoprire se la somma torna, calcola in IntelliJ la somma dei
   movimenti della carta in un periodo, ad esempio dall'11 settembre al 10 ottobre:
   ```sql
   SELECT SUM(amount), COUNT(*)
   FROM bank_transaction
   WHERE account_id = 3 AND status = 'BOOKED'
     AND booking_date BETWEEN '2026-09-11' AND '2026-10-10';
   ```
   Se il risultato è `-630.39`, il periodo della banca coincide con le date contabili. Altrimenti, prova
   a spostare le date di uno o due giorni: scoprirai come ING delimita i suoi periodi.

## Cosa hai imparato

- La differenza tra **spesa** e **rimborso di un anticipo**, e perché contarli entrambi è un errore.
- Un algoritmo di **somma su finestre consecutive** (doppio ciclo), con vincoli contro le coincidenze.
- Una **migrazione che aggiorna dati** solo se non sono stati modificati dall'utente.
- `BigDecimal.compareTo` contro `equals`.
