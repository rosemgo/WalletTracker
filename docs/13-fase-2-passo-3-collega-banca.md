# 13. Fase 2, passo 3: collegare le banche dalla dashboard e "Aggiorna ora"

**Obiettivo:**
- **collegare una banca dalla dashboard**: scegli la banca, fai il login sul suo sito, e al ritorno
  collegamento e movimenti sono già pronti. Niente più programma a riga di comando, né indirizzi da
  copiare e incollare;
- **"Aggiorna ora"**: un pulsante nella pagina Conti che aggiorna subito tutti i conti.

> Prerequisiti: passo 2 completato e `git pull` fatto.

---

## Parte A: i concetti

### 1. Il flusso del collegamento

È lo stesso flusso del programma della Fase 0. Cambia solo **chi** fa i vari passaggi:

```
 TU (browser)                  DASHBOARD                 BACKEND                    ENABLE BANKING / BANCA
 ─────────────                 ─────────                 ───────                    ──────────────────────
 1. scegli "ING", Collega ──►  POST /api/connections ──► genera uno "state"
                                                         casuale e lo ricorda ────► POST /auth
                               ◄── url del login ─────── ◄── url del login ──────── 
 2. il browser va sul sito della banca: login + SCA ──────────────────────────────► (sito della banca)
 3. la banca rimanda il browser a https://localhost:8443/callback?code=...&state=...
                               pagina /callback
                               POST /api/connections/complete {code, state}
                                                    ──►  controlla lo state
                                                         scambia il code ────────► POST /sessions
                                                         salva collegamento e conti
                                                         importa i movimenti ────► GET /accounts/.../transactions
                                                         riclassifica tutto
                               ◄── "ING collegata: 3 conti, 154 movimenti" ─
```

Nella Fase 0 il passo 3 non funzionava da solo: all'indirizzo di ritorno non rispondeva nessuno, e
dovevi copiare l'indirizzo dalla barra del browser. Ora all'indirizzo di ritorno risponde la dashboard.

### 2. Perché la dashboard passa a `https://localhost:8443`

L'indirizzo di ritorno (*redirect URL*) è registrato nella tua applicazione Enable Banking, e la banca
rimanda il browser **esattamente** lì: `https://localhost:8443/callback` (è anche il valore di
`ENABLE_BANKING_REDIRECT_URL` nel `.env`). Perché il ritorno arrivi alla dashboard, la dashboard deve
rispondere proprio su quell'indirizzo:

- **porta 8443** invece di 4200;
- **HTTPS** invece di HTTP: l'indirizzo registrato è `https://`, ed è giusto così, perché nell'indirizzo
  di ritorno viaggia il codice che autorizza la lettura dei tuoi conti.

Basta dirlo al server di sviluppo di Angular, in `angular.json`:

```json
"serve": {
  "options": {
    "proxyConfig": "proxy.conf.json",
    "ssl": true,
    "port": 8443
  }
}
```

Con `"ssl": true` Angular crea da solo un **certificato autofirmato**. Un certificato HTTPS serve a
dimostrare al browser che il sito è davvero chi dice di essere, e di solito lo firma un'autorità che il
browser conosce. Il nostro lo "firma" il nostro stesso PC, quindi il browser non può fidarsi e mostra un
avviso: "La connessione non è privata". Per `localhost`, cioè il tuo PC, è normale: premi **Avanzate →
Procedi su localhost**. La comunicazione è comunque cifrata.

### 3. Lo `state`: una protezione contro i collegamenti "infilati"

Al punto 1 il backend genera uno **`state`** casuale e se lo ricorda. La banca lo restituisce identico
al punto 3, e il backend accetta il ritorno solo se lo `state` è uno dei suoi:
- **mai visto** → rifiutato;
- **già usato** → rifiutato (vale una volta sola);
- **più vecchio di 30 minuti** → rifiutato.

Senza questo controllo, un sito malevolo potrebbe mandarti a
`https://localhost:8443/callback?code=<un codice suo>` e farti collegare **il suo** conto al tuo
WalletTracker. Questo tipo di attacco si chiama **CSRF** (*Cross-Site Request Forgery*), e lo `state` è
la protezione standard di OAuth 2, il protocollo su cui si basano questi login.

### 4. "Aggiorna ora" e gli header PSU

Ricordi i due tipi di lettura della PSD2?

| | Sincronizzazione automatica | "Aggiorna ora" / collegamento |
|---|---|---|
| Chi la avvia | il timer, ogni 6 ore | tu, con un clic |
| Header PSU ("l'utente è presente") | no | **sì** |
| Limite giornaliero | ~4 letture per conto | nessuno |

