# 46. Fase 4, passo 3: il PIN per aprire l'app

**Obiettivo di questo passo:** prima di rendere l'app raggiungibile dal telefono (passo 4, Tailscale), serve una
porta chiusa a chiave. Da qui in poi l'app si apre solo con un **PIN**:
- il PIN si crea la prima volta **dal PC**;
- su ogni dispositivo si inserisce una volta, e vale finché lo si usa: dopo **12 ore** senza usarlo, o dopo un
  riavvio del backend, lo richiede;
- dopo **5 PIN sbagliati** di fila, le prove si fermano per un po';
- se lo dimentichi, si azzera **solo dal PC**, con lo script di installazione.

| Passo | Che cosa | Stato |
|---|---|---|
| 1 | tre container + Ollama facoltativo, nginx (`docs/44`) | fatto |
| 2 | lo script di installazione, la password del database (`docs/45`) | fatto |
| **3** | **il PIN per aprire l'app** | **questa guida** |
| 4 | Tailscale: l'app dal telefono con https vero, redirect di Enable Banking | da fare |
| 5 | backup e ripristino, provato davvero | da fare |
| 6 | aggiornamenti, la guida per chi non è informatico, il doppio clic | da fare |

> Prerequisiti: i passi 1 e 2 e `git pull`. Al primo avvio Flyway applica la migrazione **V29**. Poi lancia lo
> script come sempre (`docs/45`): le immagini si ricostruiscono da sole.

**Le scelte fatte insieme:**

| Domanda | Scelta |
|---|---|
| quanto è lungo il PIN | da 6 a 10 cifre |
| ogni quanto lo richiede | dopo 12 ore senza usare l'app (e dopo un riavvio del backend) |
| la copia dei dati sul telefono | solo a sessione aperta: si cancella quando si esce o la sessione scade |
| come si crea la prima volta | nell'app, solo dal PC |

**Indice**
- Parte A: come funziona un login con PIN
- Parte B: Spring Security e la sessione
- Parte C: il PIN nel backend
- Parte D: CSRF, il gettone
- Parte E: il frontend
- Parte F: la copia dei dati sul telefono
- Parte G: il PIN dimenticato
- Parte H: i test
- Parte I: la sicurezza di questo passo
- Parte J: prova
- Parte K: verifiche fatte e difetti trovati
- Parte L: problemi comuni
- Esercizi guidati

---

## Parte A: come funziona un login con PIN

![Il PIN e la sessione, passo per passo](img/pin-sessione.svg)

1. **La dashboard chiede lo stato** (`GET /api/auth/status`): c'è un PIN? Questo browser è già dentro?
2. Non è dentro: Angular mostra **"Inserisci il PIN"** al posto dell'app.
3. **Il PIN parte verso il backend** (`POST /api/auth/login`). Il backend lo controlla e, se è giusto, apre una
   **sessione**: si ricorda "questo browser ha inserito il PIN" e gli dà un **cookie** con il numero della sessione.
4. Da qui in poi il browser **aggiunge da solo il cookie** a ogni richiesta, e il backend riconosce la sessione.
5. Dopo 12 ore senza richieste (o a un riavvio) la sessione si chiude. La richiesta successiva riceve **401**, e
   l'app torna alla pagina del PIN.

Tre idee da avere chiare:

- **Il PIN non si salva mai.** Si salva il suo **hash**: il risultato di un calcolo che non si può fare al
  contrario. Per controllare un PIN, il backend rifà il calcolo sul PIN scritto e confronta i risultati. Chi
  rubasse il database troverebbe `{bcrypt}$2a$12$...`, non `135792`.
- **La sessione è come il braccialetto di un concerto.** All'ingresso mostri il biglietto (il PIN) una volta, e ti
  danno un braccialetto (il cookie). Poi ti basta il braccialetto. Il braccialetto scade (12 ore), e si può tagliare
  (Esci).
- **Una persona sola.** L'app è per una persona sola (`docs/03`), quindi c'è un PIN solo, niente nomi utente.

---

## Parte B: Spring Security e la sessione

### B1. Che cos'è Spring Security

È la libreria standard di Spring per la sicurezza. Lavora come il nostro "buttafuori" `LocalOnlyRequestFilter`
(`docs/17`): una **catena di filtri** che guarda ogni richiesta **prima** dei controller. Si aggiunge al progetto con
una dipendenza:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

Appena aggiunta, Spring Security **chiude tutto**: è la scelta prudente. Poi gli si dice che cosa aprire, in una
classe di configurazione, `auth/SecurityConfig.java`:

```java
http
    .authorizeHttpRequests(requests -> requests
        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
        .requestMatchers("/api/auth/**").permitAll()
        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
        .anyRequest().authenticated())
    .csrf(csrf -> csrf.spa())
    .formLogin(AbstractHttpConfigurer::disable)
    .httpBasic(AbstractHttpConfigurer::disable)
    .logout(AbstractHttpConfigurer::disable)
    .exceptionHandling(errors -> errors
        .authenticationEntryPoint((request, response, e) -> problem(response, 401, "Inserisci il PIN."))
        .accessDeniedHandler((request, response, e) -> problem(response, 403,
                "Richiesta rifiutata: ricarica la pagina e riprova.")));
```

Riga per riga:

| Riga | Che cosa vuol dire |
|---|---|
| `.requestMatchers("/api/auth/**").permitAll()` | le richieste del PIN sono aperte a tutti: senza, non si potrebbe nemmeno inserirlo |
| `.requestMatchers("/actuator/health", ...).permitAll()` | il controllo di salute di Docker (`docs/44`, parte C3) non ha un PIN |
| `.anyRequest().authenticated()` | **tutto il resto** solo con una sessione aperta; senza, 401 |
| `.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()` | quando una richiesta già controllata finisce in errore (es. file troppo grande, 413), Spring la "rigira" a una pagina di errore interna: senza questa riga, l'errore vero diventerebbe un 401 |
| `.csrf(csrf -> csrf.spa())` | la protezione CSRF per le app con il frontend separato: parte D |
| `.formLogin(...disable)`, `.httpBasic(...)`, `.logout(...)` | Spring offre di serie una sua pagina di login, il login "Basic" (la finestrella grigia del browser con utente e password) e un suo `/logout`: non li usiamo, il PIN lo chiede la dashboard |
| `.exceptionHandling(...)` | che cosa rispondere quando manca la sessione (401) o il gettone CSRF (403): un JSON con il motivo in `detail`, come gli altri errori delle API (`docs/09`) |

**L'ordine dei filtri.** Il buttafuori `LocalOnlyRequestFilter` ora ha `@Order(Ordered.HIGHEST_PRECEDENCE + 50)`:
lavora **prima** di Spring Security, che sta a -100. Una richiesta con un nome falso (DNS rebinding) viene rifiutata
prima di tutto il resto, anche prima di sentirsi dire "serve il PIN".

### B2. La sessione e il suo cookie

Le regole della sessione sono in `application.yml`:

```yaml
server:
  forward-headers-strategy: native
  servlet:
    session:
      timeout: 12h
      tracking-modes: cookie
      cookie:
        name: WALLETTRACKER_SESSION
        http-only: true
        same-site: strict
```

| Impostazione | Che cosa fa | Contro che cosa |
|---|---|---|
| `timeout: 12h` | dopo 12 ore **senza richieste** la sessione si chiude (ogni richiesta fa ripartire il conto) | un telefono perso con l'app aperta |
| sessioni in memoria | le sessioni stanno nella memoria del backend: un riavvio (un aggiornamento) le chiude tutte | — è una conseguenza, non una scelta: comoda per azzerare tutto (parte G) |
| `tracking-modes: cookie` | la sessione si riconosce **solo** dal cookie, mai da un `;jsessionid=...` nell'indirizzo | numeri di sessione finiti nella cronologia, nei log, in un link copiato |
| `name` | il nome del cookie (di serie sarebbe `JSESSIONID`) | — |
| `http-only: true` | il JavaScript della pagina **non può leggerlo** | uno script infilato nella pagina (XSS) che ruba il cookie |
| `same-site: strict` | il browser **non lo manda** nelle richieste partite da un altro sito | CSRF (parte D) |

**E `Secure`?** Un cookie `Secure` viaggia **solo su https**. Non lo scriviamo fisso, perché oggi l'app è su
`http://localhost:8000`: con `Secure` il browser non rimanderebbe il cookie, e non si entrerebbe mai. Tomcat lo
aggiunge da solo **quando la richiesta è https**. Ma la richiesta al backend arriva da nginx, sempre in http: come fa
Tomcat a saperlo? Gliel'ha detto nginx, con l'intestazione `X-Forwarded-Proto` (`docs/44`, parte D3).
`forward-headers-strategy: native` dice a Tomcat di crederci, ma **solo** se la richiesta arriva da un indirizzo
interno (la rete di Docker, 127.0.0.1): un estraneo che scrivesse `X-Forwarded-Proto` a mano non conterebbe. Al
passo 4, con Tailscale e https, il cookie diventerà `Secure` senza toccare niente. Lo prova `SessionCookieTest`.

**Il cookie in pratica**, come lo manda il backend (si vede in F12 → Application → Cookies):

```
WALLETTRACKER_SESSION=3F2A...; Path=/; HttpOnly; SameSite=Strict
XSRF-TOKEN=8c1d...; Path=/
```

Il secondo è il gettone CSRF: **non** è `HttpOnly`, di proposito (parte D).

---

## Parte C: il PIN nel backend

### C1. La tabella (V29)

