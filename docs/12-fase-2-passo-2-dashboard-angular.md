# 12. Fase 2, passo 2: la dashboard Angular

**Obiettivo:** la prima versione della dashboard, nel browser. Due pagine:

- **Movimenti**: l'elenco a pagine, con i filtri per conto e per tipo, e la **correzione del tipo** da un
  menu a tendina;
- **Conti**: i conti collegati, con il **ruolo** modificabile, la scadenza del consenso e lo stato
  dell'ultima sincronizzazione.

La dashboard non contiene logica sui movimenti: chiama le API REST del passo 1 e mostra il risultato.

> Prerequisiti: passo 1 completato e `git pull` fatto.

---

## Parte A: i concetti

### 1. Una "single page application"

```
 browser (http://localhost:4200)                      backend (http://localhost:8080)
 ┌───────────────────────────────┐                    ┌──────────────────────────┐
 │ Angular (JavaScript)          │  GET /api/...      │ Spring Boot              │
 │  - disegna le pagine          │ ─────────────────► │  - API REST              │
 │  - cambia pagina senza        │ ◄───── JSON ────── │  - database              │
 │    ricaricare il browser      │                    │                          │
 └───────────────────────────────┘                    └──────────────────────────┘
```

Il browser scarica **una sola volta** l'applicazione Angular (HTML, JavaScript, CSS). Da lì in poi:

- cambiando pagina ("Movimenti" → "Conti") non chiede nuove pagine al server: è Angular (il **router**)
  a sostituire il contenuto;
- i dati li chiede al backend con richieste HTTP (`GET /api/transactions`) e riceve **JSON**.

Si chiama *single page application* (SPA): una sola pagina HTML, il cui contenuto cambia con JavaScript.

### 2. Due server durante lo sviluppo, e il proxy

Mentre sviluppi giri **due** programmi:

| Programma | Porta | Cosa fa |
|---|---|---|
| backend (`.\mvnw.cmd spring-boot:run`) | 8080 | API e database |
| dashboard (`npm start`, cioè `ng serve`) | 4200 | serve l'applicazione Angular e la **ricompila a ogni modifica** |

C'è però un problema. Il browser applica la **same-origin policy**: una pagina caricata da
`localhost:4200` non può leggere le risposte di `localhost:8080`, perché la porta è diversa e quindi è
un'altra "origine". È una protezione importante: impedisce a un sito qualsiasi di leggere, con il tuo
browser, i dati di un altro sito.

Le soluzioni sono due:
1. **CORS**: il backend dichiara "accetto richieste da localhost:4200". Funziona, ma è configurazione in
   più nel backend, e se fatta male apre un buco di sicurezza.
2. **Proxy** (la nostra scelta): il browser parla **solo** con `localhost:4200`, e il server di sviluppo di
   Angular inoltra al backend le richieste che iniziano con `/api`:

```
browser ── GET localhost:4200/api/accounts ──► ng serve ── GET localhost:8080/api/accounts ──► backend
```

Il proxy si configura in `frontend/proxy.conf.json`:

```json
{
  "/api": {
    "target": "http://localhost:8080",
    "secure": false
  }
}
```

e lo si attiva in `angular.json`, nelle opzioni di `serve` (`"proxyConfig": "proxy.conf.json"`). Per
questo nel codice gli indirizzi sono semplicemente `/api/accounts`, senza host e porta. Nella Fase 4
dashboard e API saranno servite dalla stessa origine anche in produzione, e il problema sparirà del tutto.

### 3. Angular oggi: cosa è rimasto e cosa è cambiato

Il progetto è stato creato con **Angular 22**. Molto è come lo ricordi:
- componenti con `@Component`, template HTML e CSS;
- servizi con `@Injectable`, iniettati nei componenti;
- `HttpClient` e gli `Observable` di RxJS, con `subscribe()`;
- il router, con `routerLink` e `<router-outlet>`;
- le pipe (`date`, `currency`) e il binding: `{{ }}`, `[proprietà]`, `(evento)`;
- TypeScript.

