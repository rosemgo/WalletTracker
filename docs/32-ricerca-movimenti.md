# 32. La ricerca nei movimenti

**Obiettivo:** nella pagina Movimenti, un campo **Cerca** che trova i movimenti scrivendo una parola ("netflix"),
un pezzo di parola ("izzer"), il nome di una categoria ("costi bancari") o un importo ("12,99"). Funziona insieme
agli altri filtri (conto, tipo, periodo, senza categoria) e con le pagine.

| Parte | Cosa |
|---|---|
| A | che cosa si cerca, e dove |
| B | il backend: la query, LIKE e i suoi caratteri speciali |
| C | gli importi |
| D | la pagina: aspettare che l'utente smetta di scrivere, e le risposte fuori ordine |
| E | i test, e la prova nel browser |
| F | esercizi guidati |
| G | aggiornamento: più parole, con una Specification |

---

## Parte A: che cosa si cerca, e dove

Il testo si cerca, **senza badare a maiuscole e minuscole**, in:

- la **causale** (`description`): "PAGAMENTO CARTA NETFLIX.COM AMSTERDAM";
- la **controparte** (`counterparty`): "Zalando SE";
- il nome della **categoria** del movimento (quella che si vede: scelta a mano o messa da una regola).
- la **nota** scritta dall'utente (aggiunta dopo, `docs/34`).

Se il testo è un **importo** ("12,99", "12.99", "-12,99 €", "1.234,56"), si trovano anche i movimenti con quell'importo,
**con qualsiasi segno**: cercando "12,99" escono l'abbonamento Netflix (−12,99) e il rimborso Zalando (+12,99).
Le due ricerche si sommano: "2026" trova le causali che contengono "2026" e i movimenti da 2.026,00 €.

Con **più parole** devono esserci **tutte**, in **qualsiasi ordine** e in **qualsiasi campo** (Parte G):
"netflix amsterdam" e "amsterdam netflix" trovano "NETFLIX.COM AMSTERDAM"; "canone bancari" trova la causale
"Canone Mensile conto" nella categoria "Costi bancari"; "netflix 12,99" trova i Netflix da 12,99 €.

---

## Parte B: il backend

### B1. L'API

```
GET /api/transactions?text=netflix&from=2026-10-01&page=0&size=50
```

`text` è un filtro in più, facoltativo come gli altri (`TransactionFilter`). Vuoto, o solo spazi, vuol dire "nessuna
ricerca". Più lungo di 100 caratteri: 400 (nessuna causale ha bisogno di più, e una query con un testo enorme è
solo lavoro sprecato).

### B2. La query

Nella prima versione era la stessa `@Query` dell'elenco (`BankTransactionRepository.search`, `docs/09`) con una
condizione in più; ora la query si costruisce in Java (Parte G), ma le idee sono le stesse. Per una parola:

```sql
AND (:hasText = false
     OR lower(coalesce(t.description, '')) LIKE :pattern ESCAPE '\'
     OR lower(coalesce(t.counterparty, '')) LIKE :pattern ESCAPE '\'
     OR lower(coalesce(c.name, '')) LIKE :pattern ESCAPE '\'
     OR (:hasAmount = true AND (t.amount = :amount OR t.amount = -:amount)))
```

- `:hasText = false OR ...`: il trucco già usato per gli altri filtri. Senza ricerca la condizione è sempre vera.
  Qui usiamo un booleano a parte perché PostgreSQL, con un testo `null`, non saprebbe di che tipo è il parametro
  (lo stesso problema delle date, risolto lì con `cast`);
- `lower(...)`: tutto in minuscolo, e il servizio manda il testo già in minuscolo: "Netflix", "NETFLIX" e "netflix"
  si trovano a vicenda;
- `coalesce(t.description, '')`: un campo vuoto (null) diventa testo vuoto. Senza, `null LIKE ...` non è né vero
  né falso, e il movimento sparirebbe anche quando cerchiamo nella controparte;
- `c.name`: la categoria. Nella query dei risultati è il `LEFT JOIN FETCH t.category c` che c'era già (carica la
  categoria insieme al movimento, senza N+1); nella query di conteggio diventa un `LEFT JOIN t.category c` semplice:
  per contare non serve caricarla, ma per cercare nel suo nome sì.

