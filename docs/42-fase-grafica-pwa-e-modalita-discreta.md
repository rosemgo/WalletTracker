# 42. Fase grafica, passo 7: l'app installabile (PWA) e la modalità discreta

**Obiettivo:** le ultime due cose della fase grafica (`docs/33`, parte K):

- **la modalità discreta**: un tocco sull'occhio nasconde gli importi, per quando qualcuno può vedere lo schermo
  (in metro, in ufficio); la prossima apertura la ricorda;
- **l'app installabile** (PWA, *Progressive Web App*): un'icona nella schermata Home o nel menu del computer, si apre
  a schermo intero come un'app, e senza rete mostra gli ultimi dati scaricati.

| Parte | Cosa |
|---|---|
| A | che cosa cambia per chi usa l'app |
| B | la modalità discreta |
| C | che cos'è una PWA, e il service worker |
| D | il manifest e le icone |
| E | senza rete, e le versioni nuove |
| F | provarla adesso sul computer (e perché sul telefono servirà la Fase 4) |
| G | sicurezza |
| H | i test, e la prova nel browser |
| I | esercizi guidati |

---

## Parte A: che cosa cambia per chi usa l'app

- **Sul telefono**: in alto, il nome dell'app e l'**occhio**. Toccato, ogni importo diventa "••• €" in tutte le
  pagine, finché non lo si tocca di nuovo; l'occhio diventa barrato. Chiudendo e riaprendo l'app, resta com'era.
- **Sul PC**: lo stesso comando è in fondo alla colonna di sinistra, "Modalità discreta" con un interruttore.
- **Installarla**: dal menu del browser, "Installa app" (Chrome, Edge) o "Aggiungi alla schermata Home" (Safari e
  Chrome sul telefono). Si apre senza la barra dell'indirizzo, con la sua icona.
- **Senza rete**: le pagine già viste si aprono con gli ultimi dati, e un avviso giallo lo dice.
- **Una versione nuova**: un avviso "È pronta una nuova versione di WalletTracker · Aggiorna".

---

## Parte B: la modalità discreta

### Sostituire, non sfocare

La prima idea è sfocare le cifre con il CSS (`filter: blur(...)`). Due problemi:

- una cifra grande e sfocata a volte si legge lo stesso;
- la cifra resta nella pagina: copiando il testo o con un lettore di schermo esce intera.

Allora le cifre si **sostituiscono**: "1.284 €" diventa "••• €". Il simbolo della valuta resta, così la pagina non
cambia forma e si capisce che lì c'è un importo. Va via anche il **segno**: "+" e "−" dicono già qualcosa (entrata o
uscita, sopra o sotto il solito).

```ts
export function hideAmounts(text: string): string {
  return text.replace(/[-+−]?\d[\d.,]*/g, HIDDEN).replace(/[-+−](?=•)/g, '');
}
```

Una cifra con le cifre, i punti e le virgole che seguono ("1.284", "-76,29") diventa `•••`. La seconda sostituzione
toglie un segno rimasto davanti a cifre già nascoste. Succede quando una frase si compone mettendo "+" davanti a un
importo già passato di qui ("+17 € sul solito").

### Lo stato: `PrivacyStore`

È fatto come `ThemeStore` (`docs/35`, parte C):

- un **signal** `discreet`;
- `toggle()` per cambiarlo;
- la scelta ricordata nel `localStorage`, dentro un `try/catch`: in navigazione privata la modalità funziona lo
  stesso, solo non è ricordata.

### Due strumenti per nascondere

Gli importi arrivano sullo schermo in due modi, e ognuno ha il suo strumento.

**1. Nei template, con la pipe.** Prima era `{{ t.amount | currency: t.currency }}`, ora:

```html
{{ t.amount | money: t.currency }}
```

```ts
@Pipe({ name: 'money', pure: false })
export class MoneyPipe implements PipeTransform {
  private readonly privacy = inject(PrivacyStore);
  private readonly currency = new CurrencyPipe(inject(LOCALE_ID), inject(DEFAULT_CURRENCY_CODE));

  transform(value, currencyCode?, display = 'symbol', digitsInfo?) {
    const text = this.currency.transform(value, currencyCode, display, digitsInfo);
    return text !== null && this.privacy.discreet() ? hideAmounts(text) : text;
  }
}
```

