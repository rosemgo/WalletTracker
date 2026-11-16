# Lingue, seconda consegna: l'app in inglese

Dopo la prima consegna (docs/lingue-1-messaggi-e-formati.md), che preparava il terreno senza cambiare niente a schermo,
l'app si può usare in **italiano o in inglese**. L'italiano resta la lingua in cui l'app è scritta: chi la usa già non
vede nessuna differenza, tranne la nuova voce "Lingua" in Impostazioni.

Restano come sono, in qualsiasi lingua: i testi dei movimenti mandati dalle banche, i nomi e i testi delle regole di
partenza, quello che scrive l'utente (categorie rinominate, note, nomi dei conti), gli indirizzi delle pagine
(`/movimenti`), i log del backend.

## Parte A: di chi è la lingua

La lingua è un'impostazione **dell'installazione**, salvata nel database (`app_settings.language`, V42), non del
singolo browser. I motivi:

- l'app è di una persona sola, che la apre dal computer e dal telefono e la vuole uguale;
- i nomi delle categorie di partenza sono dati condivisi: non possono essere "Spesa alimentare" sul telefono e
  "Groceries" sul computer.

| Situazione | In che lingua si apre |
|---|---|
| Chi usa già l'app (aggiorna da una versione precedente) | italiano: la migrazione V42 lo scrive nel database |
| Installazione nuova, prima schermata | quella del browser (italiano o inglese; un browser tedesco → inglese) |
| Installazione nuova, dopo la prima schermata | quella della prima schermata, che diventa la lingua dell'installazione |
| Un altro dispositivo | quella dell'installazione, anche se il browser è in un'altra lingua |

Si cambia da **Impostazioni → Lingua**, in qualsiasi momento e nei due versi. La pagina si ricarica nella lingua nuova;
gli altri dispositivi se ne accorgono alla prossima apertura e si ricaricano da soli.

## Parte B: le categorie di partenza

Le 29 categorie di partenza (V12) hanno ora una **chiave fissa** (`category.builtin_key`, V42: `GROCERIES`,
`EATING_OUT`...) che dice quale categoria di partenza è, in ogni lingua. Il nome resta un dato dell'utente.

Cambiando lingua (`LanguageSwitch`, `CategoryService.translateBuiltin`):

- una categoria che ha **ancora il nome di partenza**, in una lingua qualsiasi, prende il nome nella lingua nuova:
  "Spesa alimentare" ↔ "Groceries";
- una categoria **rinominata dall'utente** resta come l'ha scritta lui ("Supermercato" resta "Supermercato");
- se il nome nuovo è **già preso** da una categoria dell'utente (ha creato una sua "Groceries"), quella di partenza
  resta com'è: due categorie con lo stesso nome non possono esistere, e vince quella dell'utente.

Movimenti, regole e budget puntano alla categoria per id: seguono da soli. I nomi in ogni lingua sono in
`BuiltinCategory`; `BuiltinCategoryTest` controlla che i nomi italiani siano proprio quelli inseriti dalla V12.

Una conseguenza voluta: non esiste mai un'app "mezza italiana e mezza inglese". Quello che resta nella lingua di prima è
solo ciò che l'utente ha scritto lui.

## Parte C: il backend

- `GET /api/language` (risponde anche senza PIN: la dashboard lo chiede prima di disegnare la pagina del PIN) e
  `PUT /api/language` (con il PIN, come ogni modifica).
- `messages_en.properties`: le 142 frasi del catalogo in inglese. La lingua di una risposta è quella dell'header
  `Accept-Language` se è una delle nostre, altrimenti l'italiano (`LanguageConfig`). La stessa regola vale nei filtri di
  sicurezza, che lavorano prima dei controller (`Messages.text(message, request)`).
- `MessageCatalogTest` controlla che i due cataloghi abbiano gli stessi codici e, frase per frase, gli stessi parametri.
- L'ordine alfabetico di categorie e budget segue la lingua dell'installazione.

## Parte D: la dashboard

**Una sola build per tutte le lingue.** L'avvio (`main.ts`) è in tre tempi:

1. si decide la lingua (`language.ts`): quella ricordata dal browser, subito; la prima volta quella dell'installazione,
   chiesta al server; in un'installazione nuova quella del browser;
2. si carica la sua traduzione (`translations.ts`) e la si consegna ad Angular (`loadTranslations`);
3. solo adesso si carica l'app (`bootstrap.ts`, con `import()`).

L'ordine conta: ogni frase dell'app viene scritta nella lingua giusta nel momento in cui il suo file viene caricato.
La traduzione inglese è un pezzo a parte, scaricato solo da chi usa l'inglese.

- `LanguageStore` tiene allineata la lingua della pagina con quella dell'installazione (vedi la tabella della parte A).
  Una difesa contro i giri a vuoto: se dopo una ricarica la pagina fosse ancora nella lingua vecchia, non ricarica una
  seconda volta.
- `languageInterceptor` dice al backend in che lingua rispondere: quella della pagina, non quella del browser. Senza,
  con il browser in inglese e l'app in italiano, gli errori arriverebbero in inglese in mezzo a una pagina italiana.
