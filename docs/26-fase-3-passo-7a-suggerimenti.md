# 26. Fase 3, passo 7a: i suggerimenti di categoria dalla tua storia

**Obiettivo:** quando scegli a mano la categoria di un movimento, il programma propone la stessa categoria per
gli altri movimenti dello **stesso esercente** ancora senza categoria. Tu accetti (in un clic, per tutti) o
dici "no". Niente intelligenza artificiale: solo le tue scelte. È la base su cui, nel passo 7b, si potrà
aggiungere un modello che suggerisce anche per gli esercenti mai visti (vedi `docs/03`, sezione 8).

> Prerequisiti: passo 6 (`docs/25`) e `git pull`. Al primo avvio Flyway applica la migrazione **V15**.

Nella dashboard: pagina **Movimenti**, riquadro **Suggerimenti di categoria** in cima (compare solo se c'è
qualcosa da suggerire).

---

## Parte A: i concetti

### 1. Che cosa viene suggerito, e quando

```
Hai scelto a mano:                 PIZZERIA DA GINO 19/10   → Bar e ristoranti
Senza categoria:                   PIZZERIA DA GINO 05/10
                                   PIZZERIA DA GINO 12/10
Suggerimento:                      PIZZERIA DA GINO → Bar e ristoranti, 2 movimenti senza categoria
```

"Lo stesso esercente" lo decide `MerchantKey`, la stessa chiave degli abbonamenti (`docs/22`): maiuscole,
cifre e punteggiatura non contano, quindi "PIZZERIA DA GINO 05/10" e "Pizzeria da Gino 12/10" sono lo
stesso esercente.

Le regole di prudenza (il programma **suggerisce, non indovina**):

| Caso | Che cosa succede |
|---|---|
| hai scelto una sola categoria per l'esercente | suggerimento |
| hai scelto categorie **diverse** (Amazon: una volta Spesa alimentare, una volta Shopping) | **nessun** suggerimento: decidi tu movimento per movimento |
| hai detto "no" a quel suggerimento | non torna; se poi scegli un'altra categoria per lo stesso esercente, quella sì |
| non hai mai scelto la categoria per l'esercente | nessun suggerimento (lo farà, forse, l'IA del passo 7b) |

Si suggerisce solo per **spese ed entrate** senza categoria: trasferimenti, investimenti e movimenti ignorati
non ne hanno bisogno, e quelli "da verificare" prima devono avere un tipo.

Finché non accetti, un suggerimento **non conta**: il movimento resta senza categoria nel riepilogo e nei
budget.

### 2. "Accetta" e "no"

- **Accetta** assegna la categoria a tutti i movimenti senza categoria di quell'esercente, come se
  l'avessi scelta a mano uno per uno (diventa la loro "categoria scelta a mano"). Poi il motore riclassifica.
- **No** salva il rifiuto: per quell'esercente, quella categoria non va più proposta.

Un suggerimento accettato vale per i movimenti **di adesso**. Per quelli futuri dello stesso esercente
arriverà di nuovo un suggerimento; se preferisci che vengano categorizzati da soli, crea una regola dal
movimento ("crea una regola", `docs/19`).

### 3. Calcolati al momento, non salvati

Nel ragionamento del passo 7 avevamo previsto una tabella dei suggerimenti. Per quelli "dalla storia" non
serve: si **calcolano a ogni richiesta** dalle tue scelte. Il vantaggio: sono sempre aggiornati. Se cambi la
categoria scelta per un movimento, il suggerimento la segue, senza niente da tenere allineato.

Si salva solo il **"no"** (V15):

```sql
CREATE TABLE category_suggestion_dismissal (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    merchant_key TEXT        NOT NULL,
    category_id  BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_suggestion_dismissal UNIQUE (merchant_key, category_id),
    CONSTRAINT fk_suggestion_dismissal_category FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE CASCADE
);
```

- `UNIQUE (merchant_key, category_id)`: dire "no" due volte non crea due righe;
- `ON DELETE CASCADE`: eliminando una categoria, i "no" che la riguardano non servono più e spariscono con
  lei.

Una tabella dei suggerimenti salvati servirà nel passo 7b: le risposte di un modello costano tempo e vanno
ricordate.

### 4. Quanto costa: tre query

`GET /api/category-suggestions` fa **tre query**, qualunque sia il numero di movimenti:

1. i movimenti con una categoria scelta a mano, **con** la categoria (`JOIN FETCH`, `docs/24`);
2. i movimenti senza categoria (spese ed entrate);
3. i "no".

Il resto (raggruppare per esercente, contare, scartare) lo fa `HistorySuggester` in memoria. Il conto è
verificato accendendo il registro delle query (`--logging.level.org.hibernate.SQL=debug`): 3.

Un dettaglio: per i "no" ci serve l'id della categoria, e `d.getCategory().getId()` **non** carica la
categoria. Hibernate l'id lo conosce già, perché è la colonna `category_id` della riga.

---

## Parte B: il codice

**Backend** (pacchetto `suggestion`)

| Classe | Che cosa fa |
|---|---|
| `HistorySuggester` | la logica, "pura" (niente database né Spring): esempi + movimenti senza categoria + "no" → suggerimenti |
| `SuggestionService` | legge i dati (le tre query), applica "accetta" e "no" |
| `SuggestionController` | `GET /api/category-suggestions`, `POST .../accept`, `POST .../dismissed` |
| `SuggestionDismissal` | l'entità del "no" (V15), con `@ManyToOne` verso la categoria |

**Frontend**

- `suggestions/suggestions-panel`: il riquadro, un componente a sé. Quando accetti, avvisa la pagina con un
  **evento** (`output()`): `<app-suggestions-panel (accepted)="load()" />`. La pagina ricarica l'elenco,
  perché alcuni movimenti ora hanno una categoria.
- Nell'altro verso, la pagina Movimenti chiama il pannello con `viewChild(SuggestionsPanel)`: quando scegli
  a mano una categoria, chiede al pannello di ricaricarsi (`reload()`), perché la tua scelta può far nascere
  un suggerimento.

`output()` va dal figlio al genitore (un evento), `viewChild()` dal genitore al figlio (una chiamata): sono i
due modi in cui due componenti Angular si parlano.

**Una correzione già che c'eravamo:** sul telefono la pagina Movimenti scorreva in orizzontale (la tabella e
i filtri uscivano dallo schermo). Ora la tabella scorre dentro il suo riquadro, come nelle altre pagine, e i
filtri vanno a capo.

---

## Parte C: i test

| Test | Che cosa verifica |
|---|---|
| `HistorySuggesterTest` (6) | stesso esercente → suggerimento; scelte diverse → niente; esempi contati; un "no" non torna, ma un'altra categoria sì; esercenti mai categorizzati; ordine (prima chi ha più movimenti) |
| `SuggestionControllerTest` (5) | l'elenco; "accetta" assegna la categoria a tutti i movimenti dell'esercente (e solo a quelli); "no" salvato una volta sola; scelte diverse; categoria inesistente o esercente vuoto → 400 |
| `suggestions-panel.spec.ts` (5) | il riquadro, accetta (con l'evento per la pagina), no (senza evento), niente da suggerire, errore |
| `transactions-page.spec.ts` | dopo una scelta a mano il pannello si ricarica |

Nel browser: una scelta a mano fa comparire il suggerimento senza ricaricare la pagina; "accetta" assegna la
categoria ai due movimenti rimasti; "no" non torna nemmeno ricaricando; la pagina sul telefono non scorre
in orizzontale.

---

## Parte D: provalo

1. `git pull`, riavvia il backend (Flyway applica la V15) e la dashboard.
2. Nella pagina **Movimenti** trova un esercente con più movimenti senza categoria (un bar, un
   supermercato). Scegli a mano la categoria per **uno** di loro.
3. In cima compare il riquadro: "2 movimenti senza categoria · l'hai scelta per 1 movimento". Premi
   **Accetta**: tutti hanno la categoria.
4. Fai lo stesso con un altro esercente e premi **no**. Ricarica la pagina: il suggerimento non torna.

### Esercizi

1. Scegli due categorie diverse per due movimenti dello stesso esercente. Che cosa succede al
   suggerimento? Perché è la scelta prudente?
2. In `psql`: `SELECT * FROM category_suggestion_dismissal;`. Poi elimina la categoria di un "no" dalla
   pagina Categorie e rileggi la tabella. Che cosa è successo, e quale riga della V15 lo fa?
3. Togli `JOIN FETCH` da `findAllWithManualCategory` e accendi il registro delle query. Quante query fa ora
   `GET /api/category-suggestions` con 10 categorie scelte a mano?