- **Composizione, non ereditarietà.** La prima versione era `class MoneyPipe extends CurrencyPipe`, ma TypeScript la
  rifiutava. `transform` di CurrencyPipe ha più "firme" (overload: una per un numero, una per `null`...), e un metodo
  che la sostituisce deve rispettarle tutte. Usare una CurrencyPipe *dentro* la nostra è più semplice: stessi
  parametri, stesso risultato, nessun vincolo. La regola generale: si preferisce la composizione all'ereditarietà.
- **`pure: false`.** Una pipe "pura" (quella normale) viene ricalcolata solo quando cambiano i suoi parametri.
  Qui il risultato dipende anche dalla modalità, che non è un parametro: senza `pure: false`, toccando l'occhio
  gli importi resterebbero visibili. Formattare un numero costa pochissimo, ricalcolarla spesso non pesa.

Le 32 pipe `currency` dei template sono diventate `money`: una sostituzione meccanica, e i risultati sono identici
(i test non sono cambiati).

**2. Nelle frasi costruite nel codice.** "25 € in meno del solito", "media 709 €", l'etichetta del grafico mentre si
scorre: le scrivono le funzioni `euros()` dei componenti. Il loro risultato ora passa da `privacy.hide()`:

```ts
private euros(value: number, currency: string): string {
  return this.privacy.hide(
    formatCurrency(value, this.locale, currency === 'EUR' ? '€' : currency, currency, '1.0-0'),
  );
}
```

`hide()` legge il signal `discreet`. Le frasi sono calcolate dentro dei `computed()`, e un `computed` si ricalcola
quando cambia un signal che ha letto. Quindi, toccando l'occhio, si aggiornano da sole: nessun codice in più per
"avvisarle".

### L'occhio, e il ruolo giusto per un interruttore

```html
<button type="button" class="icon eye" role="switch"
        aria-label="Modalità discreta: nasconde gli importi"
        [attr.aria-checked]="privacy.discreet()" (click)="privacy.toggle()">
```

- **`role="switch"` con `aria-checked`.** Il pulsante accende e spegne una modalità: è un interruttore. Il lettore di
  schermo dice "Modalità discreta, interruttore, attivato".
- **Il nome non cambia mai.** Un pulsante che si chiama a volte "Nascondi" e a volte "Mostra", con anche uno stato
  "premuto", si capisce al contrario la metà delle volte. Lo stato lo dice `aria-checked`; il suggerimento al
  passaggio del mouse (`title`) dice che cosa farà il tocco.
- **Il disegno è uno solo.** L'occhio, aperto o barrato, è in un `<ng-template #eyeIcon>` usato in due posti, la
  barra del telefono e la colonna del PC, con `*ngTemplateOutlet`.

Sul PC l'interruttore è disegnato: un binario con un pallino, che con `transform: translateX(14px)` scorre a destra
quando è acceso.

### Che cosa **non** nasconde

- **Le percentuali** ("35%") e **i grafici**: dicono le proporzioni, non quanto hai.
- **I testi scritti dal backend**, per esempio gli avvisi. Oggi nessuno contiene importi; uno nuovo che li contenesse
  andrebbe fatto passare da `hide()`.
- **I campi dei moduli** mentre si scrive un importo (il budget da creare): lo si sta scrivendo, lo si vede.

---

## Parte C: che cos'è una PWA, e il service worker

Una PWA è un sito con tre cose in più:

1. **https** (o `localhost`);
2. un **manifest**: nome, icone, colori, come aprirsi (Parte D);
3. un **service worker**: un piccolo programma JavaScript che il browser installa accanto al sito. Si mette **in
   mezzo** tra la pagina e la rete: ogni richiesta passa da lui, e lui decide se rispondere dalla sua memoria o
   chiedere al server.

Non si scrive a mano: Angular ha il suo, `@angular/service-worker` (la stessa versione di Angular). Lo si configura
con un file, `ngsw-config.json`, e la build produce il programma (`ngsw-worker.js`) e l'elenco dei file con le loro
impronte (`ngsw.json`).

```ts
provideServiceWorker('ngsw-worker.js', {
  enabled: !isDevMode(),
  registrationStrategy: 'registerWhenStable:30000',
}),
```

- **Solo nella build di produzione** (`!isDevMode()`): mentre si programma con `ng serve`, un service worker che
  serve i file di prima darebbe solo fastidio.
- **Si registra quando l'app è "stabile"** (ha finito di caricare), o dopo 30 secondi: prima conta che la pagina si
  apra in fretta.