- `text/locale.ts`: la lingua della pagina decide anche i formati. L'inglese è quello d'Europa (`en-GB`): l'app lavora
  con banche europee, quindi 31/12/2026, l'orologio a 24 ore, €1,234.50.
- La scelta della lingua (`LanguageSection`) sta in Impostazioni e nella prima schermata. I nomi delle lingue non si
  traducono ("English", non "Inglese"): li riconosce anche chi non capisce la lingua della pagina.

## Parte E: come si traduce

I testi sono marcati nel codice: attributo `i18n` nei template, `` $localize`...` `` nel TypeScript. Angular li sa
elencare tutti e dà a ognuno un identificativo, calcolato dal testo.

`frontend/src/locale/en.json` ha, per ogni identificativo, la frase italiana (`it`, solo per chi traduce) e la
traduzione (`en`). Oggi sono **1082 testi**.

```bash
npm run i18n
```

dopo aver aggiunto o cambiato un testo: aggiorna `en.json`, aggiunge le frasi nuove con la traduzione vuota e dice
quali traduzioni non servono più (con il loro testo, da riusare). Poi si completano le traduzioni vuote.

```bash
npm run i18n:check
```

controlla che non manchi niente e che segnaposto, plurali e scelte corrispondano all'italiano. Lo lancia anche la CI:
un testo aggiunto senza traduzione fa fallire la build, invece di comparire in italiano in mezzo all'inglese.

**Le regole per scrivere un testo nuovo**, perché sia traducibile:

- una frase intera in un solo pezzo: non "più" o "meno" infilati dentro un'altra frase (in inglese l'ordine cambia);
- i plurali con la forma di Angular (`{n, plural, =1 {...} other {...}}`) o con `plural()`, mai con `n === 1 ? ... : ...`;
- le date con `localDate` / `formatLocalDate`, i numeri con le funzioni di Angular: mai formati scritti a mano;
- un testo dentro un'espressione del template (`{{ cond ? 'A' : 'B' }}`) non si può marcare: si usa
  `{cond, select, true {A} other {B}}`, o una proprietà con `$localize` nel componente.

## Parte F: le prove

- **Backend**: 503 test (tra cui `LanguageControllerTest`: lettura senza PIN, modifica con il PIN, categorie
  rinominate, nomi già presi, regole e budget che seguono, frasi nella lingua della richiesta) e `V42LinguaTest` per la
  migrazione.
- **Dashboard**: 350 test. Girano isolati file per file (`isolate` in angular.json), perché le frasi si scrivono una
  volta sola, quando il file viene caricato: `english.spec.ts` apre l'app in inglese e controlla etichette, date,
  importi, plurali e una pagina intera.
- **Dal vero**, con backend, database e browser veri (Playwright): un'installazione nuova scelta in inglese dalla prima
  schermata, la creazione del PIN, tutte le 13 pagine e la scheda di un movimento senza una parola italiana, il passaggio
  all'italiano e ritorno con una categoria rinominata, un secondo dispositivo che si riallinea da solo.
- **Confronto con la versione di prima**, in italiano, sugli stessi dati: le pagine hanno lo stesso testo e le stesse
  dimensioni. Le uniche differenze sono la nuova scelta della lingua (prima schermata e Impostazioni) e le etichette
  delle righe dei grafici, ora scritte con i formati della lingua (stesso testo, con lo spazio che non va a capo).

## Parte G: tornare alla versione solo italiana

Se ci si accorgesse di uno sbaglio, si può tornare alla versione di prima di questo lavoro:

- **nel codice**: tutto il lavoro sulle lingue sta in commit suoi, dopo `015560d` (l'ultimo commit di `main` prima di
  cominciare). Finché non è unito a `main`, `main` non cambia; dopo, si annulla con un `git revert` del merge;
- **nel database**: la V42 (la V41 è lo storico dei saldi, arrivato da `main`) aggiunge solo una tabella (`app_settings`) e una colonna (`category.builtin_key`), senza
  togliere né cambiare niente di quello che c'era. **Provato**: la versione di prima, avviata su un database già
  migrato dalla V42, parte (Flyway avvisa che il database è "più nuovo", ma non si ferma) e mostra le stesse pagine.
  E nel verso opposto: un database creato dalla versione di prima, con i suoi dati, passa alla nuova (V41 e V42) e
  resta in italiano, con le 29 categorie di partenza riconosciute.

Un'avvertenza: **prima di tornare indietro, rimetti la lingua in italiano** da Impostazioni. La versione di prima non sa
tradurre le categorie: se l'installazione fosse in inglese, resterebbero con i nomi inglesi ("Groceries").

## Che cosa resta in italiano (terza consegna)

- lo script di installazione, `GUIDA.md`, `LEGGIMI.txt`, `NOVITA.md`;
- il manifest dell'app installabile (`manifest.webmanifest`: nome, descrizione e scorciatoie) e la riga per chi ha
  JavaScript spento in `index.html`: sono file fissi, uno solo per tutte le lingue;
- l'errore dell'ultimo backup, scritto dallo script del container;
- i link alla guida, che punta alla `GUIDA.md` italiana.