```sql
CREATE TABLE app_pin (
    id              INT         PRIMARY KEY CHECK (id = 1),
    pin_hash        TEXT        NOT NULL,
    failed_attempts INT         NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Al massimo una riga (`CHECK (id = 1)`), come `ai_settings` (`docs/27`). Ma qui **nessuna riga** ha un significato:
"il PIN non c'è ancora". Azzerare il PIN vuol dire cancellare la riga (parte G).

Il conteggio degli errori sta **nel database**, non in memoria: altrimenti basterebbe far riavviare il backend per
ricominciare da zero.

### C2. Il blocco (`AppPin`)

```java
static final int FREE_ATTEMPTS = 5;
static final List<Duration> WAITS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
        Duration.ofMinutes(15), Duration.ofHours(1));

void failed(Instant now) {
    failedAttempts++;
    if (failedAttempts >= FREE_ATTEMPTS) {
        int step = Math.min(failedAttempts - FREE_ATTEMPTS, WAITS.size() - 1);
        lockedUntil = now.plus(WAITS.get(step));
    }
    updatedAt = now;
}
```

| Errore n. | Che cosa succede |
|---|---|
| 1, 2, 3, 4 | "PIN sbagliato. Ancora N tentativi prima di una pausa." |
| 5 | pausa di **1 minuto** |
| 6 | pausa di 5 minuti |
| 7 | pausa di 15 minuti |
| 8 e oltre | pausa di **1 ora**, ogni volta |
| il PIN giusto | il conteggio torna a zero |

**Perché basta.** Un PIN di 6 cifre ha un milione di combinazioni. Con 5 tentativi liberi e poi circa uno all'ora,
provarle tutte richiederebbe più di cent'anni. Il conteggio è **unico per l'app**, non per dispositivo: chi prova i
PIN da cento telefoni non ottiene cento volte i tentativi. Il rovescio: chi sbaglia apposta può farti aspettare. Per
un'app personale dentro la tua rete Tailscale è il compromesso giusto.

La regola sta nella classe dell'entità, vicino ai dati che usa, ed è provata da sola, senza database (`AppPinTest`).

### C3. Il controllo (`PinService`)

```java
@Transactional
public Attempt check(String pin) {
    requireValid(pin);
    AppPin saved = repository.findForUpdate().orElseThrow(...);
    Instant now = clock.instant();
    Duration locked = saved.lockedFor(now);
    if (!locked.isZero()) {
        return new Attempt.Locked(locked);
    }
    if (encoder.matches(pin, saved.pinHash())) {
        saved.succeeded(now);
        return new Attempt.Correct();
    }
    saved.failed(now);
    ...
}
```

Le idee:

- **Durante la pausa il PIN non si guarda nemmeno.** Se lo controllassimo, chi prova i PIN capirebbe lo stesso,
  dalla risposta, se ha indovinato: la pausa non servirebbe a niente.
- **`findForUpdate()`** è un `SELECT ... FOR UPDATE` (l'annotazione `@Lock(PESSIMISTIC_WRITE)` nel repository): la
  riga resta "bloccata" fino alla fine della transazione. Senza, chi prova i PIN potrebbe mandarne cento **nello
  stesso istante**: tutti leggerebbero "0 errori finora", e il blocco verrebbe scavalcato.
- **`encoder.matches`** rifà l'hash del PIN scritto e lo confronta con quello salvato. L'encoder è **bcrypt con costo
  12** (`SecurityConfig`): 2¹² giri di calcolo, circa un quarto di secondo. Per chi entra non si nota; per chi avesse
  il database e volesse provare un milione di PIN sono giorni di calcolo invece di secondi. Il
  `DelegatingPasswordEncoder` scrive davanti all'hash il nome dell'algoritmo (`{bcrypt}`): se un giorno lo
  cambiassimo, i PIN già salvati si riconoscerebbero ancora.
- **Un solo istante** (`Instant now = clock.instant()`) per tutto il tentativo. L'abbiamo aggiunto **dopo averlo
  visto sbagliare** (parte K): leggendo l'orologio due volte, a qualche millisecondo di distanza, la pausa di un
  minuto appena iniziata risultava "59 secondi".
- **L'orologio è un bean** (`Clock`): nei test ne usiamo uno fermo e lo spostiamo avanti di 61 secondi, invece di
  aspettare davvero (parte H).
- **`sealed interface Attempt`**: i tre risultati possibili (`Correct`, `Wrong`, `Locked`) sono `record`. Il
  controller li distingue con uno `switch` sui tipi, e il compilatore controlla che non ne manchi nessuno.

### C4. Le API (`AuthController`)

| Richiesta | Risposta |
|---|---|
| `GET /api/auth/status` | `{"pinSet":..., "authenticated":..., "canCreatePin":...}` più il cookie `XSRF-TOKEN` |
| `POST /api/auth/pin` `{"pin":"..."}` | 204 e la sessione aperta; 403 se non dal PC; 409 se il PIN c'è già; 400 se non è di 6-10 cifre |
| `POST /api/auth/login` `{"pin":"..."}` | 204 e la sessione aperta; 401 sbagliato (con `attemptsLeft`); 429 in pausa (con `retryAfterSeconds` e l'intestazione `Retry-After`) |
| `POST /api/auth/logout` | 204: la sessione è chiusa |

**Il PIN si crea solo dal PC.** `POST /api/auth/pin` controlla il nome con cui è stata aperta l'app (l'header
`Host`, che nginx passa così com'è): deve essere `localhost` o `127.0.0.1`. Quando al passo 4 l'app sarà
raggiungibile anche come `pc-casa.<rete>.ts.net`, da lì il PIN non si potrà creare: nessuno potrà "arrivare prima di
te". Una precisazione onesta: l'header `Host` lo scrive chi fa la richiesta, quindi questo controllo protegge da un
errore o da un dispositivo distratto della tua rete, non da un dispositivo della tua rete Tailscale programmato
apposta. La vera protezione è che il PIN lo crei **adesso**, dal PC, prima di accendere Tailscale; e dopo, una volta
che c'è, non si può più creare da nessuna parte.

**Aprire la sessione** (`openSession`):

```java
if (request.getSession(false) != null) {
    request.changeSessionId();
}
SecurityContext context = SecurityContextHolder.createEmptyContext();
context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(OWNER, null, List.of()));
SecurityContextHolder.setContext(context);
contextRepository.saveContext(context, request, response);
```

- **`changeSessionId()`** è la difesa dalla *session fixation*. Un attaccante che fosse riuscito a far usare al tuo
  browser un numero di sessione scelto da lui aspetterebbe che tu inserisca il PIN, per usare poi lo stesso numero.
  Cambiando il numero al momento del PIN, il suo resta "fuori";
- **`SecurityContext`** è il modo in cui Spring Security ricorda "chi è dentro" per la richiesta in corso;
  **`saveContext`** lo salva nella sessione (`HttpSessionSecurityContextRepository`), così vale anche per le
  richieste successive.

**Lo stato prepara il gettone CSRF.** Il metodo `status` riceve un parametro `CsrfToken csrfToken` e chiama
`csrfToken.getToken()`, anche se non lo usa: è il modo per chiedere a Spring di preparare il gettone e mandarlo nel
cookie `XSRF-TOKEN`. È la prima richiesta della dashboard, quindi da lì in poi Angular ce l'ha.

---

## Parte D: CSRF, il gettone

### D1. L'attacco

CSRF (*Cross-Site Request Forgery*, "richiesta falsificata da un altro sito"): mentre sei dentro WalletTracker,
apri in un'altra scheda un sito malevolo. Quel sito fa partire dal **tuo** browser una richiesta verso
`http://localhost:8000/api/...` per modificare qualcosa. Il browser, di suo, aggiungerebbe il cookie della sessione:
la richiesta sembrerebbe tua.

