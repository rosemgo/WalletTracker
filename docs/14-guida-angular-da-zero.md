# 14. Angular da zero: capire il frontend di WalletTracker

Questa guida parte dalle **basi** e arriva a spiegare **ogni file** della dashboard. Non dà niente per
scontato: se di Angular ricordi poco o nulla, è scritta per te. Usa sempre il **codice vero** del progetto
(cartella `frontend/`), e lo paragona a quello che conosci già del backend Java/Spring.

**Come leggerla.** In ordine, con il codice aperto accanto (VS Code o IntelliJ). Ogni capitolo dice quali
file guardare. Alla fine ci sono degli **esercizi** con le soluzioni.

| Capitolo | Argomento |
|---|---|
| 1 | Le basi del web: HTML, CSS, JavaScript, il DOM |
| 2 | TypeScript per chi conosce Java |
| 3 | Gli strumenti: Node, npm, Angular CLI |
| 4 | Come parte l'applicazione |
| 5 | I componenti e i template |
| 6 | I signals: lo stato della pagina |
| 7 | I servizi e la dependency injection |
| 8 | Parlare con il backend: HttpClient e Observable |
| 9 | Il router: le pagine |
| 10 | Seguire un clic dall'inizio alla fine |
| 11 | I file uno per uno |
| 12 | I test |
| 13 | Guardare dentro l'applicazione mentre gira |
| 14 | Esercizi (con soluzioni) |
| 15 | Glossario |

---

## 1. Le basi del web: HTML, CSS, JavaScript, il DOM

Un browser capisce **tre linguaggi**, ognuno con un compito:

| Linguaggio | Compito | Esempio |
|---|---|---|
| **HTML** | la **struttura**: cosa c'è nella pagina | `<button>Aggiorna ora</button>` |
| **CSS** | l'**aspetto**: colori, spazi, caratteri | `.negative { color: red; }` |
| **JavaScript** | il **comportamento**: cosa succede quando clicchi | "al clic, chiedi i dati al server" |

### HTML: elementi, attributi, annidamento

```html
<td class="amount">-630,39 €</td>
```

- `<td>` ... `</td>` è un **elemento** (una cella di tabella), con un tag di apertura e uno di chiusura;
- `class="amount"` è un **attributo**: un'informazione in più sull'elemento;
- gli elementi stanno uno **dentro** l'altro: una `<table>` contiene righe `<tr>`, che contengono celle
  `<td>`.

Gli elementi che userai di più nel progetto:

| Elemento | Cos'è |
|---|---|
| `<h1>` | titolo |
| `<p>` | paragrafo |
| `<div>`, `<span>` | contenitori "neutri", per raggruppare o applicare uno stile |
| `<a href="...">` | link |
| `<button>` | pulsante |
| `<select>` con `<option>` | menu a tendina |
| `<input>` | campo di testo |
| `<table>`, `<thead>`, `<tbody>`, `<tr>`, `<th>`, `<td>` | tabella, intestazione, corpo, riga, cella di intestazione, cella |

### CSS: selettori e regole

```css
.negative {
  color: var(--negative);
}
```

- `.negative` è un **selettore**: "tutti gli elementi con `class="negative"`";
- tra le graffe ci sono le **regole**: qui il colore del testo;
- `var(--negative)` usa una **variabile CSS**, definita una volta sola in cima al file `styles.css`
  (`--negative: #c62828;`).

Gli stili del progetto stanno tutti in `frontend/src/styles.css`, e valgono per tutta l'applicazione.

### JavaScript e il DOM

Quando il browser legge l'HTML, costruisce in memoria un **albero di oggetti**, uno per ogni elemento: il
**DOM** (*Document Object Model*). JavaScript può leggere e modificare quell'albero, e il browser ridisegna
subito la pagina:

```js
document.querySelector('h1').textContent = 'I miei conti';   // cambia il titolo nella pagina
```

Scrivere a mano tutte le modifiche al DOM ("aggiungi una riga, cambia questo testo, togli quella classe")
diventa ingestibile molto presto. **Angular fa questo lavoro per te**: tu descrivi *come deve essere* la
pagina in base ai dati (il **template**), e Angular aggiorna il DOM quando i dati cambiano.

### Una "single page application"

Un sito tradizionale ha un file HTML per ogni pagina, e a ogni clic su un link il browser scarica una
pagina nuova. La nostra dashboard è invece una **SPA** (*single page application*):

1. il browser scarica **una sola pagina** (`index.html`) e il JavaScript di Angular;
2. cambiando "pagina" (Movimenti → Conti), Angular **sostituisce il contenuto** senza ricaricare;
3. i dati arrivano dal backend in **JSON**, con richieste HTTP fatte da JavaScript.

---

## 2. TypeScript per chi conosce Java

Angular si scrive in **TypeScript**: JavaScript con i **tipi**. Il browser non capisce TypeScript, quindi
Angular lo traduce (*compila*) in JavaScript. I tipi servono solo mentre scrivi: se sbagli, la
compilazione ti avvisa, proprio come `javac`.

### Variabili

```ts
const PAGE_SIZE = 50;              // costante: come "final" in Java
let params = new HttpParams();     // variabile che può cambiare
```

Il tipo spesso non si scrive, perché TypeScript lo **deduce**: `PAGE_SIZE` è un `number`. Quando serve,
lo si scrive **dopo** il nome:

```ts
let name: string = 'ING';
```

### I tipi principali

| TypeScript | Java | Note |
|---|---|---|
| `number` | `int`, `long`, `double` | un solo tipo numerico |
| `string` | `String` | |
| `boolean` | `boolean` | |
| `Account[]` | `List<Account>` | array |
| `string \| null` | `String` (che può essere null) | in TypeScript bisogna **dichiarare** che un valore può essere null |
| `Map<number, string>` | `Map<Integer, String>` | |
| `void` | `void` | |

### `interface`: la forma di un oggetto

```ts
export interface Account {
  id: number;
  bank: string;
  name: string | null;
  ...
}
```

Descrive com'è fatto un oggetto: è l'equivalente dei nostri **record DTO** Java (`AccountDto`). Il JSON
che arriva dal backend viene trattato come un `Account`. Le interfacce **spariscono** nel JavaScript
finale: servono solo a controllare il codice mentre lo scrivi.

### Tipi unione: l'equivalente degli enum

```ts
export type AccountRole = 'UNASSIGNED' | 'MAIN' | 'SPENDING' | 'CREDIT_CARD' | 'INVESTMENT' | 'EXCLUDED';
```

Una variabile di tipo `AccountRole` può contenere **solo** uno di quei testi: `'MAIN'` va bene, `'BOH'`
è un errore di compilazione. Sono gli stessi valori dell'enum Java `AccountRole`, e viaggiano nel JSON
come testo.