Gli header PSU contengono il tuo **indirizzo IP** e il tuo **browser** (*User-Agent*):
- il **browser** lo leggiamo dall'header `User-Agent`, che il browser manda a ogni richiesta;
- l'**IP** non possiamo leggerlo dalla richiesta: il backend la vede arrivare da `127.0.0.1` (lo stesso
  PC), che per la banca non significa nulla. Lo chiediamo quindi a **ipify**, come faceva il programma
  della Fase 0. Con WalletTracker installato a casa, è l'IP da cui navighi.

Se ipify non risponde, la lettura avviene lo stesso, ma **senza** header PSU: conta come una lettura in
background.

---

## Parte B: il codice

### Backend

| Classe | Cosa fa |
|---|---|
| `connection/BankLinkService` | il flusso: `banks()`, `start()` (link del login + `state`), `complete()` (controllo dello `state`, sessione, salvataggio, importazione, classificazione) |
| `connection/ConnectionController` | `GET /api/banks?country=IT`, `POST /api/connections`, `POST /api/connections/complete` |
| `sync/SyncController` | `POST /api/sync` ("Aggiorna ora") |
| `sync/SyncService.syncAllNow` | come la sincronizzazione automatica, ma subito, per tutti i conti, con gli header PSU |
| `web/PublicIpAddress` | l'IP pubblico da ipify, tenuto in memoria per 10 minuti |
| `web/PresentUser` | costruisce gli header PSU per la richiesta corrente |
| `web/ApiExceptionHandler` | trasforma gli errori di Enable Banking in risposte `502` con il motivo |

**Gli `state` in attesa** stanno in una `ConcurrentHashMap`: una mappa che più richieste HTTP possono
usare nello stesso momento senza fare danni, perché ogni richiesta del server web gira su un suo
thread. Stanno in memoria: se il backend si riavvia mentre sei sul sito della banca, basta ricominciare.

```java
Pending started = state != null ? pending.remove(state) : null;   // remove: uno state si usa UNA volta
if (started == null || isExpired(started)) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Questo collegamento non è valido o è scaduto...");
}
```

**Il refactoring di `SyncService`.** Sincronizzazione automatica e "Aggiorna ora" fanno quasi la stessa
cosa, quindi il ciclo è uno solo, `syncAll(psu, onlyDue)`, e i due metodi pubblici lo chiamano con
parametri diversi:

```java
public Round syncAllInBackground() { return syncAll(null, true); }   // senza PSU, solo i conti "scaduti"
public Round syncAllNow(PsuHeaders psu) { return syncAll(psu, false); } // con PSU, tutti i conti
```

**`@RestControllerAdvice`.** Se Enable Banking risponde con un errore (es. credenziali sbagliate), il
client lancia una `RestClientResponseException`. Senza gestirla, la dashboard riceverebbe un generico
`500`. `ApiExceptionHandler` la intercetta per **tutti** i controller e risponde `502 Bad Gateway`
("il servizio dietro di me ha risposto con un errore"), con il motivo in `detail`.

**`HttpServletRequest` come parametro.** Se un metodo del controller la chiede, Spring gli passa la
richiesta HTTP "grezza", da cui leggiamo l'header `User-Agent`.

### Frontend

| File | Cosa fa |
|---|---|
| `link/link-page.ts` (`/collega`) | paese, ricerca con un `computed`, pulsante "Collega"; poi manda il browser al login della banca |
| `link/callback-page.ts` (`/callback`) | legge `code` e `state` dall'indirizzo, chiama il backend, mostra il risultato |
| `api/browser-location.ts` | `go(url)`: manda il browser a un altro sito; nei test lo sostituiamo con un finto |
| `accounts/accounts-page.*` | pulsante "Aggiorna ora" e link "Collega una banca" |

**Leggere i parametri dell'indirizzo.** `ActivatedRoute` descrive la pagina corrente; `snapshot` ne è
una "fotografia" al momento dell'apertura:

```ts
const params = this.route.snapshot.queryParamMap;   // ?code=...&state=...
const code = params.get('code');
```

Subito dopo, la pagina **toglie** i parametri dalla barra degli indirizzi:

```ts
this.router.navigate([], { relativeTo: this.route, queryParams: {}, replaceUrl: true });
```

Così, se ricarichi la pagina, non si prova di nuovo a usare un codice già usato. `replaceUrl: true`
sostituisce la voce nella cronologia invece di aggiungerne una.