- In `angular.json`, nella configurazione `production`: `"serviceWorker": "ngsw-config.json"`.

### `ngsw-config.json`: che cosa tenere in memoria

```json
"assetGroups": [
  { "name": "app", "installMode": "prefetch",
    "resources": { "files": ["/index.html", "/manifest.webmanifest", "/*.css", "/*.js"] } },
  { "name": "assets", "installMode": "lazy", "updateMode": "prefetch",
    "resources": { "files": ["/icons/**", "/media/**", "/**/*.(svg|png|webp|woff2|woff)"] } }
]
```

- **app**, `prefetch`: tutto il programma (HTML, CSS, JavaScript, anche le pagine caricate "pigre" di `docs/36`) è
  scaricato **subito**, alla prima visita. Così tutte le pagine si aprono anche senza rete.
- **assets**, `lazy`: le icone e i caratteri si scaricano solo quando servono, e poi restano.

```json
"dataGroups": [{
  "name": "dati",
  "urls": ["/api/overview**", "/api/alerts**", "/api/transactions**", "/api/explore/**",
           "/api/categories**", "/api/accounts**", "/api/budgets**", "/api/subscriptions**"],
  "cacheConfig": { "strategy": "freshness", "timeout": "4s", "maxAge": "7d", "maxSize": 300 }
}]
```

Le **risposte dell'API** che si possono mostrare senza rete. Due strategie possibili:

| Strategia | Che cosa fa | Per |
|---|---|---|
| `performance` | risponde dalla memoria se c'è, la rete solo se manca | dati che cambiano poco |
| `freshness` | chiede **sempre** alla rete; la memoria solo se la rete non risponde (o dopo `timeout`) | dati che devono essere quelli di adesso |

Per dei conti in banca vale `freshness`: con la rete si vedono sempre i dati veri; con la rete lenta, dopo 4
secondi, gli ultimi avuti; senza rete, subito quelli. `maxAge: 7d`: dati più vecchi di una settimana non si mostrano.

Restano **fuori**, di proposito:
- l'IA e le Impostazioni (`/api/ai`, `/api/setup`);
- i collegamenti alle banche e la sincronizzazione (`/api/connections`, `/api/sync`, `/api/banks`).

Senza rete non avrebbero senso, e meno dati restano sul dispositivo, meglio è (Parte G). Il service worker in
memoria tiene solo le **letture** (GET): una modifica (POST, PUT, DELETE) va sempre alla rete.

Due dettagli trovati provando:
- `**` senza una "/" accanto non attraversa le "/" del percorso (diventa `[^/]*`). Funziona lo stesso per
  `/api/transactions/183` perché il service worker cerca il modello **dentro** l'indirizzo, senza ancorarlo
  all'inizio e alla fine. L'abbiamo verificato nel browser (Parte H), non dato per scontato;
- `navigationUrls` esclude `/api/**`: se qualcuno apre un indirizzo dell'API nella barra del browser, non deve
  ricevere la pagina dell'app al posto della risposta.

---

## Parte D: il manifest e le icone

`public/manifest.webmanifest`, collegato in `index.html` con `<link rel="manifest">`:

```json
{
  "name": "WalletTracker",
  "short_name": "Wallet",
  "lang": "it",
  "start_url": "/riepilogo",
  "display": "standalone",
  "theme_color": "#f2f3f6",
  "icons": [
    { "src": "icons/icon-192.png", "sizes": "192x192", "purpose": "any" },
    { "src": "icons/icon-512.png", "sizes": "512x512", "purpose": "any" },
    { "src": "icons/icon-maskable-512.png", "sizes": "512x512", "purpose": "maskable" }
  ],
  "shortcuts": [{ "name": "Movimenti", "url": "/movimenti" }, { "name": "Analisi", "url": "/analisi" }]
}
```

- **`short_name`**: il nome sotto l'icona, dove c'è poco spazio.
- **`display: standalone`**: senza la barra dell'indirizzo, come un'app.
- **`start_url`**: dove si apre.
- **`shortcuts`**: tenendo premuta l'icona (Android) o con il tasto destro (computer), le scorciatoie per
  Movimenti e Analisi.