### Funzioni e arrow function

```ts
function nameOf(account: Account): string {     // funzione "classica"
  return account.name ?? account.bank;
}

const nameOf = (account: Account) => account.name ?? account.bank;   // arrow function
```

La **arrow function** (`=>`) è come una **lambda** Java (`account -> ...`). La userai di continuo:

```ts
list.map((a) => (a.id === updated.id ? updated : a))     // come stream().map(...) in Java
banks.filter((bank) => bank.name.toLowerCase().includes(text))
```

`map`, `filter` e simili sono metodi degli array, come `stream().map()` e `stream().filter()`, ma
restituiscono subito un nuovo array, senza `.collect()`.

### Classi

```ts
export class AccountsPage {
  private readonly api = inject(WalletApi);
  protected readonly loading = signal(true);

  protected nameOf(account: Account): string { ... }
}
```

- `private`, `protected`, `public`: come in Java. In Angular, `protected` significa "visibile al
  **template** del componente, ma non ad altre classi";
- `readonly`: come `final` per un campo;
- i campi si possono inizializzare direttamente nella dichiarazione;
- dentro i metodi, campi e altri metodi si usano **sempre** con `this.`: `this.loading.set(false)`.
  In Java `this` è facoltativo, qui no.

### Template literal: stringhe con valori dentro

```ts
`Aggiornati ${round.updated} conti`
```

Con gli **accenti gravi** (`` ` ``) invece degli apici, `${...}` inserisce un valore nel testo. È come
`"Aggiornati " + round.updated + " conti"` in Java.

### Due operatori utilissimi: `?.` e `??`

```ts
account.name ?? account.bank          // se name è null (o undefined), usa bank
response.error?.detail                // se error è null, il risultato è undefined (niente errore)
```

- `a ?? b`: "`a`, ma se è null usa `b`". Come `Objects.requireNonNullElse(a, b)` in Java.
- `a?.b`: "`a.b`, ma se `a` è null non andare in errore". In Java dovresti scrivere
  `a != null ? a.b : null`.

### `as`: "fidati, è di questo tipo"

```ts
const role = (event.target as HTMLSelectElement).value as AccountRole;
```

È come un **cast** Java: diciamo a TypeScript che `event.target` è un `<select>`, così possiamo leggere
`.value`.

### Moduli: `import` ed `export`

Ogni file `.ts` è un **modulo**. Quello che un file vuole rendere visibile lo marca con `export`; gli
altri lo prendono con `import`:

```ts
// models.ts
export interface Account { ... }

// wallet-api.ts
import { Account } from './models';         // './' = stessa cartella; niente estensione .ts
import { HttpClient } from '@angular/common/http';   // senza './' = una libreria in node_modules
```

È l'equivalente di `package` e `import` in Java, ma basato sui **file**, non sui package.

### Due tipi "di utilità"

```ts
Record<AccountRole, string>     // un oggetto con UNA chiave per ogni ruolo, e valori di tipo string
Partial<WalletApi>              // un oggetto con ALCUNI dei metodi di WalletApi (lo usiamo nei test)
```

---

## 3. Gli strumenti: Node, npm, Angular CLI

| Strumento | Cos'è | Equivalente nel backend |
|---|---|---|
| **Node.js** | esegue JavaScript **fuori dal browser** (sul tuo PC). Gli strumenti di Angular sono scritti in JavaScript, e girano su Node | la JVM |
| **npm** | scarica le librerie e lancia i comandi del progetto | Maven |
| `package.json` | elenco delle librerie (*dipendenze*) e dei comandi | `pom.xml` |
| `package-lock.json` | le versioni **esatte** installate (va su Git) | — |
| `node_modules/` | le librerie scaricate (**non** va su Git) | la cartella `~/.m2` |
| **Angular CLI** (`ng`) | il comando di Angular: crea, compila, avvia, testa | il plugin Spring Boot di Maven |
| `angular.json` | la configurazione del progetto Angular | la sezione `<build>` del `pom.xml` |

### I comandi (dalla cartella `frontend/`)

```powershell
npm install                  # legge package.json e scarica le librerie in node_modules
npm start                    # = ng serve: avvia il server di sviluppo
npm test -- --watch=false    # = ng test: esegue i test una volta
npm run build                # = ng build: compila la versione di produzione in dist/
```

I nomi `start`, `test`, `build` sono definiti nella sezione `"scripts"` di `package.json`.

### Cosa fa `npm start` (cioè `ng serve`)

1. **compila** TypeScript e template in JavaScript (con *esbuild*, molto veloce);
2. avvia un **server web di sviluppo** su `https://localhost:8443` (la porta e l'HTTPS sono in
   `angular.json`);
3. **osserva i file**: appena ne salvi uno, ricompila e il browser si aggiorna da solo;
4. fa da **proxy**: le richieste che iniziano con `/api` le inoltra al backend su `localhost:8080`
   (configurazione in `proxy.conf.json`).

```
browser ──► https://localhost:8443/conti          ──► ng serve risponde con l'app Angular
browser ──► https://localhost:8443/api/accounts   ──► ng serve inoltra a http://localhost:8080/api/accounts
```

Perché il proxy? Il browser, per sicurezza, non lascia che una pagina di `localhost:8443` legga le
risposte di `localhost:8080` (*same-origin policy*, spiegata in `docs/12`). Con il proxy, per il browser
tutto arriva dallo stesso indirizzo.

---

## 4. Come parte l'applicazione

Segui il percorso aprendo i file nell'ordine:

```
1. index.html          il browser scarica la pagina; nel body c'è un elemento sconosciuto: <app-root>
        │
2. main.ts             il JavaScript parte: bootstrapApplication(App, appConfig)
        │
3. app.config.ts       i "provider": router, HttpClient, lingua italiana
        │
4. app.ts              il componente App ha selector 'app-root': Angular lo "monta" dentro <app-root>
        │
5. app.html            disegna la barra in alto e, sotto, <router-outlet>
        │
6. app.routes.ts       il router guarda l'indirizzo (/conti) e sceglie il componente (AccountsPage)
        │
7. accounts-page.*     AccountsPage viene disegnato dentro <router-outlet>, e carica i dati
```

### 1. `src/index.html`

```html
<body>
  <app-root></app-root>
</body>
```

L'unica pagina HTML dell'applicazione. `<app-root>` non è un elemento HTML standard: è il **segnaposto**
dove Angular metterà il componente principale.

### 2. `src/main.ts`

```ts
bootstrapApplication(App, appConfig).catch((err) => console.error(err));
```