**Errori dal backend.** Grazie ai Problem Details (passo 1), ogni errore ha il motivo in `detail`. La
dashboard lo mostra così com'è:

```ts
error: (response) => this.error.set(response.error?.detail ?? 'Il collegamento non è riuscito.'),
```

`?.` e `??` sono due operatori di TypeScript:
- `a?.b` vale `undefined` se `a` è null, invece di dare errore;
- `a ?? b` vale `b` se `a` è null o undefined.

### I test

| Test | Cosa verifica |
|---|---|
| `BankLinkServiceTest` (6) | banche in ordine alfabetico; durata del consenso (90 giorni per ING, 180 senza limite); link del login e `redirect_url`; collegamento completo con importazione **con header PSU** e classificazione; `state` sconosciuto rifiutato senza chiamare la banca; `state` usabile una volta sola |
| `ConnectionControllerTest` (4) | elenco banche; `400` con il motivo per uno `state` sconosciuto; errore di Enable Banking → `502`; "Aggiorna ora" legge anche un conto appena aggiornato, con IP e browser negli header PSU |
| `LinkPage` (2) | la ricerca filtra le banche; "Collega" manda il browser al login |
| `CallbackPage` (2) | con `code` e `state` completa il collegamento; con `error` mostra il messaggio della banca senza chiamare il backend |
| `AccountsPage` (+1) | "Aggiorna ora" chiama l'API e mostra il riepilogo |

---

## Parte C: cosa fare

1. **Aggiorna e lancia i test:**
   ```powershell
   git pull
   cd backend
   .\mvnw.cmd test               # 63 test
   cd ..\frontend
   nvm use 24.21.0
   npm install
   npm test -- --watch=false     # 13 test
   ```
2. **Avvia backend e dashboard** come al passo 2 (`.\mvnw.cmd spring-boot:run` e `npm start`).
3. **Apri <https://localhost:8443>.** La prima volta il browser mostra "La connessione non è privata":
   premi **Avanzate → Procedi su localhost (non sicuro)**. È il certificato autofirmato del punto A.2.
4. **"Aggiorna ora":** nella pagina **Conti** premi il pulsante. Dopo qualche secondo vedrai
   "Aggiornati N conti" e l'ora di "Ultimo aggiornamento" cambiata. Se un conto dà errore, lo vedi in
   rosso accanto al conto.
5. **Collega (o ricollega) una banca dalla dashboard.** Un buon banco di prova è **Trade Republic**, che
   ha il consenso più breve (90 giorni):
   - **Collega una banca** → cerca "Trade" → **Collega**;
   - fai login e SCA sul sito di Trade Republic;
   - tornerai su `https://localhost:8443/callback` con il riepilogo: banca collegata, conti e movimenti
     nuovi. Se il conto era già collegato, viene **riconosciuto** (stessa chiave stabile) e passa al
     nuovo consenso: nessun doppione.
6. **Prova l'annullamento:** avvia un collegamento e sul sito della banca premi "Annulla". Tornerai sulla
   dashboard con il messaggio della banca.

> **E il programma della Fase 0?** Funziona ancora (`-Dspring-boot.run.profiles=poc`), ma non serve più
> per collegare le banche. Attenzione: se la dashboard è accesa mentre colleghi una banca dal programma,
> il ritorno dalla banca arriva alla dashboard, che usa lei il codice. In quel caso il collegamento
> risulterà fatto dalla dashboard.

---

## Cosa hai imparato

- Il flusso di autorizzazione completo (in stile **OAuth 2**), con **redirect URL** e parametro **`state`**
  contro gli attacchi **CSRF**.
- **HTTPS in sviluppo** con un certificato autofirmato, e perché il browser lo segnala.
- Quando usare gli **header PSU** e da dove prendere IP e browser dell'utente.
- `@RestControllerAdvice` per gestire gli errori di un servizio esterno (`502 Bad Gateway`).
- `ConcurrentHashMap` per dati condivisi tra richieste HTTP.
- In Angular: `ActivatedRoute` e i parametri dell'indirizzo, `router.navigate` con `replaceUrl`, un
  servizio "finto" per non lasciare davvero la pagina nei test.

## Prossimo passo

**Fase 2, passo 4:** le **regole** dalla dashboard (vederle, aggiungerle, modificarle) e la
**configurazione guidata** delle credenziali Enable Banking, così chi installa WalletTracker non deve
toccare il file `.env`.