Le novità:

| Prima | Oggi | Dove lo vedi |
|---|---|---|
| `NgModule` obbligatori (`AppModule`, `declarations`...) | **componenti standalone**: ogni componente elenca in `imports` quello che usa | `accounts-page.ts`: `imports: [DatePipe]` |
| `AppModule` con i provider | **`app.config.ts`**: un elenco di funzioni `provide...()` | `provideRouter(routes)`, `provideHttpClient()` |
| `*ngIf`, `*ngFor` | **control flow**: `@if`, `@else`, `@for (... ; track ...)`, `@empty` | tutti i template |
| proprietà della classe, cambiamenti rilevati da zone.js | **signals**: `signal()`, `computed()`; il progetto è **zoneless** (senza zone.js) | `accounts = signal<Account[]>([])` |
| iniezione solo nel costruttore | anche **`inject()`** | `private readonly api = inject(WalletApi)` |
| file `accounts.component.ts`, classe `AccountsComponent` | file `accounts-page.ts`, classe `AccountsPage` (la nuova guida di stile toglie il suffisso) | tutto il progetto |
| Karma + Jasmine, test in un browser vero | **Vitest** con **jsdom** (un DOM simulato): niente browser, molto più veloce | `npm test` |
| build con Webpack | build con **esbuild** e server di sviluppo **Vite**: secondi invece di minuti | `npm start`, `npm run build` |

#### I signals, in breve

Un **signal** è un "contenitore di un valore" che avvisa Angular quando il valore cambia:

```ts
protected readonly accounts = signal<Account[]>([]);   // crea, con valore iniziale []
this.accounts.set(nuovaLista);                          // cambia il valore
this.accounts.update((list) => list.map(...));          // cambia partendo dal valore attuale
```

```html
@for (account of accounts(); track account.id) { ... }  <!-- si legge "chiamandolo": accounts() -->
```

Quando il valore cambia, Angular sa **esattamente** quali parti della pagina lo leggono e aggiorna solo
quelle. Un `computed` è un signal **calcolato** da altri, e si ricalcola da solo:

```ts
protected readonly accountNames = computed(() => new Map(this.accounts().map((a) => [a.id, a.name ?? a.bank])));
```

**Perché "zoneless"?** Una volta Angular usava una libreria, **zone.js**, che intercettava *ogni* evento
del browser (clic, timer, risposte HTTP) e dopo ognuno ricontrollava tutta la pagina alla ricerca di
cambiamenti. Funzionava "per magia", ma era pesante e difficile da capire. Con i signals Angular sa da
solo cosa è cambiato, quindi zone.js non serve più.

---

## Parte B: il codice

### 1. La struttura

```
frontend/
├── package.json          le dipendenze (come il pom.xml) e i comandi: npm start, npm test...
├── package-lock.json     le versioni ESATTE installate (va su Git: build ripetibili)
├── angular.json          la configurazione del progetto: build, server di sviluppo, test
├── proxy.conf.json       il proxy verso il backend
├── node_modules/         le dipendenze scaricate (NON va su Git: c'è nel .gitignore)
└── src/
    ├── index.html        l'unica pagina HTML: contiene <app-root>
    ├── main.ts           il punto di partenza: avvia l'applicazione (come il main() di Spring Boot)
    ├── styles.css        gli stili globali
    └── app/
        ├── app.ts / app.html      il componente principale: barra in alto + <router-outlet>
        ├── app.config.ts          i provider: router, HttpClient, lingua italiana
        ├── app.routes.ts          quale componente per quale indirizzo
        ├── api/
        │   ├── models.ts          i tipi dei dati delle API (lo specchio dei DTO Java)
        │   ├── labels.ts          i nomi italiani di ruoli e tipi
        │   └── wallet-api.ts      il servizio che chiama il backend
        ├── accounts/              la pagina dei conti (.ts + .html + .spec.ts)
        └── transactions/          la pagina dei movimenti
```