Il punto di partenza, come il `main()` di `WalletTrackerApplication` in Spring Boot: "avvia
l'applicazione, con `App` come componente principale e `appConfig` come configurazione".

### 3. `src/app/app.config.ts`

```ts
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withFetch()),
    { provide: LOCALE_ID, useValue: 'it-IT' },
  ],
};
```

L'elenco dei **provider**: le cose che Angular mette a disposizione dell'applicazione.
- `provideRouter(routes)`: attiva il router con le nostre pagine;
- `provideHttpClient(...)`: rende disponibile `HttpClient`, il client per chiamare il backend;
- `{ provide: LOCALE_ID, useValue: 'it-IT' }`: la lingua predefinita, per le date e gli importi in
  formato italiano (insieme a `registerLocaleData(localeIt)`, poco sopra);
- `provideBrowserGlobalErrorListeners()`: registra gli errori JavaScript non gestiti.

È l'equivalente delle classi di configurazione di Spring (`@Configuration`, i `@Bean`).

### 4–5. `app.ts` e `app.html`: il componente principale

```ts
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
})
export class App {}
```

```html
<header>
  <span class="brand">WalletTracker</span>
  <nav>
    <a routerLink="/movimenti" routerLinkActive="active">Movimenti</a>
    ...
  </nav>
</header>
<main>
  <router-outlet />
</main>
```

La barra in alto è **sempre** visibile. `<router-outlet />` è il punto dove compare la pagina scelta.
Cambiando pagina cambia solo il contenuto di `<router-outlet>`, mentre la barra resta.

---

## 5. I componenti e i template

Un **componente** è un pezzo di interfaccia: una pagina intera (`AccountsPage`) o una parte (`App`, la
barra). È fatto di:

| Parte | File | Cosa contiene |
|---|---|---|
| la **classe** | `accounts-page.ts` | i dati (stato) e i metodi (cosa fare ai clic) |
| il **template** | `accounts-page.html` | l'HTML, con dei "segnaposto" collegati alla classe |
| (lo stile) | qui nessuno: usiamo `styles.css`, globale | |

### Il decoratore `@Component`

```ts
@Component({
  selector: 'app-accounts-page',
  imports: [DatePipe, RouterLink],
  templateUrl: './accounts-page.html',
})
export class AccountsPage implements OnInit { ... }
```

`@Component` è un **decoratore**, l'equivalente delle annotazioni Java (`@Service`, `@RestController`):
dice ad Angular "questa classe è un componente". I parametri:
- **`selector`**: il nome del tag con cui il componente si può usare in un altro template
  (`<app-accounts-page>`). Le nostre pagine non vengono mai scritte a mano in un template: le mostra il
  router;
- **`imports`**: le cose che il template usa e che vengono da fuori. Qui la pipe `date` (`DatePipe`) e
  la direttiva `routerLink` (`RouterLink`). Se dimentichi un import, il template non compila;
- **`templateUrl`**: il file HTML del template.

`implements OnInit` funziona come un'interfaccia Java: la classe promette di avere il metodo
`ngOnInit()` (vedi il ciclo di vita, più sotto).

### Il binding: collegare template e classe

Il **binding** è il cuore di Angular. Ci sono quattro forme, e le trovi tutte nei nostri template.

#### 1. Interpolazione `{{ }}`: dalla classe al testo

```html
<td>{{ account.bank }}</td>
<p class="message">{{ message() }}</p>
```

Mostra un valore come testo. Dentro `{{ }}` c'è un'**espressione**, valutata nel contesto della classe:
`message()` chiama il signal `message` della classe.

#### 2. Property binding `[ ]`: dalla classe a una proprietà dell'elemento

```html
<button [disabled]="syncing()">
<option [value]="role" [selected]="role === account.role">
```

Le **parentesi quadre** dicono: "il valore di questa proprietà è il risultato dell'espressione". Il
pulsante è disabilitato quando `syncing()` è `true`. Senza parentesi (`disabled="syncing()"`), sarebbe
solo il testo `"syncing()"`.

Due varianti che trovi nel progetto:

```html
<td [class.negative]="t.amount < 0">           <!-- aggiunge la classe CSS "negative" solo se la condizione è vera -->
<select [attr.aria-label]="'Ruolo di ' + nameOf(account)">   <!-- imposta un attributo HTML -->
```

`aria-label` è un'etichetta per i lettori di schermo (accessibilità), che usiamo anche nei test per
trovare i menu.

#### 3. Event binding `( )`: dall'elemento alla classe

```html
<button (click)="syncNow()">
<select (change)="onRoleChange(account, $event)">
<input (input)="onSearch($event)" />
```

Le **parentesi tonde** dicono: "quando succede questo evento, esegui questa istruzione". `$event` è
l'oggetto dell'evento del browser, da cui il metodo legge per esempio il valore scelto:

```ts
onRoleChange(account: Account, event: Event): void {
  const role = (event.target as HTMLSelectElement).value as AccountRole;
  ...
}
```

`event.target` è l'elemento che ha generato l'evento (il `<select>`), e `.value` è l'opzione scelta.

#### 4. Le direttive come attributi

```html
<a routerLink="/collega">Collega una banca</a>
```

`routerLink` non è un attributo HTML: è una **direttiva** di Angular (da `imports: [RouterLink]`), che dà
un comportamento in più all'elemento. Qui, al clic, cambia pagina senza ricaricare il browser.

**Riassunto:**

| Sintassi | Direzione | Esempio |
|---|---|---|
| `{{ x }}` | classe → testo | `{{ account.bank }}` |
| `[prop]="x"` | classe → proprietà dell'elemento | `[disabled]="syncing()"` |
| `(evento)="f()"` | elemento → classe | `(click)="syncNow()"` |

### Il control flow: `@if`, `@for`

Il template può mostrare parti in base a una condizione, o ripeterle per ogni elemento di una lista.

#### `@if` / `@else`

```html
@if (loading()) {
  <p>Caricamento...</p>
} @else {
  <table>...</table>
}
```

Come un `if` Java: mostra il primo blocco se la condizione è vera, altrimenti il secondo.

Una forma utile, in `transactions-page.html`:

```html
@if (result(); as page) {
  ... page.items ... page.totalPages ...
} @else if (loading()) {
  <p>Caricamento...</p>
}
```

`as page` dà un **nome** al valore: dentro il blocco, `page` è il risultato di `result()`, sicuramente
non null. È comodo e si evita di chiamare `result()` dieci volte.

#### `@for` / `@empty`

```html
@for (account of accounts(); track account.id) {
  <tr>
    <td>{{ account.bank }}</td>
    ...
  </tr>
} @empty {
  <tr><td colspan="5">Nessun conto collegato.</td></tr>
}
```

