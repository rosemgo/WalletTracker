# Il codice dell'operazione della banca

Fuori dalla numerazione, come `docs/saldi.md` e `docs/tinaba.md`. Nasce da una domanda: come fa l'app di una banca a
mettere da sola un movimento nella categoria giusta, e perché la nostra non ci riesce?

| Parte | Cosa |
|---|---|
| A | che cosa mandano davvero le banche (niente MCC) |
| B | una regola può guardare il codice invece del testo (`V45`) |
| C | come si legge il codice |
| D | le regole di partenza sul codice, e gli interessi passivi |
| E | dove si vede: Regole e scheda del movimento |
| F | i limiti |
| G | i test |

---

## Parte A: che cosa mandano davvero le banche

L'app di una banca categorizza da sola soprattutto grazie al **codice MCC** (Merchant Category Code): un numero di 4
cifre che i circuiti delle carte danno a ogni esercente (5411 supermercato, 5541 distributore...). La banca lo
riceve con ogni pagamento con carta.

Noi i movimenti li riceviamo da Enable Banking, che prevede un campo `merchant_category_code`, ma ogni banca decide
se riempirlo. Abbiamo controllato su un'installazione vera, con Fineco, ING, Revolut (via Enable Banking), Tinaba e
Trade Republic (da file), con una query sul JSON originale (`raw_json`): **nessuna banca manda l'MCC**, il campo non
c'è proprio.

Alcune però mandano un'altra cosa: il **codice dell'operazione** (`bank_transaction_code`). Dice *che operazione è*,
non *per che cosa*:

| Banca | Parte riempita | Esempi |
|---|---|---|
| Fineco | `code` | `I18-Interessi Passivi`, `CED-Stacco Cedole Italia`, `V48-Bonifico Istantaneo` |
| ING | `description` | `Accredito Stipendio/Pensione`, `Prelievo Carta`, `Addebito Diretto`, `Bonus Promozione` |
| Revolut | `code` | `CARD_PAYMENT`, `TOPUP`, `TRANSFER`, `REWARD` |

Non tutti i movimenti lo hanno: nell'installazione controllata, ING lo mandava su meno di un movimento su cinque.
Quasi tutti i codici di Fineco li riconoscono già le regole di partenza sul testo (`Ced.su`, `Div.su`, `Rit.ced`...).
I file CSV hanno colonne simili (la `Categoria` di Tinaba, la `CAUSALE` di ING, il `Tipo` di Revolut): anche lì sono
tipi di operazione, non categorie di spesa.

Qualche codice però dice la categoria, mentre il testo spesso non la dice: lo stipendio di ING arriva come
`Bonifico N. … Note: STIPENDIO MESE …`, e una regola sul testo lo trova solo se chi paga scrive sempre la stessa nota.

---

## Parte B: una regola può guardare il codice invece del testo

`V45` aggiunge alle regole la colonna `match_field`:

- `TEXT`: il testo del movimento, causale + controparte (`ClassificationService.textOf`), come finora;
- `BANK_CODE`: il codice dell'operazione della banca. Un movimento senza codice non corrisponde mai.

Il resto della regola non cambia: "contiene", "inizia con", "contiene tutte le parole", il segno dell'importo, il
tipo, la categoria, la priorità. Le regole che c'erano guardano il testo (`DEFAULT 'TEXT'`), e una richiesta
all'API senza `matchField` (`RuleRequest`) crea una regola sul testo, come prima.

Nel motore cambia solo il passo 6 (`ClassificationService.classify`):

```java
String bankCode = transaction.getBankCode();
for (ClassificationRule rule : rules) {
    if (rule.matches(text, bankCode, amount)) {
```

e `ClassificationRule.matches(text, bankCode, amount)` sceglie dove guardare. Anche la prova di una regola
(`RuleService.preview`) usa lo stesso confronto, con `rule.matches(transaction)`.

---

## Parte C: come si legge il codice

`BankCode.of(rawJson)` legge `bank_transaction_code` dal JSON originale e unisce le parti riempite, nell'ordine
`code`, `sub_code`, `description`, con " · " in mezzo. Null se non c'è.