**Le icone.** Il disegno è uno solo, `icons/icon.svg`: il quadrato con la sfumatura dell'accento e una W disegnata
come **linea**, non come lettera. Una lettera dipenderebbe dal carattere installato su chi la disegna. Le misure PNG
(192, 512, 180 per iOS, 32 per la scheda del browser) le ha fatte Chromium, aprendo l'SVG e fotografandolo con
Playwright: niente programmi di grafica.

**"Maskable".** Android ritaglia le icone con la forma che sceglie il telefono (cerchio, goccia, quadrato
arrotondato). Un'icona "maskable" ha il fondo fino ai bordi e il disegno nel **cerchio centrale** (l'80%): qualunque
ritaglio la lascia intera. Per questo ce ne sono due versioni: `icon.svg` con gli angoli arrotondati, per dove si
mostra così com'è, e `icon-maskable.svg`.

In `index.html` sono arrivati anche:
- l'icona SVG per le schede del browser (al posto del logo di Angular);
- `apple-touch-icon` e i `meta` per iOS, che il manifest lo legge solo in parte;
- un `<noscript>` per chi ha JavaScript spento.

Nella barra in alto del telefono, `padding-top: env(safe-area-inset-top)` lascia il posto alla "tacca" quando l'app
è aperta a schermo intero.

---

## Parte E: senza rete, e le versioni nuove

`pwa/app-status.ts` segue due cose, con due signal, e `app.html` mostra gli avvisi.

**La rete.** Il browser dice se è collegato (`navigator.onLine`) e avvisa quando cambia (gli eventi `online` e
`offline`). Senza rete compare: "Senza rete: stai vedendo gli ultimi dati scaricati, e per ora non si possono fare
modifiche." Dirlo è importante: dati di ieri scambiati per quelli di oggi sono peggio di nessun dato.

**Le versioni nuove.** Quando l'app si apre, il service worker scarica `ngsw.json` dal server e lo confronta con il
suo. Se è cambiato, scarica i file nuovi in sottofondo, e quando sono tutti pronti avvisa:

```ts
this.updates.versionUpdates
  .pipe(filter((event) => event.type === 'VERSION_READY'))
  .subscribe(() => this.updateReady.set(true));
```

La pagina mostra "È pronta una nuova versione · Aggiorna", e il tocco ricarica. Perché non ricaricare da soli?
Chi sta scrivendo una nota perderebbe il testo.

Due casi in più:
- **l'app torna in primo piano** (`visibilitychange`): un'app installata sul telefono resta aperta per giorni, e
  allora controlliamo anche lì (`checkForUpdate`);
- **`unrecoverable`**: la versione in uso non si può più completare, perché un file è sparito dal server. Si
  propone di ricaricare con lo stesso avviso.

`SwUpdate` si chiede con `inject(SwUpdate, { optional: true })`: nei test e con `ng serve` il service worker non c'è,
e così il servizio funziona lo stesso (non fa niente).

---

## Parte F: provarla adesso sul computer

```bash
cd frontend
npm run pwa        # ng build, poi la build servita su http://localhost:4300 (il backend deve essere avviato)
```

Poi in Chrome o Edge: `http://localhost:4300`, e nella barra dell'indirizzo l'icona "Installa".

`scripts/serve-dist.mjs` è un piccolo server scritto con i soli moduli di Node:

- manda i file della build;
- un indirizzo che non è un file (`/movimenti/12`) riceve `index.html`, che poi passa la palla al router di Angular;
- le richieste `/api` le passa al backend (come `proxy.conf.json` per `ng serve`).

Perché non `ng serve`, e perché `http`:

- il service worker esiste solo nella build di produzione;
- un service worker si registra solo su `https` **con un certificato valido**, oppure su `localhost`, che il
  browser considera sicuro anche senza certificato. Il certificato fatto da sé di `ng serve --ssl` non basta: la
  registrazione fallisce.

**E sul telefono?** Il telefono non è `localhost`: servono un indirizzo raggiungibile e un certificato vero. È il
lavoro della **Fase 4**: con Tailscale il computer di casa ha un nome e un certificato https validi. Da lì
l'installazione sul telefono è "Aggiungi alla schermata Home". L'app è già pronta: manca solo la strada.

`npm start` resta com'era (`https://localhost:8443`, senza service worker), anche per il ritorno da Enable Banking
(`/callback`).

---

## Parte G: sicurezza