- ripete il blocco per ogni elemento della lista, come `for (Account account : accounts)`;
- **`track account.id`** (obbligatorio) dice ad Angular come riconoscere ogni elemento. Quando la lista
  cambia, Angular aggiorna solo le righe cambiate invece di ridisegnare tutta la tabella;
- **`@empty`** viene mostrato se la lista è vuota.

`@for` si può annidare: in ogni riga dei conti c'è un `<select>` con un `@for` sui ruoli.

### Le pipe: trasformare un valore per mostrarlo

```html
{{ account.consentValidUntil | date: 'dd/MM/yyyy' }}       →  08/02/2027
{{ t.amount | currency: t.currency }}                      →  -630,39 €
```

Il simbolo `|` passa il valore a una **pipe**, che lo trasforma solo per la visualizzazione (il dato
originale non cambia). Dopo i `:` ci sono i parametri. Le pipe vanno importate nel componente
(`imports: [DatePipe, CurrencyPipe]`). Il formato italiano arriva dal `LOCALE_ID` di `app.config.ts`.

### Il ciclo di vita: `ngOnInit`

```ts
export class AccountsPage implements OnInit {
  ngOnInit(): void {
    this.loadAccounts();
  }
}
```

Angular chiama `ngOnInit()` **una volta**, appena ha creato il componente. È il posto giusto per caricare
i dati iniziali. Quando cambi pagina, il componente della pagina vecchia viene **distrutto**; tornandoci,
ne viene creato uno nuovo, e `ngOnInit` riparte: per questo i dati si ricaricano.

---

## 6. I signals: lo stato della pagina

### Il problema

La pagina deve sempre mostrare i dati **attuali**: se la lista dei conti cambia, la tabella va
aggiornata. Ma come fa Angular a sapere **quando** i dati sono cambiati?

### La soluzione: i signals

Un **signal** è un contenitore di un valore, che **avvisa** quando il valore cambia:

```ts
protected readonly accounts = signal<Account[]>([]);    // crea il signal, con valore iniziale [] (lista vuota)
protected readonly loading = signal(true);               // tipo dedotto: boolean
protected readonly error = signal<string | null>(null);
```

Tre operazioni:

```ts
this.accounts()                     // LEGGERE: si "chiama" come una funzione
this.loading.set(false)             // SCRIVERE un valore nuovo
this.accounts.update((list) => list.map(...))   // SCRIVERE partendo dal valore attuale
```

E nel template:

```html
@if (loading()) { ... }
@for (account of accounts(); track account.id) { ... }
```

**Come funziona.** Quando il template legge `accounts()`, Angular si annota: "questa parte di pagina
dipende da `accounts`". Quando qualcuno fa `accounts.set(...)`, Angular sa esattamente cosa ridisegnare.
Non devi mai dire "aggiorna la pagina": basta cambiare i signals.

### Una regola importante: creare valori nuovi, non modificare quelli vecchi

```ts
this.accounts.update((list) => list.map((a) => (a.id === updated.id ? updated : a)));
```

`map` crea un **nuovo** array, con il conto aggiornato al posto di quello vecchio. Se invece modificassi
l'array esistente (es. `list[0] = updated`), il signal conterrebbe **lo stesso** array, e potrebbe non
accorgersi del cambio. È lo stesso principio dei record Java immutabili: invece di cambiare un oggetto,
se ne crea uno nuovo.

### `computed`: un valore calcolato da altri signals

```ts
protected readonly accountNames = computed(
  () => new Map(this.accounts().map((a) => [a.id, a.name ?? a.bank])),
);
```

Un **`computed`** è un signal il cui valore è **calcolato** da altri signals. Qui: una mappa
id → nome del conto, costruita da `accounts()`.
- Si **ricalcola da solo** quando cambia `accounts()`, e solo allora;
- il risultato viene **memorizzato**: leggere `accountNames()` cento volte non ricostruisce la mappa
  cento volte.

Nel template: `{{ accountNames().get(t.accountId) }}` mostra il nome del conto di ogni movimento.

Un altro esempio, in `link-page.ts`: le banche filtrate mentre scrivi nella casella di ricerca.

```ts
protected readonly filteredBanks = computed(() => {
  const text = this.search().trim().toLowerCase();
  return this.banks().filter((bank) => bank.name.toLowerCase().includes(text));
});
```

Dipende da **due** signals: cambia `search` (scrivi una lettera) o `banks` (cambi paese), e la lista si
ricalcola.

### "Zoneless": perché è importante

Nelle versioni passate di Angular, quelle che probabilmente hai usato, una libreria chiamata **zone.js**
intercettava *ogni* evento del browser (clic, timer, risposte HTTP), e dopo ognuno controllava **tutta**
la pagina alla ricerca di cambiamenti. Funzionava "per magia", ma era pesante, e quando qualcosa non si
aggiornava era difficile capire perché.

Il nostro progetto è **zoneless**, cioè senza zone.js. Angular ridisegna una parte della pagina quando:
1. cambia un **signal** letto da quella parte di template;
2. succede un **evento** collegato nel template (`(click)`, `(change)`...).

Per questo **tutto lo stato** delle pagine sta nei signals. Se mettessi un dato in un campo normale
(`this.loading = false`) e lo cambiassi dentro la risposta di una richiesta HTTP, la pagina non si
aggiornerebbe.

---

## 7. I servizi e la dependency injection

### Il problema

Più pagine devono chiamare il backend: i conti, i movimenti, il collegamento delle banche. Non vogliamo
ripetere gli indirizzi delle API in ogni componente.

### La soluzione: un servizio

Un **servizio** è una classe con un compito preciso, usata da più componenti. Il nostro è `WalletApi`
(`src/app/api/wallet-api.ts`), l'unico punto che conosce gli indirizzi delle API:

```ts
@Injectable({ providedIn: 'root' })
export class WalletApi {
  private readonly http = inject(HttpClient);

  accounts(): Observable<Account[]> {
    return this.http.get<Account[]>('/api/accounts');
  }
  ...
}
```

### La dependency injection: come in Spring

| | Spring (backend) | Angular (frontend) |
|---|---|---|
| dichiarare un servizio | `@Service` | `@Injectable({ providedIn: 'root' })` |
| quante istanze | una (singleton) | una per tutta l'app (`'root'`) |
| chiederlo | parametro del costruttore | `inject(WalletApi)` oppure parametro del costruttore |
| chi lo crea | il contenitore di Spring | l'injector di Angular |

Un componente lo usa così:

```ts
export class AccountsPage {
  private readonly api = inject(WalletApi);   // "Angular, dammi il WalletApi"
}
```