Il paragone con il backend aiuta: `package.json` ↔ `pom.xml`, `npm` ↔ Maven, `node_modules/` ↔ la
cartella `~/.m2`, `wallet-api.ts` ↔ un `@Service`, `models.ts` ↔ i record DTO.

### 2. `models.ts`: i tipi dei dati

```ts
export type AccountRole = 'UNASSIGNED' | 'MAIN' | 'SPENDING' | 'CREDIT_CARD' | 'INVESTMENT' | 'EXCLUDED';

export interface Account {
  id: number;
  bank: string;
  name: string | null;
  ...
}
```

Le `interface` descrivono la forma del JSON che arriva dal backend: stessi nomi dei campi dei record Java.
TypeScript le usa solo mentre scrivi (se sbagli il nome di un campo, la compilazione fallisce); nel
JavaScript finale spariscono. `'MAIN' | 'SPENDING'` è un **tipo unione**: il valore può essere solo
uno di quei testi, ed è l'equivalente di un enum Java.

In `labels.ts`, `Record<AccountRole, string>` obbliga ad avere una traduzione per **ogni** ruolo: se
aggiungi un ruolo in `models.ts` e dimentichi di tradurlo, la compilazione fallisce.

### 3. `wallet-api.ts`: il servizio

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

- `providedIn: 'root'`: una sola istanza per tutta l'applicazione, iniettabile ovunque, come un
  `@Service` di Spring.
- Ogni metodo restituisce un **`Observable`**: la richiesta parte solo al `subscribe()` e la risposta
  arriva dopo, in modo asincrono.
- In `transactions(filter)`, `HttpParams` costruisce i parametri dopo il `?`. I filtri `null` **non**
  vengono mandati, proprio come il backend si aspetta.

### 4. `AccountsPage`: la pagina dei conti

```ts
export class AccountsPage implements OnInit {
  private readonly api = inject(WalletApi);
  protected readonly accounts = signal<Account[]>([]);
  protected readonly loading = signal(true);

  ngOnInit(): void {
    this.api.accounts().subscribe({
      next: (accounts) => { this.accounts.set(accounts); this.loading.set(false); },
      error: () => { this.error.set('Impossibile caricare i conti: il backend è avviato?'); ... },
    });
  }
```

`ngOnInit` è lo stesso di una volta: il posto dove caricare i dati. La risposta finisce in un signal, e
il template si aggiorna da solo.

Il template usa il nuovo control flow:

```html
@if (loading()) {
  <p>Caricamento...</p>
} @else {
  <table>
    @for (account of accounts(); track account.id) {
      <tr>...</tr>
    } @empty {
      <tr><td colspan="5">Nessun conto collegato.</td></tr>
    }
  </table>
}
```

- `track account.id` dice ad Angular come riconoscere ogni riga: se la lista cambia, aggiorna solo le
  righe cambiate invece di ridisegnarle tutte. Era il `trackBy` di `*ngFor`, che ora è **obbligatorio**.
- `@empty` è il caso "lista vuota", prima da scrivere a parte con un `*ngIf`.

Il menu del ruolo:

```html
<select (change)="onRoleChange(account, $event)">
  @for (role of roles; track role) {
    <option [value]="role" [selected]="role === account.role">{{ roleLabels[role] }}</option>
  }
</select>
```

`(change)` chiama il metodo quando l'utente sceglie un'opzione; `$event` è l'evento del browser, da cui
il metodo legge il valore scelto. Non usiamo `ngModel`/`FormsModule`: per un singolo menu basta l'evento.

Dopo il salvataggio, `this.accounts.update(list => list.map(...))` sostituisce il conto aggiornato. Si
crea un **nuovo** array invece di modificare quello vecchio: è così che il signal "si accorge" del cambio.

### 5. `TransactionsPage`: la pagina dei movimenti

- I **filtri** (conto, tipo) e la **pagina** sono signals. Ogni cambio di filtro riporta alla pagina 0 e
  chiama `load()`, che fa `GET /api/transactions` con i filtri correnti.
- **`accountNames`** è un `computed`: la mappa id → nome dei conti, per mostrare il nome del conto in
  ogni riga.