| Rischio | Difesa |
|---|---|
| qualcuno guarda lo schermo | la modalità discreta sostituisce le cifre (non le sfoca): non restano nella pagina |
| dati sul dispositivo | in memoria solo le letture delle pagine principali, al massimo 7 giorni e 300 risposte; niente Impostazioni, IA, collegamenti, sincronizzazione. Restano nel profilo del browser come la cronologia: chi usa lo stesso profilo li vede, come vedrebbe l'app aperta. Per cancellarli: Impostazioni del browser → dati dei siti |
| dati vecchi presi per buoni | strategia `freshness` (la rete prima), avviso "Senza rete" |
| un service worker che non si aggiorna | `index.html`, `ngsw.json` e il worker sempre ricontrollati (`no-cache`); avviso "Aggiorna" |
| "path traversal" nel server locale (`/..%2F..%2Fetc/passwd`) | il percorso è risolto (`normalize`) e deve restare dentro la cartella della build, altrimenti 404; provato anche con la "/" codificata `%2F`, che il parser degli URL non risolve |
| il server locale raggiungibile da altri | ascolta solo su `127.0.0.1`; `X-Content-Type-Options: nosniff` |

Le righe nuove sono nella tabella della sicurezza (`docs/17`, righe 25-27).

---

## Parte H: i test, e la prova nel browser

Frontend, 187 test (180 prima):

- `privacy.spec.ts`:
  - le cifre e i segni nascosti, il resto del testo uguale;
  - una pagina di prova con la pipe e una frase calcolata: toccando l'interruttore si aggiornano tutte e due da sole;
  - la scelta ricordata a una nuova apertura;
  - con l'archiviazione bloccata la modalità funziona lo stesso;
- `app.spec.ts`: l'occhio è un interruttore, cambia stato, e quello della colonna del PC lo segue;
- `app-status.spec.ts`:
  - la rete che va e viene;
  - senza service worker nessuna versione nuova;
  - con un SwUpdate finto: `VERSION_DETECTED` non basta, `VERSION_READY` sì; tornando in primo piano, un controllo.

Nel browser (Playwright):

1. **Modalità discreta** (con `ng serve`): accesa nel Riepilogo, poi in Movimenti, Analisi, Budget, Abbonamenti.
   Nel testo delle pagine nessuna cifra seguita da "€", tranne l'esempio scritto nel Budget ("ad esempio 400 € al
   mese"). Due correzioni trovate così:
   - il "+" restava davanti a "••• € sul solito" (Parte B);
   - sul PC l'occhio da solo in una riga sprecava spazio, ed è finito nella colonna.
2. **PWA** (con `npm run pwa`):
   - il service worker si registra, e dalla seconda apertura risponde lui;
   - Chrome non segnala nessun ostacolo all'installazione (`Page.getInstallabilityErrors` vuoto, con un profilo
     normale: in incognito l'unico "errore" è proprio l'incognito);
   - cambiando `ngsw.json` sul server, l'avviso "Aggiorna" compare.
3. **Senza rete, davvero.** La prima prova usava `setOffline` di Playwright. In Chromium però non vale per le
   richieste del service worker: i dati "senza rete" arrivavano dalla rete. La prova vera **spegne il server**:
   - Riepilogo, dettaglio di un movimento e Analisi si aprono con gli ultimi dati, con l'avviso giallo;
   - l'invito "configura Enable Banking" sparisce, perché la sua richiesta fallisce e la pagina non inventa niente.

   **La lezione**: una prova che passa va controllata anche quando passa.

---

## Parte I: esercizi guidati

1. **Più o meno fresco.** Porta il `timeout` a `1s` e, nel browser, Strumenti per sviluppatori → Rete → "3G lento".
   Che cosa vedi aprendo il Riepilogo? E con `performance` al posto di `freshness`?
2. **La memoria del service worker.** Strumenti per sviluppatori → Applicazione → Archiviazione cache: trova
   `ngsw:...:data:dati`. Quali indirizzi ci sono? Perché non c'è `/api/setup`?
3. **Un importo che scappa.** Cerca nei template un numero che è un importo ma non passa da `money`. Come lo
   troveresti con un test (pensa a come la prova nel browser cerca "cifre seguite da €")?
4. **L'occhio da tastiera.** Aggiungi una scorciatoia (per esempio il tasto "d" premuto fuori da un campo) che
   accende e spegne la modalità discreta. Dove ascolteresti il tasto (`App`, con `@HostListener` o `host`)? Come
   eviti che scatti mentre si scrive una nota?