### D2. Tre difese, una sopra l'altra

| Difesa | Come funziona | Dal passo |
|---|---|---|
| `Origin` controllato | il buttafuori rifiuta le modifiche se il browser dichiara che partono da un altro sito | Fase 2 (`docs/17`, n. 3) |
| `SameSite=Strict` | il browser non manda il cookie della sessione nelle richieste partite da un altro sito | questo |
| **il gettone CSRF** | ogni modifica deve portare un valore che solo le nostre pagine possono leggere | questo |

Perché tre? Perché ognuna ha dei casi limite (browser vecchi, configurazioni particolari), e il costo di aggiungerle
è piccolo. È il principio della **difesa in profondità**.

### D3. Come funziona il gettone

```
 1. il backend manda il cookie   XSRF-TOKEN=8c1d...        (leggibile dal JavaScript: niente HttpOnly)
 2. Angular lo legge e lo rimanda come header, nelle richieste che modificano (POST, PUT, DELETE):
                                 X-XSRF-TOKEN: 8c1d...
 3. Spring Security confronta header e cookie: diversi o mancanti → 403
```

Il trucco: un altro sito può far partire una richiesta verso di noi, ma **non può leggere i nostri cookie** (la
regola della stessa origine, `docs/44` parte D3b). Quindi non può scrivere l'header giusto.

**Angular lo fa da solo.** `HttpClient` ha la protezione XSRF accesa di serie: per le richieste che modificano,
verso il nostro stesso indirizzo, legge il cookie `XSRF-TOKEN` e aggiunge l'header `X-XSRF-TOKEN`. Sono proprio i
nomi che usa Spring Security con `csrf().spa()`: nel frontend non c'è niente da scrivere.

L'abbiamo provato nel browser: la stessa modifica con il gettone risponde 200, senza il gettone 403.

---

## Parte E: il frontend