La **query di conteggio** deve avere le stesse condizioni: altrimenti "Pagina 1 di 4" conterebbe tutti i movimenti
e non quelli trovati.

### B3. LIKE e i suoi caratteri speciali

In LIKE due caratteri non sono lettere: `%` vuol dire "qualsiasi testo" e `_` "un carattere qualsiasi". Il servizio
costruisce il modello (`TransactionSearch.likePattern`, una volta per ogni parola):

```
"netflix"  →  "%netflix%"            (contiene "netflix")
"100%"     →  "%100\%%"              (contiene "100%", proprio con il segno di percentuale)
"su_m"     →  "%su\_m%"
```

Il `\` davanti dice a PostgreSQL "questo `%` (o `_`) è un carattere normale"; `ESCAPE '\'` nella query dice quale
carattere fa da escape. Senza, cercando solo "%" il modello sarebbe "%%%" ("qualsiasi testo"), e cercando "_"
sarebbe "%_%" ("almeno un carattere"): in entrambi i casi uscirebbero **tutti** i movimenti, invece del solo
"Sconto 100% su_misura". Un risultato sbagliato, non un errore, quindi difficile da notare: c'è un test proprio per
questo.

**E l'SQL injection?** Il testo arriva come **parametro** (`:pattern`), mai incollato nel testo della query:
PostgreSQL lo tratta sempre come un valore, qualunque cosa contenga. L'escape serve solo a far funzionare bene
LIKE, non alla sicurezza.

---

## Parte C: gli importi

`TransactionSearch.amountIn` riconosce un importo con un'espressione regolare:

```
-?( \d{1,3}(\.\d{3})+(,\d{1,2})?  |  \d+([.,]\d{1,2})? )
```

cioè, dopo aver tolto "€" e gli spazi:

| Scritto | Valore |
|---|---|
| `12,99`, `12.99` | 12,99 |
| `1.234,56` | 1234,56 (il punto separa le migliaia) |
| `1.234` | 1234: con tre cifre dopo il punto non sono decimali, un importo ne ha due |
| `-45,50 €` | 45,50 (il segno si ignora: si cercano spese e entrate) |
| `12,999`, `netflix` | non è un importo |

Il valore va alla query come `BigDecimal`, il tipo dei soldi (`docs/06`): nessun errore di arrotondamento.

---

## Parte D: la pagina

### D1. Il campo

`<input type="search">` in cima ai filtri, su una riga sua (è il filtro più usato). `type="search"` fa aggiungere al
browser la **x** per svuotarlo. Svuotato, l'elenco torna completo.

### D2. Aspettare che l'utente smetta di scrivere (debounce)

Se cercassimo a ogni tasto, scrivendo "netflix" partirebbero sette richieste: "n", "ne", "net"... Invece
`onSearch` fa partire un timer di 300 millisecondi, e ogni tasto lo **riavvia** (`clearTimeout`): la richiesta parte
solo quando l'utente si ferma. Nella prova nel browser, scrivendo "netflix" lettera per lettera: **una** richiesta.

Con RxJS si scriverebbe `debounceTime(300)`; qui la pagina usa i signal e una funzione semplice, e un timer basta.

### D3. Le risposte fuori ordine

Anche con il debounce possono partire due richieste vicine ("netf", una pausa, poi "netflix"). La rete non
garantisce l'ordine: la risposta a "netf" può arrivare **dopo** quella a "netflix", e rimettere in pagina i risultati
vecchi mentre nel campo c'è scritto "netflix". `load()` numera le richieste (`lastRequest`) e ignora le risposte che
non sono dell'ultima. Lo stesso problema, nella pagina Importa CSV, l'avevamo risolto con `switchMap` (`docs/25`):
due strade per la stessa idea.

### D4. Nessun risultato