- **La correzione del tipo**: il menu di ogni riga mostra il tipo attuale. Sceglierne un altro chiama
  `PUT /api/transactions/{id}/manual-type`. Se il movimento è corretto a mano compare "corretto a mano ·
  annulla", e "annulla" manda `{"type": null}`. Dopo ogni correzione si ricarica la pagina, perché una
  correzione può cambiare anche altri movimenti (l'altra metà di un trasferimento).
- **Formati italiani**: `{{ t.amount | currency: t.currency }}` mostra `-630,39 €` e
  `{{ t.bookingDate | date: 'dd/MM/yyyy' }}` mostra `10/10/2026`. Funziona perché in `app.config.ts`
  registriamo la lingua italiana (`registerLocaleData(localeIt)`) e la impostiamo come predefinita
  (`{ provide: LOCALE_ID, useValue: 'it-IT' }`).

### 6. Router e configurazione

```ts
export const routes: Routes = [
  { path: '', redirectTo: 'movimenti', pathMatch: 'full' },
  { path: 'movimenti', component: TransactionsPage, title: 'Movimenti · WalletTracker' },
  { path: 'conti', component: AccountsPage, title: 'Conti · WalletTracker' },
  { path: '**', redirectTo: 'movimenti' },
];
```

`title` diventa il titolo della scheda del browser. In `app.html`, `routerLink` e `routerLinkActive`
creano i link della barra e colorano quello della pagina corrente.

### 7. I test (Vitest)

Due tecniche, entrambe già viste nel backend:

**Il servizio con un finto server HTTP** (`wallet-api.spec.ts`), come `MockRestServiceServer`:

```ts
TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
api.transactions({ accountId: 3, type: null, page: 1, size: 50 }).subscribe();
const request = http.expectOne((r) => r.url === '/api/transactions');
expect(request.request.params.has('type')).toBe(false);  // il filtro spento non viene mandato
```

**I componenti con un servizio finto** (`accounts-page.spec.ts`, `transactions-page.spec.ts`), come
`@MockitoBean`:

```ts
providers: [{ provide: WalletApi, useValue: fakeApi }]   // al posto del servizio vero
...
select.value = 'CREDIT_CARD';
select.dispatchEvent(new Event('change'));                // simuliamo l'utente
await fixture.whenStable();                               // aspettiamo che Angular aggiorni la pagina
expect(changedRoles).toEqual([[3, 'CREDIT_CARD']]);
```

| Test | Cosa verifica |
|---|---|
| `WalletApi`: *sends only the filters that are set* | i filtri null non finiscono nell'indirizzo |
| `WalletApi`: *removes a manual correction by sending type null* | `PUT` con `{"type": null}` |
| `AccountsPage`: *shows the accounts...* | conti, ruolo selezionato, errore di sincronizzazione |
| `AccountsPage`: *saves the role chosen from the menu* | il menu chiama l'API e mostra la conferma |
| `TransactionsPage`: *shows dates and amounts in Italian format...* | `10/10/2026`, `-630,39 €`, "Saldo carta di credito" |
| `TransactionsPage`: *a filter restarts from the first page* | il filtro manda `page: 0` |
| `TransactionsPage`: *a correction is saved and the page is reloaded* | correzione salvata e pagina ricaricata |
| `App`: *shows the links to the pages* | la barra con i link |

### 8. La pipeline

In `.github/workflows/ci.yml` c'è ora il job **`frontend`**, che gira in parallelo al job `backend`:
installa Node.js 24, poi `npm ci` (installa **esattamente** le versioni di `package-lock.json`),
`npm test -- --watch=false` e `npm run build`.

---

## Parte C: cosa fare

### 1. Node.js 24 e Angular CLI

Angular 22 richiede **Node.js 24 LTS** (o almeno 22.22.3).

**Se usi nvm-windows** (più versioni di Node sullo stesso PC, ad esempio perché un altro progetto ne
richiede una diversa):

```powershell
nvm list                         # le versioni installate
nvm install 24.21.0              # solo se la 24 non c'è ancora
nvm use 24.21.0                  # vale per tutto il PC, non solo per questa finestra
node -v                          # v24.21.0
npm install -g @angular/cli      # il comando "ng", installato SOLO per questa versione di Node
ng version
```

Con nvm ogni versione di Node ha la sua cartella, e i programmi installati con `npm install -g` stanno
**dentro** quella cartella. Per questo la CLI di Angular va installata con la 24 attiva, e un programma
installato con un'altra versione (es. `clasp`) va reinstallato anche per la 24, se ti serve lì.
Ricordati di fare `nvm use` con la versione giusta prima di lavorare su un progetto.

**Se non usi nvm**, installa direttamente Node.js LTS:

```powershell
winget install OpenJS.NodeJS.LTS          # poi chiudi e riapri PowerShell
node -v
npm install -g @angular/cli
ng version
```

> **Errore "l'esecuzione di script è disabilitata nel sistema"?** Su Windows PowerShell blocca gli
> script come `npm.ps1` e `ng.ps1`. Si risolve una volta per tutte, per il tuo utente:
> `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.

### 2. Aggiorna e installa le dipendenze

```powershell
git pull
cd frontend
npm install               # scarica le dipendenze in node_modules (la prima volta qualche minuto)
npm test -- --watch=false # 8 test
```

Non preoccuparti dell'avviso `npm warn install-scripts`: le versioni recenti di npm non eseguono più in
automatico gli script di installazione dei pacchetti (una protezione contro i pacchetti malevoli), e
la dashboard funziona lo stesso.

### 3. Avvia tutto

Servono **tre** cose accese: il database, il backend e la dashboard. Usa due finestre di PowerShell:

```powershell
# Finestra 1, dalla radice del progetto
docker compose up -d
cd backend
.\mvnw.cmd spring-boot:run
```

```powershell
# Finestra 2
cd frontend
npm start
```

Apri <http://localhost:4200>.

### 4. Prova

1. **Movimenti:** filtra per tipo **"Da verificare"**: sono i movimenti di Trade Republic senza
   descrizione. Correggine uno (es. "Acquisto titoli") dal menu: comparirà "corretto a mano · annulla".
2. Filtra per **conto** e scorri le pagine con "Successiva".
3. **Conti:** controlla che la carta di credito abbia il ruolo **"Carta di credito"**, e guarda la colonna
   "Ultimo aggiornamento": se un conto ha avuto un errore (es. 429), lo vedi in rosso.
4. **Ricompilazione automatica:** con `npm start` acceso, apri `src/app/accounts/accounts-page.html` e
   cambia il titolo `<h1>Conti</h1>` in `<h1>I miei conti</h1>`. Salva: il browser si aggiorna da solo
   in un attimo. Poi rimettilo com'era.

### Che editor usare?

Per il frontend va benissimo **VS Code** (gratuito) con l'estensione **Angular Language Service**, che
completa e controlla i template. IntelliJ IDEA Community ha un supporto limitato per Angular e
TypeScript (il supporto completo è nella versione a pagamento). Molti sviluppatori usano IntelliJ per il
backend e VS Code per il frontend.

---

## Cosa hai imparato

- Che cos'è una **single page application**, e perché durante lo sviluppo ci sono **due server**.
- La **same-origin policy**, CORS e il **proxy** di sviluppo.
- Angular moderno: **standalone**, `app.config.ts`, **control flow** (`@if`, `@for`), **signals**
  (`signal`, `computed`), `inject()`, **zoneless**.
- Un servizio con `HttpClient` e `HttpParams`, e i **tipi** TypeScript che rispecchiano i DTO Java.
- Pipe con la **lingua italiana** (`LOCALE_ID`).
- Test con **Vitest**: finto server HTTP e servizi finti.
- Un secondo job nella **pipeline CI**.

## Prossimo passo

**Fase 2, passo 3:** collegare una banca dalla dashboard (niente più indirizzo da copiare e incollare
nel programma a riga di comando) e il pulsante **"Aggiorna ora"**.