### E1. L'app o la pagina del PIN

`app.html` ora comincia così:

```html
@if (auth.state() !== 'inside') {
  <app-pin-page />
} @else {
  <div class="shell"> ... l'app di sempre ... </div>
}
```

Finché non si è dentro, l'app **non c'è nemmeno**: niente navigazione, niente pagine, nessuna richiesta di dati. Il
router intanto **ricorda l'indirizzo** aperto: dopo il PIN si arriva proprio lì. Conta per due casi:
- aprendo un link diretto, come `http://localhost:8000/movimenti`;
- al ritorno dalla banca (`/callback?code=...&state=...`, `docs/13`), se nel frattempo la sessione era scaduta.

L'abbiamo provato: aperta `/movimenti`, inserito il PIN, si è su Movimenti.

### E2. Lo stato condiviso (`AuthStore`)

`auth/auth-store.ts` è un servizio con un signal, come `SetupStore` (`docs/16`):

| Stato | Quando | Che cosa si vede |
|---|---|---|
| `checking` | all'apertura, finché il backend non risponde | "Un attimo…" |
| `create` | il PIN non c'è, e l'app è aperta dal PC | "Crea il tuo PIN" (due campi) |
| `create-elsewhere` | il PIN non c'è, e l'app è aperta da un altro dispositivo | "Crea il PIN dal PC" |
| `login` | il PIN c'è, e questo browser non è dentro | "Inserisci il PIN" |
| `unreachable` | il backend non risponde, e non c'è una sessione recente | "Il PC non risponde" e "Riprova" |
| `inside` | dentro | l'app |

### E3. La sessione scaduta: l'intercettore

Dopo 12 ore, o dopo un riavvio del backend, la prossima richiesta riceve 401. Invece di gestirlo in ogni pagina, c'è
un **intercettore** (`auth/auth-interceptor.ts`): una funzione che HttpClient chiama per **ogni** richiesta e
risposta dell'app.

```ts
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthStore);
  return next(request).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401
          && request.url.startsWith('/api/') && !request.url.startsWith('/api/auth/')) {
        auth.sessionLost();
      }
      return throwError(() => error);
    }),
  );
};
```

- `next(request)` lascia proseguire la richiesta; `catchError` guarda gli errori che tornano;
- un 401 da `/api/auth/login` **non** conta: lì vuol dire "PIN sbagliato", e lo mostra la pagina del PIN;
- `throwError` fa proseguire l'errore verso chi aveva fatto la richiesta: l'intercettore osserva, non nasconde.

Si registra in `app.config.ts`: `provideHttpClient(withFetch(), withInterceptors([authInterceptor]))`.

Dopo un riavvio del backend, nel browser: un tocco su "Analisi" porta alla pagina del PIN; inserito il PIN, si è su
Analisi.

### E4. La pagina del PIN (`auth/pin-page.ts`)

- I campi sono `type="password"` (le cifre non si vedono) con `inputmode="numeric"`: sul telefono si apre la
  **tastierina dei numeri**;
- `autocomplete="new-password"` alla creazione e `current-password` all'inserimento: i gestori di password capiscono
  che cosa sono;
- alla creazione il PIN si scrive **due volte**, e la pagina controlla subito che sia di 6-10 cifre e che i due siano
  uguali, prima di mandarlo;
- il motivo di un errore è quello scritto dal backend ("PIN sbagliato. Ancora 3 tentativi prima di una pausa.");
- un PIN sbagliato **si cancella** dal campo;
- durante una pausa (429) il pulsante è spento e dice quanto manca: "Aspetta 1 min", poi i secondi.

### E5. Esci

"Esci" è in fondo alla colonna sul PC, e in fondo alla pagina "Altro" sul telefono. Chiude la sessione nel backend
(`POST /api/auth/logout`) e cancella dal dispositivo la copia dei dati (parte F).

---

## Parte F: la copia dei dati sul telefono

### F1. Il problema

Con l'app installata, il service worker tiene una **copia** delle ultime letture, fino a 7 giorni (`docs/42`), per
mostrarle a PC spento o senza rete. Ma senza rete il backend non può dire se la sessione è aperta: chi avesse in mano
il tuo telefono sbloccato potrebbe aprire l'app offline e vedere la copia, **senza PIN**.

La scelta: **la copia vale solo a sessione aperta.**

### F2. Come

1. **Quando la copia si cancella:**
   - quando premi "Esci";
   - quando il backend risponde 401 (sessione scaduta);
   - all'apertura dell'app, se il backend dice "non sei dentro".
2. **Senza rete**, l'app si apre sulla copia **solo** se il backend ha confermato "sei dentro" nelle **ultime 12
   ore**, la stessa durata della sessione. L'ultima conferma è ricordata nel browser (`localStorage`,
   `wallettracker.session-seen`); altrimenti: "Il PC non risponde".

