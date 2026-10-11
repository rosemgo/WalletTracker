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

## Parte A: come viene riconosciuto il saldo

Prima di tutto dai al conto della carta il ruolo **`CREDIT_CARD`**. Il saldo viene poi riconosciuto in
due modi, entrambi **sicuri**: non si basano su coincidenze.

1. **Con una regola sulla causale.** La regola di partenza `Estratto conto carta di credito` riconosce
   l'addebito di ING e ora dà `CARD_SETTLEMENT` (l'ha aggiornata la migrazione **V6**, solo se non
   l'avevi modificata). Per un'altra banca basta una regola con la sua causale: nella Fase 2 si
   aggiungeranno dalla dashboard.
2. **Con l'abbinamento dei trasferimenti.** Alcune banche mostrano il pagamento anche sulla carta, come
   entrata. Se i due lati hanno l'aspetto di un trasferimento tra conti propri (le stesse condizioni
   degli altri trasferimenti: testo vuoto, nome dell'intestatario o IBAN di un tuo conto), vengono
   abbinati. Poiché un lato è una carta, il tipo è `CARD_SETTLEMENT`.

E se nessuno dei due funziona? Una **correzione manuale**: un clic nella dashboard.

### Un errore da cui imparare

La prima versione di questo passo aveva un terzo controllo, "per somma". Un'uscita da un tuo conto veniva
considerata un saldo se il suo importo era **esattamente** la somma di alcuni acquisti consecutivi della
carta. Sembrava sicuro ("al centesimo!"), ma sui dati veri ha scambiato per saldi della carta una
bolletta da 42,68 €, un pagamento a un amico da 7,30 € e persino una ritenuta sui dividendi da 49,70 €.

Perché? Con un centinaio di acquisti sulla carta, le somme di acquisti consecutivi sono **migliaia** di
valori diversi, quasi tutti sotto qualche centinaio di euro. Prendi un importo piccolo qualsiasi: la
probabilità che coincida con una di quelle somme non è affatto trascurabile. Su centinaia di movimenti,
qualche coincidenza è praticamente certa. Per esempio:

```
Carta:         BAR -30,00   EDICOLA -12,68   FARMACIA -37,02
Somme:         -30,00 -12,68 = -42,68   ← uguale alla bolletta
               -12,68 -37,02 = -49,70   ← uguale alla ritenuta
```

In più quel controllo veniva **prima** delle regole, quindi sovrascriveva anche risultati giusti (la
ritenuta è `INVESTMENT_TAX`).

La lezione è la stessa del passo 4: **il motore non deve indovinare**. Meglio un movimento da correggere a
mano che un errore silenzioso nei totali. Il controllo è stato rimosso, e quegli esempi sono diventati un
test di **regressione**: un test che ricorda un errore passato, perché non torni.

---

## Parte B: il codice

- **`AccountRole.CREDIT_CARD`** e **`TransactionType.CARD_SETTLEMENT`**: due nuovi valori negli enum.
  Non servono colonne nuove nel database, perché ruoli e tipi sono salvati come testo.
- **`V6__saldo_carta_di_credito.sql`**: aggiorna la regola di partenza, **solo se è ancora quella
  originale**. Una migrazione non deve cancellare le scelte dell'utente.
- **`ClassificationService.transferType`**: un trasferimento da o verso una carta è `CARD_SETTLEMENT`.

### I test

| Test | Scenario |
|---|---|
| `theCreditCardSettlementIsRecognisedByTheStarterRule` | l'addebito di ING, riconosciuto dalla regola |
| `aPaymentShownAlsoOnTheCardIsPairedAsASettlement` | banca che mostra il pagamento anche sulla carta |
| `movementsThatHappenToEqualSumsOfCardPurchasesKeepTheirType` | **regressione**: bolletta e ritenuta uguali per caso alla somma di acquisti della carta restano `EXPENSE` e `INVESTMENT_TAX` |
| `aRefundOnTheCardIsNotPairedWithAnUnrelatedPurchase` | un rimborso sulla carta e un acquisto dello stesso importo non sono un saldo |

---

## Parte C: cosa fare

1. **Aggiorna e lancia i test:**
   ```powershell
   git pull
   cd backend
   .\mvnw.cmd test          # 53 test
   ```
2. **Avvia l'applicazione** (`.\mvnw.cmd spring-boot:run`).
3. **Fai riclassificare tutto.** La classificazione viene ricalcolata dopo una sincronizzazione o un
   cambio di ruolo. Il modo più rapido è riassegnare il ruolo alla carta:
   ```powershell
   Invoke-RestMethod -Method Put -Uri http://localhost:8080/api/accounts/3/role `
       -ContentType "application/json" -Body '{"role": "CREDIT_CARD"}'
   ```
4. **Controlla** <http://localhost:8080/api/transactions?type=CARD_SETTLEMENT>: devono esserci solo gli
   `Estratto conto carta di credito`.

## Cosa hai imparato

- La differenza tra **spesa** e **rimborso di un anticipo**, e perché contarli entrambi è un errore.
- Perché un controllo "esatto al centesimo" può comunque produrre **coincidenze**, quando i confronti
  possibili sono migliaia.
- I **test di regressione**.
- Una **migrazione che aggiorna dati** solo se non sono stati modificati dall'utente.
