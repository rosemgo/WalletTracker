# Lingue, prima consegna: messaggi, plurali e date

L'app oggi è solo in italiano. Questa consegna **non cambia niente a schermo**: prepara il terreno perché una seconda
lingua (l'inglese) si possa aggiungere senza riscrivere, e toglie alcune cose scritte "a mano" che valevano solo per
l'italiano. Le consegne successive: (2) l'inglese nell'app, (3) installatore, guida e novità.

## Parte A: il backend manda codici, non frasi

**Prima.** Le frasi nascevano nel Java, dove serviva l'errore:

```java
throw new ResponseStatusException(HttpStatus.CONFLICT, "Esiste già una categoria \"" + name + "\".");
```

Con due lingue non regge: la frase è già scritta prima di sapere chi la leggerà. Ed è peggio per i messaggi che si
**salvano**: l'errore dell'ultimo aggiornamento di un conto lo scrive il timer di notte e lo si legge il giorno dopo.

**Adesso.** Il Java dice *quale* frase serve e con quali valori; la frase sta in un catalogo e nasce quando la
risposta esce:

```java
// messaggio: Esiste già una categoria "{0}".
throw new ApiException(HttpStatus.CONFLICT, "category.duplicate", existing.getName());
```

Il commento `// messaggio:` riporta la frase italiana accanto a ogni codice: si legge che cosa vede l'utente senza
aprire il catalogo.

| Classe (`it.wallettracker.message`) | A che cosa serve |
|---|---|
| `Message` | codice + parametri. Un parametro può essere un altro messaggio ("Riga 3: manca la data.") |
| `Messages` | dal messaggio alla frase, nella lingua della richiesta (`MessageSource` di Spring) |
| `ApiException` | un errore HTTP con un messaggio: prende il posto di `ResponseStatusException("frase")` |
| `MessageException` | lo stesso per il codice che non sa di HTTP (chi legge un file) |
| `MessageSerializer` | nei DTO un campo `Message` diventa, nel JSON, la sua frase |
| `MessageConverter` | in una colonna il messaggio si salva come JSON: `{"code":"sync.bank-error","args":[404]}` |

Il catalogo è `backend/src/main/resources/messages.properties` (141 frasi). Per l'inglese basterà un file
`messages_en.properties` con gli stessi codici. Le regole per scriverlo (l'apostrofo due volte, `{0,number,#}` per i
numeri senza punto delle migliaia, i plurali con `choice`) sono in cima al file.

Che cosa cambia per la dashboard: niente, tranne un campo in più nel corpo degli errori, `"code"`.

**Le frasi già salvate.** `account.last_sync_error` conteneva una frase; ora contiene il JSON. Le righe vecchie si
leggono così come sono, e spariscono al primo aggiornamento del conto, che riscrive la colonna.

**I test.** `MessageCatalogTest` controlla che ogni codice usato nel Java sia nel catalogo e viceversa, e che ogni
frase sia scritta bene. I test che controllavano una frase la controllano ancora, passando dal catalogo:
`.satisfies(says("non trovato"))` al posto di `.hasMessageContaining(...)`.

**Che cosa resta in italiano, apposta.** I log; i testi delle banche e dei servizi esterni, mostrati così come sono
(`Message.verbatim`); l'errore dell'ultimo backup, che lo scrive lo script del container (terza consegna).

## Parte B: Investimenti, le voci senza nome

"Altre rendite", "Senza nome del titolo", "Ritenute senza la loro rendita nel periodo" e "Senza categoria" erano frasi
scritte dal backend, che la dashboard riconosceva **confrontando il testo** per scegliere il link. Adesso ogni voce ha
un tipo (`GroupKind`), il nome è `null` per quelle che non ne hanno uno, e la frase la scrive la dashboard
(`INVESTMENT_GROUP_LABELS`). È il "limite che resta" di docs/investimenti.md, chiuso.

## Parte C: i plurali

Erano scritti a mano in una cinquantina di punti: `n === 1 ? '1 movimento' : n + ' movimenti'`. Funziona in italiano e
in inglese, non in generale (il polacco ha quattro forme). Adesso:

- nei template, la forma standard di Angular, dentro un elemento con `i18n`:
  `{n, plural, =1 {1 movimento} other {{{ n }} movimenti}}`
- nel TypeScript, `plural(n, { one: '1 movimento', other: `${n} movimenti` })` (`text/plural.ts`), che chiede la forma
  giusta alle regole della lingua (`Intl.PluralRules`).

Per i plurali nei template serve `@angular/localize`, lo strumento ufficiale di Angular per le lingue: è stato
aggiunto ora. **Prova fatta:** su Angular 22 una traduzione caricata all'avvio (`loadTranslations`) traduce testi,
plurali e frasi con parametri con **una sola build**. È la strada della seconda consegna.

Una trappola incontrata: tra due elementi Angular toglie lo spazio da solo. Dove serve si scrive `&ngsp;`.

## Parte D: le date e i numeri

- La lingua dell'app sta in un posto solo, `text/locale.ts` (`APP_LOCALE`): prima `'it-IT'` era ripetuto in quattro file.
- I formati delle date hanno un nome (`text/dates.ts`): nelle pagine `{{ data | localDate: 'numeric' }}` al posto di
  `{{ data | date: 'dd/MM/yyyy' }}`. Dove Angular ha un formato suo per la lingua (`mediumDate`, `longDate`,
  `fullDate`) si usa quello.
- Spariti gli elenchi dei mesi e dei giorni scritti a mano (`day-groups.ts`, `explore-labels.ts`) e i "Gen",
  "Gennaio" nel codice: i nomi li danno i dati della lingua.
- Le righe del grafico del mese ("1.000 €") e la dimensione dei backup usano i formati della lingua.

## Che cosa non è cambiato

Nessuna frase, nessun formato a schermo. I test della dashboard che controllano i testi (circa 700 controlli) passano
senza modifiche, tranne due adattati: le voci di Investimenti e lo spazio che non va a capo in "1.000 €".