### F3. Svuotare il cassetto, non buttarlo

Le copie stanno nella **Cache Storage** del browser, in "cassetti" con un nome (F12 → Application → Cache Storage).
Le risposte del gruppo "dati" di `ngsw-config.json` stanno in `ngsw:/:1:data:dati:cache`.

Il primo tentativo **buttava** il cassetto (`caches.delete(nome)`). Provando, abbiamo scoperto che non bastava: il
service worker acceso tiene il cassetto **aperto**, e continuava a usarlo, invisibile, finché non si spegneva.
Dopo un nuovo PIN la copia sembrava vuota, eppure senza rete i dati comparivano. La soluzione è **svuotarlo**:
cancellare una per una le risposte che contiene. Così spariscono anche per il service worker.

```ts
for (const name of names) {
  const drawer = await caches.open(name);
  const saved = await drawer.keys();
  await Promise.all(saved.map((request) => drawer.delete(request)));
}
```

Accanto ci sono due cassetti "di servizio" del service worker (`...:lru` e `...:age`), dove tiene il suo registro:
quali **indirizzi** ha in copia e da quando, **senza** i dati. Li lasciamo: il service worker li riscrive dalla sua
memoria, quindi svuotarli non servirebbe. È il rischio che resta: chi ha il telefono può vedere *quali* pagine hai
aperto di recente (per esempio una ricerca), non i loro dati.

### F4. Il rischio che resta, in chiaro

Telefono rubato **sbloccato**, con una sessione usata nelle ultime 12 ore, e messo subito **senza rete**: si vedono
gli ultimi dati scaricati. È il prezzo della funzione "a PC spento vedi gli ultimi dati". Il blocco dello schermo del
telefono resta la prima difesa.

---

## Parte G: il PIN dimenticato

Il PIN non si recupera: nel database c'è solo il suo hash. Si **azzera**, e lo si ricrea:

```powershell
powershell -ExecutionPolicy Bypass -File installa.ps1 -ReimpostaPin     # Windows
```
```bash
./installa.sh --reimposta-pin                                         # macOS / Linux
```

Lo script:
1. cancella la riga del PIN (`DELETE FROM app_pin`), dentro il container del database;
2. **riavvia il backend**: le sessioni, che stanno nella sua memoria, si chiudono tutte, anche quelle di un
   telefono perso;
3. alla prossima apertura dal PC (`http://localhost:8000`), l'app chiede "Crea il tuo PIN".

**Perché niente domanda segreta o email.** Ogni modo di recuperare il PIN "da fuori" sarebbe una seconda porta, da
difendere anche lei. Così l'unica strada è avere in mano il PC: chi ce l'ha, ha comunque già tutto.

---

## Parte H: i test

### H1. I test di prima, con il PIN

Con Spring Security, i test delle API avrebbero ricevuto tutti 401. Invece di toccarli uno per uno, in
`IntegrationTestConfiguration` c'è una configurazione che fa partire **ogni** richiesta di MockMvc "come chi ha già
inserito il PIN":

```java
@Bean
MockMvcBuilderCustomizer authenticatedRequests() {
    return builder -> builder.defaultRequest(MockMvcRequestBuilders.get("/").with(user("proprietario")).with(csrf()));
}
```

`user(...)` e `csrf()` vengono da `spring-security-test` (la dipendenza `spring-boot-starter-security-test`). I test
che usano il server web vero (`UploadLimitTest`, `HealthEndpointTest`) aprono invece una sessione vera, con
`auth/TestSession`: un piccolo "browser" che fa quello che fa la dashboard (stato, PIN, cookie, gettone).

### H2. I test nuovi

| Test | Che cosa prova |
|---|---|
| `AppPinTest` (2) | la regola del blocco, da sola: 4 errori liberi, poi 1, 5, 15 minuti, un'ora; il PIN giusto azzera il conteggio |
| `AuthControllerTest` (9) | senza PIN 401; lo stato dà il gettone; il PIN si crea solo dal PC e una volta sola; 6-10 cifre; senza gettone CSRF 403; 5 errori, poi pausa (429, `Retry-After: 60`) anche con il PIN giusto, finita la pausa si entra; la seconda pausa è di 5 minuti; il numero di sessione cambia al login; Esci chiude la sessione; il controllo di salute resta aperto; il PIN si salva come `{bcrypt}$2a$12$...` |
| `SessionCookieTest` (2) | con il server vero: il cookie è `HttpOnly` e `SameSite=Strict`, senza `Secure` su http; con `X-Forwarded-Proto: https` diventa `Secure` |
| `HealthEndpointTest` | gli endpoint di Actuator nascosti: senza PIN 401, con il PIN 404 |
| `auth-store.spec.ts` (6) | gli stati; l'invio del PIN; senza rete, la copia solo con una conferma recente; un 401 chiude la sessione, un 401 del PIN no; Esci |
| `pin-page.spec.ts` (4) | i due campi, i controlli prima dell'invio, il motivo del backend, il PIN sbagliato che si cancella, la pausa con il pulsante spento, "Crea il PIN dal PC" |
| `app.spec.ts` (+1) | senza PIN c'è solo la pagina del PIN, non la navigazione |