"Nessun movimento trovato per "spotify" con questi filtri.": dice che cosa si è cercato, e ricorda che anche gli
altri filtri contano (cercando con il periodo "Questo mese", Netflix di settembre non c'è).

---

## Parte E: i test, e la prova nel browser

| Test | Che cosa prova |
|---|---|
| `TransactionControllerTest.transactionsCanBeSearchedByText` | causale (maiuscole/minuscole), pezzo di parola, categoria, importo nei vari formati e con i due segni, `%` e `_` come caratteri normali, spazi intorno, vuoto = tutto, insieme al periodo (con il conteggio giusto), testo troppo lungo = 400 |
| `TransactionControllerTest.amountsAreRecognizedInTheSearch` | i formati degli importi |
| `wallet-api.spec.ts` | il testo va all'API senza spazi intorno; solo spazi = non va |
| `transactions-page.spec.ts` | una richiesta sola dopo aver smesso di scrivere (con i timer finti di Vitest); conta solo la risposta all'ultima ricerca; il messaggio "nessun risultato" |

Nel browser, con un database vero e alcuni movimenti di prova: "netflix" scritto lettera per lettera → una richiesta,
due movimenti; "12,99" → i due Netflix e il rimborso Zalando; "costi banc" → il canone del conto (dalla categoria);
"spotify" → il messaggio; sul telefono il campo occupa tutta la larghezza.

---

## Parte F: esercizi guidati

### Esercizio 1: più parole (fatto: Parte G)

Con la prima versione (un pezzo di testo unico), cercando "netflix amsterdam" si trovava la causale
"NETFLIX.COM AMSTERDAM"? E "amsterdam netflix"? Come faresti a trovare i movimenti che contengono **tutte** le
parole, in qualsiasi ordine?

<details>
<summary>Soluzione</summary>

"netflix amsterdam" no: la causale contiene "netflix.com amsterdam", e il pezzo "netflix amsterdam" (con lo spazio
subito dopo "netflix") non c'è. "amsterdam netflix" nemmeno. Per cercare tutte le parole: il servizio divide il testo
negli spazi, e per ogni parola aggiunge una condizione "la causale, la controparte o la categoria contiene questa
parola", unite con AND. Con un numero di parole variabile una `@Query` fissa non basta: si usa una
**Specification** di Spring Data JPA (una condizione costruita in Java, pezzo per pezzo) o il Criteria API. Con molti
movimenti, PostgreSQL ha anche la ricerca "full text" (`to_tsvector`, `to_tsquery`), con un indice apposta.
</details>

### Esercizio 2: le lettere accentate

Una causale dice "CAFFÈ" e l'utente cerca "caffe". La trova? Come si potrebbe?

<details>
<summary>Soluzione</summary>

No: "è" ed "e" sono caratteri diversi. PostgreSQL ha l'estensione `unaccent` (`CREATE EXTENSION unaccent`, in una
migrazione): `unaccent(lower(t.description)) LIKE unaccent(:pattern)`. In JPQL si chiama con
`function('unaccent', ...)`. Nelle causali delle banche gli accenti sono rari (spesso sono già tolti), quindi per ora
non l'abbiamo fatto.
</details>

### Esercizio 3: la ricerca nell'indirizzo

Se ricarichi la pagina, la ricerca si perde. Come la terresti nell'indirizzo, ad esempio `/movimenti?cerca=netflix`,
così da poterla anche salvare nei preferiti?

<details>
<summary>Soluzione</summary>

All'apertura la pagina legge il parametro (`ActivatedRoute.snapshot.queryParamMap.get('cerca')`, come fa la pagina
Importa CSV con `?conto=`) e lo mette in `text`; a ogni ricerca aggiorna l'indirizzo con
`router.navigate([], { queryParams: { cerca: testo }, replaceUrl: true })`. `replaceUrl` evita di riempire la
cronologia del browser con una voce per ogni ricerca.
</details>

---

## Parte G: aggiornamento, più parole con una Specification

### G1. Perché una `@Query` non basta più

Con una parola la condizione era una sola. Con più parole ce n'è **una per parola**: "netflix amsterdam" ne vuole
due, "netflix amsterdam 12,99" tre. Una `@Query` è scritta una volta per tutte e non può cambiare il numero di
condizioni. Si potrebbe fissare un massimo (dieci parametri, quelli non usati "spenti"), ma è una query lunghissima
e fragile.

La soluzione di Spring Data JPA è la **Specification**: una condizione costruita **in Java**, con il **Criteria API**
di JPA. Il repository estende anche `JpaSpecificationExecutor`, che aggiunge `findAll(Specification, Pageable)`; la
Specification la costruisce `TransactionSearch.of(filtro, parole)`.

### G2. Com'è fatta