Non scriviamo mai `new WalletApi()`: lo crea Angular, e ce lo dà già pronto, con il suo `HttpClient`.
Il vantaggio è lo stesso di Spring: nei **test** possiamo dare al componente un `WalletApi` **finto**
(capitolo 12).

`inject()` si può chiamare solo mentre Angular crea l'oggetto: nella dichiarazione di un campo (come qui)
o nel costruttore. Non dentro un metodo qualsiasi.

### Gli altri servizi del progetto

- **`BrowserLocation`** (`api/browser-location.ts`): un servizio di una riga, `go(url)`, che manda il
  browser a un altro sito (il login della banca). Esiste per poterlo sostituire nei test: un test non deve
  lasciare davvero la pagina.
- **Servizi di Angular** che iniettiamo: `HttpClient` (chiamate HTTP), `Router` (cambiare pagina),
  `ActivatedRoute` (i dati della pagina corrente), `DOCUMENT` (il documento del browser).

---

## 8. Parlare con il backend: HttpClient e Observable

### Una richiesta HTTP

```ts
accounts(): Observable<Account[]> {
  return this.http.get<Account[]>('/api/accounts');
}
```

- `this.http.get(...)` prepara una richiesta `GET /api/accounts`;
- `<Account[]>` dice a TypeScript che la risposta (JSON) sarà una lista di `Account`. Non c'è nessuna
  conversione: TypeScript si **fida**. Per questo `models.ts` deve rispecchiare esattamente i DTO Java;
- il risultato è un **`Observable`**.

Le altre richieste:

```ts
this.http.put<Account>(`/api/accounts/${accountId}/role`, { role })       // PUT con un corpo JSON
this.http.post<LinkResult>('/api/connections/complete', { code, state })  // POST con un corpo JSON
this.http.get<Bank[]>('/api/banks', { params: { country } })              // GET con ?country=IT
```

`{ role }` è un'abbreviazione di `{ role: role }`: un oggetto con un campo `role`, che diventa il JSON
`{"role": "INVESTMENT"}`.

### Observable e `subscribe`

Una richiesta HTTP richiede tempo: il backend risponde dopo qualche millisecondo, o qualche secondo. Il
programma **non si ferma ad aspettare**: la risposta arriverà dopo, in modo **asincrono**.

Un **Observable** rappresenta proprio questo: "un valore che arriverà". Per usarlo bisogna
**iscriversi** (`subscribe`), dicendo cosa fare quando arriva:

```ts
this.api.accounts().subscribe({
  next: (accounts) => {                // quando arriva la risposta
    this.accounts.set(accounts);
    this.loading.set(false);
  },
  error: () => {                        // se la richiesta fallisce (backend spento, errore 500...)
    this.error.set('Impossibile caricare i conti: il backend è avviato?');
    this.loading.set(false);
  },
});
// ← qui il codice continua SUBITO, senza aspettare la risposta
```

Due cose importanti:
1. **senza `subscribe()` la richiesta non parte**. L'Observable è solo una "ricetta";
2. il codice **dopo** `subscribe` viene eseguito **prima** della risposta. Tutto quello che dipende dai
   dati va messo dentro `next`.

In Java l'idea più vicina è `CompletableFuture` con `thenAccept(...)` ed `exceptionally(...)`.

### Leggere il motivo di un errore

Il backend descrive gli errori con i **Problem Details**: un JSON con il motivo nel campo `detail` (vedi
`docs/09`). La dashboard lo mostra così:

```ts
error: (response) => {
  this.error.set(response.error?.detail ?? 'Il collegamento non è riuscito.');
},
```

`response.error` è il corpo JSON della risposta d'errore; se manca, si usa il messaggio generico.

---

## 9. Il router: le pagine

### Le rotte

`src/app/app.routes.ts`:

```ts
export const routes: Routes = [
  { path: '', redirectTo: 'movimenti', pathMatch: 'full' },
  { path: 'movimenti', component: TransactionsPage, title: 'Movimenti · WalletTracker' },
  { path: 'conti', component: AccountsPage, title: 'Conti · WalletTracker' },
  { path: 'collega', component: LinkPage, title: 'Collega una banca · WalletTracker' },
  { path: 'callback', component: CallbackPage, title: 'Collegamento · WalletTracker' },
  { path: '**', redirectTo: 'movimenti' },
];
```