**L'orologio finto.** In `AuthControllerTest`, `TestClock` è un `Clock` che si sposta solo quando lo dice il test
(`clock.advance(Duration.ofSeconds(61))`). È registrato come bean `@Primary`, quindi `PinService` riceve lui invece
dell'orologio vero. Così la prova della pausa dura un istante.

**La CI** ora fa il giro completo, con `curl`:
- controlla che senza PIN `/api/accounts` risponda 401;
- crea il PIN dal "PC", con il gettone CSRF letto dal cookie, ed entra;
- cambia la password del database;
- azzera il PIN con lo script, e controlla che la vecchia sessione non valga più.

---

## Parte I: la sicurezza di questo passo

In `docs/17` sono le righe **32-35**:
- **32: il PIN.** Hash bcrypt, pause dopo gli errori con il conteggio nel database e la riga bloccata durante il
  controllo, PIN creato e azzerato solo dal PC;
- **33: la sessione.** 401 senza; cookie `HttpOnly`, `SameSite=Strict`, `Secure` con https; 12 ore; *session
  fixation*; solo cookie;
- **34: CSRF con gettone**, in aggiunta a `Origin` e `SameSite`;
- **35: la copia dei dati** sul dispositivo solo a sessione aperta.

Dalla lista delle protezioni pianificate esce il **login**: fatto. Il principio "nessun accesso da fuori dal PC
finché non c'è il login" ora permette il passo 4.

---

## Parte J: prova

1. `git pull`, poi lancia lo script come sempre:
   ```powershell
   powershell -ExecutionPolicy Bypass -File installa.ps1
   ```
   Le immagini si ricostruiscono (qualche minuto), Flyway applica la V29.
2. Si apre `http://localhost:8000`: **"Crea il tuo PIN"**. Scegline uno di 6-10 cifre (non quello del bancomat),
   scrivilo due volte, **Crea il PIN ed entra**. Ci sono i tuoi dati di sempre.
3. Premi **Esci** (in fondo alla colonna). Torna "Inserisci il PIN".
4. Sbaglia il PIN cinque volte: dopo il quinto, il pulsante dice "Aspetta 1 min". Dopo un minuto, entra con quello
   giusto.
5. Lascia l'app aperta e riavvia il backend (`docker compose restart backend`): al primo tocco su una pagina, l'app
   chiede di nuovo il PIN.
6. F12 → **Application → Cookies → http://localhost:8000**: `WALLETTRACKER_SESSION` con la spunta HttpOnly e
   SameSite Strict; `XSRF-TOKEN` senza HttpOnly.
7. F12 → **Application → Cache Storage**: apri `ngsw:/:1:data:dati:cache` e guarda le risposte salvate. Premi Esci
   e riaprilo (tasto destro → Refresh): è vuoto.
8. Per curiosità, prova l'azzeramento: `... installa.ps1 -ReimpostaPin`. L'app chiederà di creare un PIN nuovo.

**Per sviluppare** (`npm start` e `./mvnw spring-boot:run`) il PIN è lo stesso dell'app nei container, perché sta
nel database.

---

## Parte K: verifiche fatte e difetti trovati

Prove con una copia nuova del progetto installata con lo script, un anno di movimenti inventati sul conto "Banca
Alfa", e Chromium in formato telefono e PC.

| Prova | Esito |
|---|---|
| test del backend: 335, tutti verdi (1 saltato, come prima) | ✅ |
| test del frontend: 203 verdi, Prettier, build | ✅ |
| senza PIN: `/api/accounts` 401, `/actuator/health` 200 | ✅ |
| da un altro nome ("pc-casa.esempio.ts.net"): "Crea il PIN dal PC", nessun campo | ✅ |
| dal PC: "Crea il tuo PIN"; due PIN diversi: "I due PIN non sono uguali" | ✅ |
| PIN creato aprendo `/movimenti`: dopo il PIN si è su Movimenti | ✅ |
| una modifica con il gettone CSRF 200, senza 403 | ✅ |
| cookie: `WALLETTRACKER_SESSION` HttpOnly, SameSite Strict; `XSRF-TOKEN` leggibile | ✅ |
| cinque errori: "Ancora 4… 1 tentativo", poi "riprova tra 1 minuto", pulsante "Aspetta 1 min" spento; dopo 61 s si entra | ✅ |
| backend riavviato: al tocco su Analisi, pagina del PIN; dopo il PIN, su Analisi | ✅ |
| senza rete (nginx fermo) con una sessione recente: Movimenti dalla copia, 50 movimenti | ✅ |
| Esci: risposte in copia 0, subito e dopo 3 secondi; `/api/accounts` 401; conferma dimenticata | ✅ |
| senza rete dopo Esci: "Il PC non risponde"; una lettura dei movimenti dal service worker: 504, niente dati | ✅ |
| sul PC: "Esci" in fondo alla colonna | ✅ |
| `--reimposta-pin` e `-ReimpostaPin`: PIN azzerato, vecchia sessione 401, nuovo PIN creato | ✅ |