Una Specification è una funzione che riceve tre oggetti e restituisce una condizione (`Predicate`):

```java
(root, query, cb) -> {
    List<Predicate> conditions = new ArrayList<>();
    if (filter.accountId() != null) {
        conditions.add(cb.equal(root.get("account").get("id"), filter.accountId()));
    }
    ...
    for (String word : words) {
        conditions.add(wordAnywhere(cb, word, ...));      // una condizione per parola
    }
    return cb.and(conditions.toArray(Predicate[]::new));  // tutte insieme: AND
}
```

- `root` è il movimento (`BankTransaction`): `root.get("description")` è la sua causale;
- `cb` (`CriteriaBuilder`) costruisce le condizioni: `cb.equal`, `cb.like`, `cb.lower`, `cb.coalesce`, `cb.or`,
  `cb.and`;
- `query` è la query intera: serve a sapere se è quella dei risultati o quella del conteggio (G4).

Un vantaggio in più: un filtro **spento non c'è proprio** nella query (prima era `:accountId IS NULL OR ...`), e
sparisce anche il problema del tipo dei parametri null (il `cast` delle date).

### G3. Una parola

`wordAnywhere` costruisce, per ogni parola, "è nella causale **OR** nella controparte **OR** nel nome della categoria
(**OR** è l'importo, se la parola è un importo)". Le parole poi si uniscono con **AND**. L'SQL che Hibernate genera
per "netflix 12,99" (preso dal log, `logging.level.org.hibernate.SQL=debug`):

```sql
select ... from bank_transaction bt1_0
  left join category c1_0 on c1_0.id = bt1_0.category_id
  left join category mc1_0 on mc1_0.id = bt1_0.manual_category_id
where (lower(coalesce(bt1_0.description, ?)) like ? escape ''
       or lower(coalesce(bt1_0.counterparty, ?)) like ? escape ''
       or lower(coalesce(c1_0.name, ?)) like ? escape '')
  and (lower(coalesce(bt1_0.description, ?)) like ? escape ''
       or lower(coalesce(bt1_0.counterparty, ?)) like ? escape ''
       or lower(coalesce(c1_0.name, ?)) like ? escape ''
       or bt1_0.amount = ? or bt1_0.amount = ?)
order by bt1_0.booking_date desc, bt1_0.id desc
offset ? rows fetch first ? rows only
```

Tutti i valori sono `?`: parametri, mai testo incollato. E c'è **una query sola**: le categorie arrivano con i
movimenti (i due `left join`), senza le query in più del problema N+1.

### G4. Il "fetch" e il conteggio

Per mostrare il nome della categoria la carichiamo insieme al movimento: `root.fetch("category", LEFT)`, l'equivalente
di `LEFT JOIN FETCH`. Ma Spring Data, per sapere quante pagine ci sono, fa anche una query di **conteggio**
(`select count(...)`), e in un conteggio non si può "caricare" niente: Hibernate darebbe errore. Per questo la
Specification controlla `query.getResultType()`: se è `Long` (il conteggio) usa un join semplice, altrimenti il
fetch. In tutti e due i casi ottiene un `Join` con cui cercare nel nome della categoria (`category.get("name")`).

### G5. Le parole

`TransactionSearch.words` divide il testo negli spazi (anche più spazi di fila), in minuscolo. Il simbolo "€" staccato
si ignora e attaccato si toglie: "-12,99 €" e "12,99€" sono un importo. Al massimo **10 parole** (400 oltre), e il
limite di 100 caratteri resta.

### G6. L'ordine

L'ordine dei risultati ora è nella richiesta di pagina: `PageRequest.of(pagina, dimensione, Sort.by(desc("bookingDate"),
desc("id")))`. Spring Data lo trasforma in `order by`.

### G7. Test

| Test | Che cosa prova |
|---|---|
| `TransactionControllerTest.severalWordsMustAllBeThereInAnyOrderAndAnyField` | tutte le parole, in qualsiasi ordine; una parola nella causale e una nella categoria; una parola e un importo (anche con "€" staccato); spazi in più; troppe parole = 400 |
| `TransactionControllerTest.theSearchTextIsSplitIntoWords` | come si divide il testo |
| i test della Parte E | tutti ancora verdi: con una parola sola la ricerca fa esattamente quello di prima |
