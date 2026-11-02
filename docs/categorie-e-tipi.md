# Tipi e categorie: quando la categoria serve

Fuori dalla numerazione, come `docs/saldi.md`. Nasce da un dubbio dell'utente: chi usa l'app per la prima volta
può non capire che a certi movimenti (un acquisto di titoli, un giroconto) la categoria non serve, perché la scheda
del movimento mostrava sempre "Categoria: Nessuna categoria", come un buco da riempire.

| Parte | Cosa |
|---|---|
| A | il principio |
| B | perché non copiare il tipo nella categoria |
| C | che cosa cambia nell'app |
| D | i test, e la prova nel browser |

---

## Parte A: il principio

Ogni movimento ha due informazioni (`docs/tinaba.md`, parte J):

- il **tipo** dice che cosa succede ai soldi (Spesa, Entrata, Acquisto titoli, Giroconto…), e decide in quali
  totali conta;
- la **categoria** dice *per che cosa*.

La categoria serve solo dove risponde a una domanda che il tipo lascia aperta:

| Tipo | La domanda aperta | Categoria |
|---|---|---|
| Spesa, Entrata | per che cosa? (Spesa alimentare, Stipendio…) | sì |
| Cedole, dividendi e interessi | quale rendita? (Cedole, Dividendi, Interessi) | sì |
| Tasse investimenti | quale tassa? (Ritenute, Imposta di bollo…) | sì |
| Acquisto e vendita titoli, Versamento e prelievo investimenti, Trasferimento interno, Saldo carta, Ignorato | nessuna: il tipo dice già tutto | non serve |

## Parte B: perché non copiare il tipo nella categoria

L'idea era uniformare: dare una categoria a tutti, per esempio "Acquisto titoli" a un Acquisto titoli. Non l'abbiamo
fatto, per tre motivi:

1. **Non aggiunge niente**: "Acquisto titoli · Acquisto titoli" dice due volte la stessa cosa.
2. **Due informazioni da tenere allineate**: se il tipo cambia ("Conta come", una regola), la categoria copiata
   resterebbe quella vecchia: un Prelievo investimenti con categoria "Acquisto titoli".
3. **Le rendite sono un'altra cosa**: lì la categoria dice davvero qualcosa in più (quale rendita).

Il problema era dell'interfaccia, ed è lì che si risolve. Se un giorno servisse una categoria anche per gli
acquisti, dovrebbe rispondere a una domanda nuova, per esempio *in che cosa* si è investito (ETF, Azioni,
Obbligazioni): sarebbe una funzione a parte.

## Parte C: che cosa cambia nell'app

### Un elenco solo, nel backend e nel frontend

```java
// TransactionType
public static final Set<TransactionType> WITH_CATEGORY = EnumSet.of(EXPENSE, INCOME, INVESTMENT_INCOME,
        INVESTMENT_TAX);
```

e nel frontend `needsCategory(type)` in `frontend/src/app/api/category-groups.ts`, con lo stesso elenco. Un movimento
ancora "Da verificare" la mostra: prima si sceglie il tipo.

### La scheda del movimento

Per un tipo che non ne ha bisogno, **senza** categoria, la riga diventa:

> **Categoria** · Non serve
> Per un acquisto di titoli non serve una categoria: il tipo dice già che cos'è. **Aggiungila comunque**

- la frase cambia con il tipo (`WITHOUT_CATEGORY`): "un versamento negli investimenti", "un trasferimento tra i tuoi
  conti", "il saldo della carta di credito"…;
- "Aggiungila comunque" apre lo stesso elenco delle categorie: chi la vuole la mette;
- il suggerimento di categoria per l'esercente non si mostra (non c'è niente da suggerire);
- se il movimento **ha già** una categoria (messa a mano o da una regola), si vede come sempre.

La riga è un `<div class="detail-row">` e non un pulsante: lo stile `.detail-row` vale per tutte e due (prima era
solo `button.detail-row`, e "Categoria" e "Non serve" finivano sulla stessa riga).

Anche il testo sotto una categoria mancante è più preciso: per una cedola senza categoria dice "sceglila tu, o crea
una regola" come per una spesa; per un movimento da verificare, "prima scegli il tipo, in «Conta come»".

### Il filtro "Senza categoria" di Movimenti

Elencava **tutti** i movimenti senza categoria, compresi acquisti, versamenti e giroconti: decine di movimenti "da
sistemare" che non lo erano. Ora `TransactionSearch` aggiunge la condizione:

```java
conditions.add(cb.isNull(root.get("category")));
conditions.add(root.get("type").in(TransactionType.WITH_CATEGORY));
```

Nella prova con un anno di dati inventati: 13 movimenti invece di una cinquantina, tutti spese vere.

L'elenco dei movimenti diceva già il tipo al posto di "senza categoria" per questi movimenti (`metaOf()`), e Analisi
ha "Senza categoria" solo per spese ed entrate: lì non cambia niente.

## Parte D: i test, e la prova nel browser

- `TransactionControllerTest.withoutCategoryShowsOnlyTheTypesThatNeedOne`: una spesa, una cedola, un acquisto di
  titoli e un giroconto senza categoria; il filtro dà solo la cedola e la spesa.
- `transaction-detail.spec.ts`: un acquisto di titoli dice "Non serve", con la frase, senza suggerimento, e
  "Aggiungila comunque" apre le categorie; una cedola senza categoria la chiede; un acquisto con una categoria la
  mostra.

Nel browser (telefono), la scheda di un "Buy trade ETF MONDO": "Categoria · Non serve", la frase, il link che apre
"Scegli una categoria"; nessun errore nella console.