**Difetti trovati provando, e corretti:**
1. **"59 secondi" invece di "1 minuto".** Il backend leggeva l'orologio due volte: un solo istante per tentativo
   (parte C3);
2. **La copia dei dati non si cancellava davvero.** Buttare il cassetto non bastava, perché il service worker lo
   teneva aperto: ora si svuota (parte F3);
3. **Un `.env` nuovo per un database vecchio.** Durante le prove il volume del database di una prova precedente era
   rimasto, e lo script ha creato un `.env` con una password nuova: il backend non entrava ("password authentication
   failed"). Lo stesso succederebbe a chi cancella il `.env` e reinstalla. Ora lo script se ne accorge e si ferma,
   spiegando che cosa fare (`docs/45`, parte M);
4. **Un mio contatore sbagliato.** Dopo Esci sembravano restare 8 risposte in copia: contavo anche il registro del
   service worker (parte F3). Le risposte vere erano zero.

**Non provato:** il PIN da un telefono vero (arriva con il passo 4), e l'avvio da sviluppo (`npm start` con il backend avviato a mano). Il proxy di `ng serve` passa i cookie così come sono, quindi dovrebbe funzionare uguale: se vedi qualcosa di strano, dimmelo.

---

## Parte L: problemi comuni

| Sintomo | Causa probabile | Che cosa fare |
|---|---|---|
| "Crea il PIN dal PC" anche sul PC | hai aperto l'app con un altro nome (es. l'indirizzo IP del PC) | apri `http://localhost:8000` |
| "Richiesta rifiutata: ricarica la pagina e riprova" | manca il gettone CSRF (cookie `XSRF-TOKEN` cancellato o scaduto) | ricarica la pagina: lo stato lo ridà |
| il PIN è giusto ma "Troppi PIN sbagliati" | sei in una pausa: durante la pausa il PIN non si controlla | aspetta il tempo scritto sul pulsante |
| chiede il PIN dopo ogni aggiornamento | normale: le sessioni stanno nella memoria del backend | — |
| "Il PC non risponde" | backend o nginx spenti, o niente rete, e nessuna sessione nelle ultime 12 ore | `docker compose ps`; poi "Riprova" |
| PIN dimenticato | — | `installa.ps1 -ReimpostaPin` (parte G) |
| `npm start`: 401 su tutto | normale: anche in sviluppo serve il PIN | inserisci il PIN nella pagina |

---

## Esercizi guidati

1. **L'hash.** Con l'app avviata:
   `docker compose exec db psql -U wallettracker -d wallettracker -c "select pin_hash, failed_attempts from app_pin"`.
   Riconosci `{bcrypt}`, `$2a$` (la versione di bcrypt), `12` (il costo) e il resto (il *sale* e l'hash)? Perché due
   installazioni con lo stesso PIN hanno hash diversi? (Suggerimento: il *sale*.)
2. **La pausa vista dal database.** Sbaglia il PIN cinque volte e rilancia la query dell'esercizio 1 aggiungendo
   `locked_until`. Poi entra con il PIN giusto e guarda di nuovo.
3. **401 o 403?** In un terminale: `curl -i http://localhost:8000/api/accounts` (che cosa dice `detail`?), poi
   `curl -i -X POST http://localhost:8000/api/auth/login -H "Content-Type: application/json" -d "{\"pin\":\"123456\"}"`.
   Perché il secondo è 403 e non 401? (Parte D.)
4. **Il gettone.** Rifai il secondo comando come la CI (`.github/workflows/ci.yml`, "Controlla che l'app
   risponda"): con un file di cookie (`-c biscotti -b biscotti`), prima lo stato, poi il login con l'header
   `X-XSRF-TOKEN`. Quale risposta ottieni con un PIN sbagliato?
5. **Il cookie Secure.** Leggi `SessionCookieTest`: che cosa cambia tra i due test? Perché Tomcat crede a
   `X-Forwarded-Proto` solo dalla rete interna?
6. **Un'idea per dopo: cambiare il PIN.** Oggi il PIN si cambia solo azzerandolo. Come aggiungeresti "Cambia il
   PIN" in Impostazioni? Che cosa dovrebbe chiedere il backend, oltre al PIN nuovo? (Suggerimento: chi ha un
   telefono sbloccato con la sessione aperta non deve poterlo cambiare.)
