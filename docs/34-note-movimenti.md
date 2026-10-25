# 34. La nota di un movimento

**Obiettivo:** poter scrivere una **nota** su un movimento ("regalo per Luca", "da dividere con Marco", "rimborsato
a gennaio"), vederla nell'elenco e **ritrovarla con la ricerca**. È la funzione delle note di N26 (vedi `docs/33`,
parte M); i tag di N26 invece non li facciamo.

| Parte | Cosa |
|---|---|
| A | che cosa è una nota, e che cosa non è |
| B | il database: una colonna e un vincolo (V27) |
| C | il backend: l'API e la pulizia del testo |
| D | la ricerca trova anche le note |
| E | la pagina Movimenti |
| F | sicurezza: testo, mai HTML |
| G | i test, e la prova nel browser |
| H | esercizi guidati |

---

## Parte A: che cosa è una nota, e che cosa non è

- È **dell'utente**: la banca non la conosce, la sincronizzazione e la classificazione non la toccano mai. Un
  movimento che la banca rimanda uguale (la stessa impronta, `docs/06`) non viene riscritto, quindi la nota resta.
- È **facoltativa** e **corta**: una riga, al massimo 300 caratteri (due righe sul telefono).
- **Non** decide niente: non cambia il tipo, la categoria, i totali. Serve a ricordare.

Per questo salvare una nota **non riclassifica** (al contrario della categoria scelta a mano, `docs/19`) e la pagina
non si ricarica: cambia solo quella riga.

---

## Parte B: il database (V27)

```sql
ALTER TABLE bank_transaction ADD COLUMN note TEXT;
ALTER TABLE bank_transaction ADD CONSTRAINT ck_bank_transaction_note_length CHECK (char_length(note) <= 300);
```

- `TEXT` senza `NOT NULL`: `NULL` vuol dire "nessuna nota". Non salviamo mai la stringa vuota `''`: il backend la
  trasforma in `NULL`, così "c'è una nota?" ha una risposta sola (`note IS NOT NULL`).
- Il **vincolo** ripete il limite del backend. Perché due volte? Il controllo del backend dà all'utente un messaggio
  chiaro; il vincolo del database è la **rete di sicurezza**: anche un errore nel codice, o un `UPDATE` scritto a
  mano, non può salvare un testo enorme. `char_length` conta i caratteri, non i byte: "è" vale uno.
- Il vincolo su `NULL`: `char_length(NULL) <= 300` è `NULL` ("non so"), e per PostgreSQL un CHECK fallisce solo se
  è **falso**. Quindi `NULL` passa, come vogliamo.

Nell'entità `BankTransaction` la colonna è un campo semplice:

```java
@Column(name = "note")
private String note;
```

con `getNote()` e `setNote(...)`. Nessuna logica nell'entità: la pulizia del testo sta nel service (Parte C).

---

## Parte C: il backend

### L'API

```
PUT /api/transactions/{id}/note
{"note": "regalo per Luca"}
```

Risponde con il movimento (`TransactionDto`, che ora ha il campo `note`). Con `{"note": null}` o un testo di soli
spazi la nota viene **tolta**. È lo stesso schema di `manual-category` e `manual-type`: un `PUT` sulla "parte" del
movimento che si cambia, con un piccolo record come corpo:

```java
@PutMapping("/{id}/note")
public TransactionDto changeNote(@PathVariable Long id, @RequestBody NoteChange change) {
    return transactionService.changeNote(id, change.note());
}

public record NoteChange(String note) {
}
```

Perché `PUT` e non `POST`? `PUT` vuol dire "metti questo valore": mandare due volte la stessa nota dà lo stesso
risultato (è **idempotente**). Un `POST` di solito crea qualcosa di nuovo a ogni chiamata.

### La pulizia del testo

```java
String cleaned = note == null ? "" : note.strip().replaceAll("\\s+", " ");
if (CONTROL.matcher(cleaned).find()) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La nota contiene caratteri non validi.");
}
if (cleaned.length() > MAX_NOTE_LENGTH) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "La nota è troppo lunga: al massimo " + MAX_NOTE_LENGTH + " caratteri.");
}
transaction.setNote(cleaned.isEmpty() ? null : cleaned);
```

Riga per riga:

1. `strip()` toglie gli spazi all'inizio e alla fine (anche quelli "speciali" Unicode, a differenza del vecchio
   `trim()`).
2. `replaceAll("\\s+", " ")`: ogni gruppo di spazi, tabulazioni e **a capo** diventa uno spazio solo. La nota è una
   riga: un "a capo" incollato da un'altra app non deve rompere l'elenco.
3. `CONTROL` è l'espressione regolare `[\p{Cntrl}&&[^\s]]`: "un carattere di controllo (`\p{Cntrl}`) **e** (`&&`)
   non uno spazio (`[^\s]`)". Gli spazi sono già stati sistemati al punto 2; restano caratteri come il "campanello"
   (`\u0007`) o lo zero (`\u0000`, che PostgreSQL nei testi rifiuta con un errore). Meglio un 400 chiaro.
4. La lunghezza si controlla **dopo** la pulizia: 300 spazi sono una nota vuota, non una nota troppo lunga.
5. Vuota → `null` (Parte B).

Un movimento che non esiste: `NotFoundException` → 404, come per le altre correzioni.

Il metodo è `@Transactional`: l'entità letta con `findById` è "gestita" da JPA, e alla fine della transazione
Hibernate scrive da solo l'`UPDATE` del campo cambiato. Non serve chiamare `save()` (`docs/09`).