Ogni rotta collega un **indirizzo** a un **componente**:
- `https://localhost:8443/conti` → `AccountsPage`;
- `''` (l'indirizzo vuoto, cioè `https://localhost:8443/`) → rimanda a `movimenti`;
- `'**'` vuol dire "qualsiasi altro indirizzo": un indirizzo sbagliato torna ai movimenti;
- `title` diventa il titolo della scheda del browser.

È l'equivalente dei `@GetMapping` dei controller Spring, ma lato browser: non chiede nulla al server,
sceglie solo quale componente mostrare.

### I link

```html
<a routerLink="/conti" routerLinkActive="active">Conti</a>
```

- `routerLink`: al clic, il router cambia pagina **senza ricaricare** il browser (un normale `href`
  ricaricherebbe tutta l'applicazione);
- `routerLinkActive="active"`: quando la pagina corrente è `/conti`, aggiunge la classe CSS `active` al
  link (in `styles.css`: blu e grassetto). Così si vede in che pagina sei.

### I parametri dell'indirizzo: `ActivatedRoute`

La pagina `/callback` riceve dalla banca un indirizzo come
`https://localhost:8443/callback?code=abc&state=xyz`. Per leggere `code` e `state`:

```ts
private readonly route = inject(ActivatedRoute);

ngOnInit(): void {
  const params = this.route.snapshot.queryParamMap;
  const code = params.get('code');       // "abc", oppure null se manca
  const state = params.get('state');
}
```

- `ActivatedRoute` descrive la **pagina corrente**;
- `snapshot` è una "fotografia" del momento in cui la pagina si è aperta;
- `queryParamMap` contiene i parametri dopo il `?`.

### Cambiare pagina dal codice: `Router`

```ts
private readonly router = inject(Router);

this.router.navigate([], { relativeTo: this.route, queryParams: {}, replaceUrl: true });
```

Questa istruzione, nella pagina di callback, vuol dire: "resta nella stessa pagina (`[]`, relativa a
quella corrente), ma senza parametri, e **sostituisci** la voce nella cronologia del browser". Così i
parametri spariscono dalla barra degli indirizzi, e ricaricando la pagina non si riprova a usare un
codice già usato.

Per andare a un'altra pagina, sarebbe: `this.router.navigate(['/conti'])`.

---

## 10. Seguire un clic dall'inizio alla fine

Vediamo **tutti i collegamenti** con un esempio concreto: nella pagina **Conti** scegli il ruolo
"Carta di credito" per la carta ING.

```
 BROWSER                                    FRONTEND (Angular)                         BACKEND (Spring)
 ───────                                    ──────────────────                         ────────────────
 1. scegli "Carta di credito" nel menu
        │  evento "change"
        ▼
                                  2. accounts-page.html
                                     (change)="onRoleChange(account, $event)"
                                            │
                                  3. accounts-page.ts  onRoleChange()
                                     legge il valore: 'CREDIT_CARD'
                                     this.api.changeRole(3, 'CREDIT_CARD').subscribe(...)
                                            │
                                  4. wallet-api.ts  changeRole()
                                     this.http.put('/api/accounts/3/role', { role })
                                            │  PUT https://localhost:8443/api/accounts/3/role
                                            ▼
                                  5. ng serve (proxy.conf.json)
                                     inoltra a http://localhost:8080/api/accounts/3/role
                                                                                    │
                                                                       6. AccountController.changeRole()
                                                                          @PutMapping("/{id}/role")
                                                                                    │
                                                                       7. AccountService.changeRole()
                                                                          cambia il ruolo + classifyAll()
                                                                                    │
                                                                       8. risponde 200 con il JSON del conto
                                            ◄───────────────────────────────────────┘
                                  9. accounts-page.ts  next: (updated) => ...
                                     this.accounts.update(...)     ← signal cambiato
                                     this.message.set('...salvato...')
                                            │
                                 10. Angular vede i signals cambiati e ridisegna
                                     la riga del conto e il messaggio verde
 11. vedi il messaggio "ruolo salvato"  ◄───┘
```

Prova a rifare lo stesso percorso, file per file, con un'altra azione: per esempio la correzione di un
movimento nella pagina Movimenti (`onCorrect` → `correctType` → `PUT /api/transactions/{id}/manual-type`
→ `TransactionController` → `TransactionService.correctType`).

### La mappa dei collegamenti tra i file

```
main.ts ──► app.config.ts (provider: router, HttpClient, lingua)
   │              └──► app.routes.ts ──► TransactionsPage, AccountsPage, LinkPage, CallbackPage
   └──► app.ts / app.html (barra + router-outlet)

TransactionsPage ─┐
AccountsPage ─────┼──► WalletApi ──► HttpClient ──► /api/... ──(proxy)──► backend
LinkPage ─────────┤        │
CallbackPage ─────┘        └──► models.ts (i tipi dei dati)

TransactionsPage, AccountsPage ──► labels.ts (nomi italiani di tipi e ruoli)
LinkPage ──► BrowserLocation (manda il browser alla banca)
CallbackPage ──► ActivatedRoute (legge ?code&state), Router (pulisce l'indirizzo)
```

---

## 11. I file uno per uno

```
frontend/
├── package.json, package-lock.json   dipendenze e comandi (capitolo 3)
├── angular.json                       configurazione: build, server (porta 8443, HTTPS, proxy), test
├── proxy.conf.json                    /api → http://localhost:8080
├── tsconfig*.json                     opzioni del compilatore TypeScript
└── src/
    ├── index.html, main.ts, styles.css
    └── app/
        ├── app.ts, app.html, app.config.ts, app.routes.ts, app.spec.ts
        ├── api/            models.ts, labels.ts, wallet-api.ts, browser-location.ts
        ├── accounts/       accounts-page.ts, .html, .spec.ts
        ├── transactions/   transactions-page.ts, .html, .spec.ts
        └── link/           link-page.*, callback-page.*
```

I file `.spec.ts` sono i **test** (capitolo 12).

### `api/models.ts`: i tipi dei dati

Le **interfacce** dei dati che arrivano dal backend: `Account`, `Transaction`, `TransactionPage`,
`Bank`, `LinkResult`, `SyncRound`, e i tipi unione `AccountRole` e `TransactionType`. Ogni interfaccia
rispecchia un record Java:

| TypeScript (`models.ts`) | Java (backend) |
|---|---|
| `Account` | `AccountDto` |
| `Transaction` | `TransactionDto` |
| `TransactionPage` | `TransactionPageDto` |
| `LinkResult` | `BankLinkService.LinkResult` |
| `SyncRound` | `SyncService.Round` |

**Se cambi un DTO Java, va cambiata anche l'interfaccia qui**: TypeScript non può accorgersene da solo,
perché il JSON arriva solo quando l'applicazione gira.

### `api/labels.ts`: i nomi in italiano

```ts
export const ROLE_LABELS: Record<AccountRole, string> = {
  UNASSIGNED: 'Da assegnare',
  MAIN: 'Conto principale',
  ...
};
export const ROLES = Object.keys(ROLE_LABELS) as AccountRole[];
```

Il backend parla con i codici (`'MAIN'`), l'utente legge "Conto principale". `Record<AccountRole, string>`
obbliga ad avere una traduzione per **ogni** ruolo: se ne aggiungi uno a `AccountRole` e dimentichi di
tradurlo, la compilazione fallisce. `Object.keys(...)` prende l'elenco delle chiavi, cioè tutti i ruoli,
per i menu a tendina.

Nel template: `{{ roleLabels[role] }}` legge la traduzione di un ruolo (come `map.get(role)` in Java).

### `api/wallet-api.ts`: il servizio delle API

Visto nei capitoli 7 e 8. Un dettaglio, in `transactions()`:

```ts
let params = new HttpParams().set('page', filter.page).set('size', filter.size);
if (filter.accountId !== null) {
  params = params.set('accountId', filter.accountId);
}
```

`HttpParams` costruisce i parametri dopo il `?`. È **immutabile**: `set()` non modifica l'oggetto, ma ne
restituisce uno **nuovo**. Per questo si scrive `params = params.set(...)`. Un filtro `null` non viene
proprio aggiunto, ed è quello che il backend si aspetta.

### `accounts/`: la pagina Conti

**Stato** (signals): `accounts`, `loading`, `error`, `message`, `syncing`.

**Metodi:**
- `ngOnInit()` → `loadAccounts()`: carica i conti;
- `onRoleChange(account, event)`: cambio di ruolo (il percorso del capitolo 10);
- `syncNow()`: "Aggiorna ora". Mette `syncing` a `true`, così il pulsante si disabilita e mostra
  "Aggiornamento in corso...". Chiama `POST /api/sync`, mostra il riepilogo e ricarica i conti;
- `nameOf(account)`: il nome da mostrare (`name`, oppure la banca se il nome manca).

**Template:** la toolbar con il pulsante e il link, i messaggi (`@if (message())`), la tabella con
`@for`, un `<select>` per riga, le date con la pipe `date`.

Un dettaglio del menu a tendina:

```html
<option [value]="role" [selected]="role === account.role">{{ roleLabels[role] }}</option>
```

`[selected]` marca come scelta l'opzione uguale al ruolo attuale del conto: è così che il menu mostra il
ruolo giusto quando la pagina si apre.

### `transactions/`: la pagina Movimenti

**Stato:** `accounts`, `result` (la pagina di movimenti ricevuta), `loading`, `error`; i filtri
`accountId` e `type`, la pagina corrente `page`; il `computed` `accountNames`.

**Il flusso dei filtri:**

```ts
onTypeFilter(event: Event): void {
  const value = (event.target as HTMLSelectElement).value;
  this.type.set(value === '' ? null : (value as TransactionType));   // '' = "Tutti i tipi" = nessun filtro
  this.page.set(0);                                                   // filtro nuovo → prima pagina
  this.load();
}
```

`load()` legge i signals dei filtri e chiama `GET /api/transactions` con quei valori.

**La correzione:** `onCorrect` (scelta dal menu) e `onResetCorrection` (link "annulla") chiamano entrambi
`correct(...)`, che dopo il salvataggio ricarica la pagina, perché una correzione può cambiare anche
altri movimenti.

**`textOf(t)`**: unisce controparte e causale, senza ripetere lo stesso testo due volte (Revolut, ad
esempio, li manda uguali).

**Nel template:** `[class.negative]="t.amount < 0"` colora gli importi; `@if (t.manualType)` mostra
"corretto a mano · annulla"; i pulsanti "Precedente/Successiva" sono disabilitati sulla prima e
sull'ultima pagina.

### `link/link-page.*`: "Collega una banca"

**Stato:** `country`, `banks`, `search`, `loading`, `starting` (la banca che si sta collegando),
`error`; il `computed` `filteredBanks`.

- Cambiando paese → `loadBanks()` → `GET /api/banks?country=..`;
- scrivendo nella ricerca → `search.set(...)` → `filteredBanks` si ricalcola da solo;
- "Collega" → `POST /api/connections` → il backend risponde con l'indirizzo del login della banca →
  `this.location.go(url)` manda lì il browser. **L'applicazione Angular si chiude**: il browser è sul sito
  della banca.

### `link/callback-page.*`: il ritorno dalla banca

Dopo il login, la banca rimanda il browser a `https://localhost:8443/callback?code=...&state=...`. Il
browser carica di nuovo **tutta** l'applicazione (`index.html`, `main.ts`...), e il router mostra
`CallbackPage`, che in `ngOnInit`:
1. legge `code`, `state` ed eventuale `error` dall'indirizzo;
2. li toglie dalla barra degli indirizzi;
3. se c'è un errore della banca lo mostra; altrimenti chiama `POST /api/connections/complete`;
4. mostra il risultato ("ING collegata: 3 conti, 154 movimenti nuovi importati").

### `styles.css`

Tutti gli stili, globali. Le classi usate nei template: `.message` (verde), `.error` (rosso),
`.secondary` (testo piccolo grigio), `.negative`/`.positive` (importi), `.amount` (allineato a destra),
`.filters`, `.toolbar`, `.pager`, `.hint`, `button.link` (un pulsante che sembra un link).

---

## 12. I test

I test del frontend usano **Vitest**, e girano **senza browser** in un DOM simulato (*jsdom*). Ogni file
`xxx.spec.ts` sta accanto al file che testa.

### La struttura: `describe`, `it`, `expect`

```ts
describe('AccountsPage', () => {              // un gruppo di test (come una classe di test JUnit)
  beforeEach(() => { ... });                  // eseguito prima di ogni test (@BeforeEach)

  it('shows the accounts...', async () => {   // un test (@Test)
    ...
    expect(page.textContent).toContain('Carta di credito');   // come assertThat(...).contains(...)
  });
});
```

### `TestBed`: un mini-Angular per i test

```ts
TestBed.configureTestingModule({
  imports: [AccountsPage],
  providers: [{ provide: WalletApi, useValue: fakeApi }, provideRouter([])],
});
const fixture = TestBed.createComponent(AccountsPage);
await fixture.whenStable();
const page = fixture.nativeElement as HTMLElement;
```

- `configureTestingModule` prepara un'applicazione minima, con i provider che scegliamo noi;
- **`{ provide: WalletApi, useValue: fakeApi }`**: quando il componente chiede `WalletApi`, riceve il
  nostro **finto** (`fakeApi`), che risponde subito con dati preparati. È l'idea di `@MockitoBean` nei
  test Spring: niente backend vero;
- `createComponent` crea il componente; `whenStable()` aspetta che Angular abbia finito di aggiornare la
  pagina;
- `fixture.nativeElement` è l'HTML generato: lo interroghiamo come farebbe un utente.

Il finto servizio:

```ts
const fakeApi: Partial<WalletApi> = {
  accounts: () => of([CARD]),            // of(...) crea un Observable che risponde subito con questo valore
  changeRole: (id, role) => {
    changedRoles.push([id, role]);       // ci segniamo la chiamata, per controllarla dopo
    return of({ ...CARD, role });        // { ...CARD, role } = una copia di CARD con role cambiato
  },
};
```

### Simulare l'utente

```ts
select.value = 'CREDIT_CARD';
select.dispatchEvent(new Event('change'));   // "l'utente ha scelto un'opzione"
await fixture.whenStable();
expect(changedRoles).toEqual([[3, 'CREDIT_CARD']]);
```

### Il servizio HTTP con un finto server

In `wallet-api.spec.ts`, `provideHttpClientTesting()` sostituisce la rete con un server finto
(`HttpTestingController`): si controlla quale richiesta è partita e si decide la risposta. È come
`MockRestServiceServer` nei test Java del client di Enable Banking.

```ts
api.correctType(42, null).subscribe();
const request = http.expectOne('/api/transactions/42/manual-type');
expect(request.request.method).toBe('PUT');
expect(request.request.body).toEqual({ type: null });
```

---

## 13. Guardare dentro l'applicazione mentre gira

Gli **strumenti per sviluppatori** del browser (**F12**) sono il miglior modo per capire cosa succede.

| Scheda | Cosa ci vedi | Prova |
|---|---|---|
| **Network** (Rete) | ogni richiesta HTTP: indirizzo, metodo, stato, JSON inviato e ricevuto | apri Conti e cerca `accounts`: clicca e guarda la *Risposta*. Cambia un ruolo e guarda la richiesta `PUT` con il suo *Payload* |
| **Console** | errori JavaScript e messaggi di `console.log(...)` | aggiungi `console.log('conti', accounts);` dentro `next` in `loadAccounts()`, salva e ricarica |
| **Elements** (Elementi) | il DOM attuale, aggiornato in tempo reale | cerca `<app-root>`: dentro c'è tutto ciò che Angular ha disegnato |

Esiste anche l'estensione **Angular DevTools** (Chrome/Edge/Firefox), che mostra l'albero dei componenti
e il valore dei loro signals.

---

## 14. Esercizi (con soluzioni)

Fai gli esercizi con `npm start` acceso: ogni modifica si vede subito nel browser. Alla fine, controlla
che i test passino (`npm test -- --watch=false`) e, se vuoi tenere le modifiche, fai commit.

**Esercizio 1 (facile): meno movimenti per pagina.** Fai in modo che la pagina Movimenti mostri 20
movimenti per pagina invece di 50.

**Esercizio 2 (facile): il numero dei conti nel titolo.** Nella pagina Conti, il titolo deve diventare
"Conti (5)", con il numero dei conti.

**Esercizio 3 (medio): i conti con errori.** Sempre nella pagina Conti, sotto il titolo mostra, solo se ce
ne sono, il testo "2 conti hanno avuto un errore all'ultimo aggiornamento". Usa un `computed`.

**Esercizio 4 (medio): il totale della pagina.** Sotto la tabella dei movimenti, accanto a "Pagina 1 di
3", mostra la somma degli importi dei movimenti **della pagina visualizzata**, in euro, rossa se negativa.

**Esercizio 5 (impegnativo): un test.** Scrivi un test per l'esercizio 4 in `transactions-page.spec.ts`.

### Soluzioni

<details>
<summary>Esercizio 1</summary>

In `transactions-page.ts` cambia la costante:

```ts
const PAGE_SIZE = 20;
```

È l'unico punto dove compare: il valore viene mandato al backend come `size` in `load()`.

Ora lancia i test: **uno fallisce**, *a filter restarts from the first page*, perché si aspetta
`size: 50`. Non è un errore del tuo codice: il test descriveva il comportamento di prima, e va aggiornato
(`size: 20`) insieme al codice. I test fanno anche questo: ti segnalano **ogni** cambiamento di
comportamento, anche quelli voluti, così sei tu a decidere se è giusto.
</details>

<details>
<summary>Esercizio 2</summary>

In `accounts-page.html`:

```html
<h1>Conti ({{ accounts().length }})</h1>
```

`accounts()` legge il signal, `.length` è la lunghezza dell'array. Quando i conti arrivano dal backend,
il titolo si aggiorna da solo, perché legge un signal.
</details>

<details>
<summary>Esercizio 3</summary>

In `accounts-page.ts` aggiungi `computed` all'import da `@angular/core`, poi:

```ts
protected readonly accountsWithErrors = computed(
  () => this.accounts().filter((a) => a.lastSyncError !== null).length,
);
```

In `accounts-page.html`, sotto il titolo:

```html
@if (accountsWithErrors() > 0) {
  <p class="error">
    {{ accountsWithErrors() }} conti hanno avuto un errore all'ultimo aggiornamento
  </p>
}
```

Dopo "Aggiorna ora" i conti vengono ricaricati: il numero si ricalcola da solo.
</details>

<details>
<summary>Esercizio 4</summary>

In `transactions-page.ts`:

```ts
/** La somma degli importi della pagina visualizzata. */
protected readonly pageTotal = computed(() =>
  (this.result()?.items ?? []).reduce((sum, t) => sum + t.amount, 0),
);
```

- `this.result()?.items ?? []`: i movimenti della pagina, oppure una lista vuota se non sono ancora
  arrivati;
- `reduce` scorre la lista accumulando un valore: parte da `0` e a ogni movimento aggiunge `t.amount`.
  In Java sarebbe `stream().mapToDouble(...).sum()`.

In `transactions-page.html`, dentro `<div class="pager">`:

```html
<span [class.negative]="pageTotal() < 0">
  Totale pagina: {{ pageTotal() | currency: 'EUR' }}
</span>
```

Nota: la somma ha senso solo se i movimenti della pagina sono nella stessa valuta.
</details>

<details>
<summary>Esercizio 5</summary>

In `transactions-page.spec.ts`, dentro `describe('TransactionsPage', ...)`:

```ts
it('shows the total of the page', async () => {
  const fixture = TestBed.createComponent(TransactionsPage);
  await fixture.whenStable();

  // Il finto WalletApi restituisce un solo movimento: SETTLEMENT, da -630,39 €.
  expect((fixture.nativeElement as HTMLElement).textContent).toMatch(/Totale pagina: -630,39\s€/);
});
```

`\s` nell'espressione regolare accetta qualsiasi spazio: tra numero e simbolo dell'euro Angular mette uno
spazio "non separabile", diverso dallo spazio normale.
</details>

---

## 15. Glossario

| Termine | Significato |
|---|---|
| **Binding** | il collegamento tra template e classe: `{{ }}`, `[ ]`, `( )` |
| **Componente** | classe + template: un pezzo di interfaccia |
| **`computed`** | un signal calcolato da altri signals, ricalcolato in automatico |
| **Decoratore** | `@Component`, `@Injectable`: l'equivalente delle annotazioni Java |
| **Dependency injection** | Angular crea i servizi e li dà a chi li chiede (`inject()`) |
| **Direttiva** | un attributo che dà un comportamento in più a un elemento (`routerLink`) |
| **DOM** | l'albero di oggetti che rappresenta la pagina nel browser |
| **Observable** | un valore che arriverà in futuro; serve `subscribe()` |
| **Pipe** | trasforma un valore per mostrarlo (`date`, `currency`) |
| **Provider** | qualcosa che Angular mette a disposizione per l'iniezione (`app.config.ts`) |
| **Proxy** | il server di sviluppo inoltra `/api` al backend |
| **Router** | sceglie il componente in base all'indirizzo |
| **Servizio** | classe con un compito, condivisa tra componenti (`WalletApi`) |
| **Signal** | contenitore di un valore che avvisa quando cambia |
| **SPA** | applicazione a pagina singola: il contenuto cambia senza ricaricare |
| **Standalone** | un componente che dichiara da solo le sue dipendenze (`imports`), senza `NgModule` |
| **Template** | l'HTML del componente, con binding e control flow |
| **TypeScript** | JavaScript con i tipi |
| **Zoneless** | senza zone.js: la pagina si aggiorna grazie ai signals |

### Per approfondire

- Il tutorial ufficiale, interattivo e in inglese: <https://angular.dev/tutorials/learn-angular>.
- La guida ai signals: <https://angular.dev/guide/signals>.
- TypeScript per programmatori Java/C#: <https://www.typescriptlang.org/docs/handbook/typescript-in-5-minutes-oop.html>.