Non c'è una colonna nuova nel database: il JSON originale c'è già per ogni movimento e non cambia, quindi il codice
vale subito anche per i movimenti arrivati prima di questa versione, senza migrare dati. Leggere il JSON costa poco,
e quasi mai: se il testo non contiene `bank_transaction_code` (tutte le righe dei file, e molte banche) non si legge
nemmeno. Un JSON rovinato vale come "nessun codice", e non ferma la classificazione.

---

## Parte D: le regole di partenza

| Regola | Dove | Condizione | Risultato |
|---|---|---|---|
| Stipendio e pensione (codice della banca) | codice | contiene `Accredito Stipendio`, entrate | Entrata, *Stipendio e pensione* |
| Prelievo con la carta (codice della banca) | codice | contiene `Prelievo Carta`, uscite | Spesa, *Contanti e prelievi* |
| Bonus della banca (codice della banca) | codice | contiene `Bonus Promozione`, entrate | Entrata, *Rimborsi fiscali e bonus* |
| Premio Revolut (codice della banca) | codice | la parola `REWARD`, entrate | Entrata, *Rimborsi fiscali e bonus* |
| Interessi passivi | testo | contiene `Interessi Passivi`, uscite | Spesa, *Costi bancari* |

Tutte con priorità 50, come i costi del conto di `V4`: vengono dopo le regole degli investimenti e prima delle
regole scritte dall'utente (100, se non la cambia).

Gli **interessi passivi** sono gli interessi che paghi alla banca: per uno scoperto sul conto, o per una linea di
credito (il Credit Lombard di Fineco). Nessuna regola li prendeva, e restavano spese senza categoria. La regola guarda
il testo, non il codice: così vale per qualsiasi banca che li scriva così.

La categoria si cerca per **chiave** (`category.builtin_key`, `V43`), non per nome: vale in italiano e in inglese, e
anche se l'utente l'ha rinominata. Se l'ha eliminata, la regola c'è lo stesso, senza categoria.

`V45` non riclassifica i movimenti già importati: lo fa la prossima classificazione (la sincronizzazione, al massimo
ogni 6 ore o subito con "Aggiorna ora", un'importazione, una modifica di una regola). Come `V25` e `V31`.

---

## Parte E: dove si vede

- **Regole**: nel modulo c'è "Cerca: nel testo (causale e controparte) / nel codice dell'operazione della banca".
  Con il codice, l'etichetta diventa "Se il codice" e una riga dice dove trovarlo. Nell'elenco, sotto la condizione,
  "nel codice della banca".
- **Scheda del movimento**: in Dettagli, se la banca l'ha mandato, la riga "Codice della banca" con il link "crea
  una regola", che apre le Regole con il codice già scritto (`matchField=BANK_CODE` nell'indirizzo).

---

## Parte F: i limiti

- Il codice c'è solo nei movimenti arrivati da Enable Banking. Le righe dei file CSV hanno colonne simili (`CAUSALE`
  di ING), ma ogni banca le chiama a modo suo: oggi le regole sul codice non le vedono. Se servisse, l'importazione
  potrebbe imparare una colonna "codice dell'operazione", come ha imparato la colonna del dettaglio (`docs/tinaba.md`).
- Il codice dice che operazione è, non dove hai speso: per le spese con carta (supermercato, benzina, Amazon) la
  categoria continua a venire da te, dai suggerimenti della tua storia (`docs/26`) e dall'IA (`docs/29`).

---

## Parte G: i test

| Test | Che cosa prova |
|---|---|
| `BankCodeTest` | le parti di Fineco, ING e Revolut; l'ordine; niente codice; un JSON rovinato |
| `ClassificationRuleTest` | una regola sul codice guarda solo il codice; una sul testo ignora il codice |
| `ClassificationServiceTest` | le regole di partenza: stipendio, prelievo e bonus dal codice; un bonifico resta senza categoria; gli interessi passivi in *Costi bancari* |
| `RuleControllerTest` | una regola sul codice dall'API, con la prova; senza `matchField` la regola guarda il testo |
| `TransactionDetailTest` | la scheda del movimento ha il codice, o null |
| `rules-page.spec.ts` | il modulo con "Cerca", l'etichetta "Se il codice", l'indirizzo con `matchField` |
| `transaction-detail.spec.ts` | la riga "Codice della banca" e il suo link |