---

## Parte D: la ricerca trova anche le note

La ricerca (`docs/32`, parte G) cerca ogni parola in un elenco di campi di testo. Per aggiungere la nota è bastato
aggiungerla all'elenco, in `TransactionSearch.of(...)`:

```java
conditions.add(wordAnywhere(cb, word, List.of(root.get("description"), root.get("counterparty"),
        category.get("name"), root.get("note")), root.get("amount")));
```

`wordAnywhere` ora riceve una **lista** di campi invece di tre parametri fissi: aggiungere un campo domani sarà
una riga sola. "amazon regalo" trova il movimento con la causale AMAZON e la nota "regalo per Luca": ogni parola
deve esserci, in un campo qualsiasi.

---

## Parte E: la pagina Movimenti

> Aggiornamento (`docs/37`): la nota ora si scrive nella **scheda del movimento**; nell'elenco si vede sotto il
> nome. Il funzionamento (API, pulizia, sicurezza) è lo stesso descritto qui.

Sotto la categoria di ogni movimento:

- senza nota: il collegamento **"aggiungi una nota"**, accanto a "modifica";
- con la nota: *Nota: regalo per Luca · modifica*;
- scrivendo: un campo di testo con **Salva** e **annulla** (anche il tasto Esc annulla; Invio salva, perché il
  campo è dentro un `<form>`).

```html
<form class="note-edit" (submit)="$event.preventDefault(); saveNote(t, noteInput.value)">
  <input #noteInput type="text" [value]="t.note ?? ''" [maxLength]="maxNoteLength" ... />
```

- `#noteInput` è una **variabile di template**: dà un nome all'elemento `<input>`, così il pulsante legge
  `noteInput.value` senza un signal per ogni tasto.
- `$event.preventDefault()`: senza, il browser "invierebbe" il form ricaricando la pagina.
- `maxLength` ferma la scrittura a 300 caratteri: il backend controlla lo stesso (Parte F).

Dopo il salvataggio la pagina **non** si ricarica: `saveNote` sostituisce solo quel movimento nell'elenco, con
`result.update(...)`, che produce una pagina nuova con un elemento cambiato (un signal si aggiorna con un valore
nuovo, non modificando quello vecchio). La nota mostrata è quella **restituita dal backend**, già ripulita.

Il segnaposto della ricerca ora dice anche "nota".

---

## Parte F: sicurezza, testo e mai HTML

Una nota è testo scritto da una persona e mostrato in una pagina: il caso tipico del **cross-site scripting**
(XSS). Se qualcuno riuscisse a salvare `<img src=x onerror="...">` e la pagina la inserisse come HTML, il codice
partirebbe nel browser.

Qui non succede perché Angular, con `{{ t.note }}`, inserisce sempre **testo** (`textContent`), mai HTML: i
caratteri `<` e `>` restano caratteri. Il test della pagina lo verifica: una nota `<b>regalo</b>` si vede come
scritta e non crea nessun elemento `<b>`. La regola da ricordare: **mai** `[innerHTML]` con testo dell'utente.

Le altre difese, già viste:

| Rischio | Difesa |
|---|---|
| SQL injection dalla nota o dalla ricerca | JPA salva il campo come parametro; la ricerca usa `cb.like` con un valore (`docs/32`) |
| testi enormi | 300 caratteri nel backend e nel database (V27), `maxLength` nella pagina |
| caratteri strani | spazi e a capo ridotti a uno spazio, caratteri di controllo rifiutati (400) |

La riga corrispondente è nella tabella della sicurezza (`docs/17`, riga 21).

---

## Parte G: i test, e la prova nel browser

Backend (`TransactionControllerTest`):

- `aNoteCanBeWrittenFoundAndRemoved`: gli spazi e l'a capo diventano uno spazio; una correzione del tipo (che
  riclassifica tutto) non tocca la nota; la ricerca la trova ("luca", e "amazon regalo" su due campi diversi); gli
  altri movimenti hanno `note: null`; vuota o `null` la tolgono, e la ricerca non la trova più.
- `aWrongNoteIsRejected`: 300 caratteri vanno, 301 no (400 con il messaggio); un carattere di controllo è un 400;
  un movimento che non esiste è un 404; le note rifiutate non hanno cambiato quella salvata.

Frontend (`transactions-page.spec.ts`): si scrive una nota con l'HTML dentro, si vede come testo, la pagina non si
ricarica; "modifica" riapre il campo con la nota; svuotarlo la toglie e torna "aggiungi una nota".

Nel browser (Playwright, sul telefono e sul PC): scrivere, salvare con Invio, ritrovare con la ricerca, togliere.

---

## Parte H: esercizi guidati

1. **Un a capo voluto.** Se volessi note su più righe: che cosa cambieresti nella pulizia (Parte C), nel campo
   (`<textarea>`) e nel CSS (`white-space: pre-line`)? Perché `{{ }}` resta comunque sicuro?
2. **Il vincolo alla prova.** Con `psql`, prova `UPDATE bank_transaction SET note = repeat('a', 301) WHERE id = …`.
   Che errore dà PostgreSQL? E con `note = NULL`?
3. **Il filtro "con nota".** Aggiungi a `TransactionFilter` un `boolean withNote` e, in `TransactionSearch.of`, la
   condizione `cb.isNotNull(root.get("note"))`. Scrivi prima il test.
