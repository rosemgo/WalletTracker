# 16. Fase 2, passo 5: la configurazione guidata di Enable Banking

**Obiettivo:** chi installa WalletTracker configura Enable Banking **dalla dashboard**, senza aprire il
file `.env`:
1. una pagina **Impostazioni** spiega come creare l'applicazione su Enable Banking e mostra l'indirizzo di
   ritorno da registrare, con un pulsante "copia";
2. l'utente sceglie il file `.pem`; l'**Application ID** si compila da solo dal nome del file;
3. **"Verifica e salva"**: il backend prova le credenziali con Enable Banking e le salva **solo se
   vengono accettate**.

Finché Enable Banking non è configurato, in cima a ogni pagina c'è un avviso con il link alle
Impostazioni. Con questo passo la **Fase 2 è completa**.

> Prerequisiti: passo 4 completato e `git pull` fatto.

---

## Parte A: i concetti

### 1. Due fonti per le credenziali

| Fonte | Come | Quando si usa |
|---|---|---|
| **dashboard** | pagina Impostazioni → salvate nel database (tabella `enable_banking_settings`) | se presenti, **vincono** |
| **file `.env`** | `ENABLE_BANKING_APP_ID` + `ENABLE_BANKING_PRIVATE_KEY_PATH`, come fino al passo 4 | se la dashboard non ne ha salvate |

Tu hai già il `.env` compilato: per te non cambia niente. La pagina Impostazioni mostrerà
"✓ Configurato dal file .env". Se un giorno salvi credenziali dalla dashboard, da quel momento si usano
quelle.

### 2. L'applicazione parte anche senza credenziali

Fino al passo 4, senza `ENABLE_BANKING_APP_ID` l'applicazione **non partiva**: `@NotBlank` su
`EnableBankingProperties` lo impediva, e il messaggio di errore era rivolto a un programmatore. Per una
persona che installa WalletTracker per la prima volta non andava bene: non avrebbe mai visto la dashboard.

Ora l'applicazione parte comunque. Le chiamate a Enable Banking, finché non ci sono credenziali,
rispondono `409 Conflict` con un messaggio chiaro: *"Enable Banking non è ancora configurato: apri la
pagina Impostazioni"*.

### 3. Verificare prima di salvare

Credenziali sbagliate salvate "alla cieca" darebbero errori più avanti, quando l'utente prova a collegare
una banca, e sarebbe difficile capire perché. Invece il backend le **prova subito**:

```
browser ── PUT /api/setup/enable-banking { applicationId, privateKeyPem } ──► backend
                                                   1. la chiave è leggibile? (PKCS#8, RSA)
                                                   2. firma un JWT con QUESTE credenziali
                                                   3. GET /aspsps con quel JWT ──► Enable Banking
                                                          accettato?  sì → salva      no → 400 con il motivo
```

È lo stesso meccanismo dello schema `docs/img/collegamento-banca.svg`. Enable Banking verifica la firma
del JWT con la chiave pubblica registrata per quell'Application ID: se Application ID e file `.pem` non
appartengono alla stessa applicazione, la firma non torna e la risposta è `401`.

### 4. La chiave privata nel database: cosa comporta

Salvando dalla dashboard, la chiave privata finisce nel database, **in chiaro**. È lo stesso livello di
protezione del file `.pem` sul disco: chi può leggere il file, o il database, può usarla. Quindi:
- il database è raggiungibile **solo da questo PC** (porta `127.0.0.1` in `docker-compose.yml`);
- **la chiave non esce mai dalle API**: `GET /api/setup` restituisce solo se c'è, da dove arriva e
  l'Application ID, che non è un segreto. Un test lo verifica;
- i **backup del database** (`pg_dump`) contengono la chiave: trattali come il file `.pem`.

---

## Parte B: il backend

### Le classi nuove e quelle cambiate

| Classe | Cosa fa |
|---|---|
| `V7__credenziali_enable_banking.sql` | la tabella `enable_banking_settings`, con **una sola riga** (`CHECK (id = 1)`) |
| `EnableBankingSettings` (+ repository) | l'entità della tabella |
| **`EnableBankingCredentials`** | decide quali credenziali usare (dashboard, poi `.env`), le tiene in memoria, le salva |
| `EnableBankingJwtFactory` | non legge più le credenziali da sé: le chiede a `EnableBankingCredentials`. Se non ci sono, lancia `EnableBankingNotConfiguredException` (409) |
| `EnableBankingClient` | il JWT viene aggiunto da un **interceptor**; nuovo metodo `checkCredentials(token)` |
| `EnableBankingProperties` | `applicationId` e `privateKeyPath` non sono più obbligatori |
| `setup/SetupService`, `SetupController` | `GET /api/setup` e `PUT /api/setup/enable-banking` |

### `EnableBankingCredentials`: una "cache" con due fonti

```java
public synchronized Optional<Credentials> current() {
    if (cached == null) {
        cached = load();          // 1. database (dashboard)  2. file .env  3. nessuna
    }
    return cached;
}

public synchronized void save(String applicationId, String privateKeyPem) {
    repository.save(new EnableBankingSettings(applicationId, privateKeyPem));
    cached = null;                // al prossimo current() si rileggono
}
```

Leggere e decodificare la chiave a ogni richiesta sarebbe inutile, quindi il risultato resta in memoria
(*cache*). Quando si salvano credenziali nuove, la cache viene svuotata. `synchronized` evita che due
thread (una richiesta HTTP e la sincronizzazione automatica) leggano e scrivano la cache insieme.

`Optional<Credentials>` è il modo di Java per dire "**forse** c'è un valore": `Optional.empty()` vuol
dire "nessuna credenziale". Chi lo usa deve gestire esplicitamente il caso vuoto:

```java
credentials.current().orElseThrow(EnableBankingNotConfiguredException::new);
```

### Il JWT con credenziali "di prova"

`createToken` ora esiste in due versioni:

```java
public String createToken()                                              // con le credenziali in uso
public static String createToken(String applicationId, PrivateKey key)   // con credenziali qualsiasi
```

La seconda è `static` e serve a `SetupService`: firma un JWT con le credenziali **appena caricate**, non
ancora salvate, per provarle.

### L'interceptor nel client

Prima il JWT veniva aggiunto con `defaultRequest`, che si esegue **mentre si prepara** ogni richiesta.
Per provare credenziali nuove, però, serve una richiesta con un JWT **diverso**, e senza credenziali
salvate `defaultRequest` avrebbe fallito ancora prima di partire. Ora c'è un **interceptor**: codice
eseguito su ogni richiesta **appena prima di inviarla**, che aggiunge il JWT solo se la richiesta non ne
ha già uno:

```java
.requestInterceptor((request, body, execution) -> {
    if (request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION) == null) {
        request.getHeaders().setBearerAuth(jwtFactory.createToken());
    }
    return execution.execute(request, body);
})
```

`checkCredentials(token)` imposta da sé l'header, quindi l'interceptor lo lascia stare.

### `SetupService.saveEnableBanking`: tre controlli, poi il salvataggio

1. `parsePrivateKey(pem)`: il testo è una chiave PKCS#8 RSA? Se no → `400` con il motivo (e il comando
   `openssl` per convertirla, se è nel formato vecchio);
2. `client.checkCredentials(createToken(id, key))`: Enable Banking accetta? Se risponde con un errore →
   `400` "Enable Banking ha rifiutato queste credenziali"; se non risponde → `502`;
3. `credentials.save(id, pem)`.

### I test

| Test | Cosa verifica |
|---|---|
| `SetupControllerTest` (4) | stato con le credenziali del `.env` (e la chiave che **non** compare nella risposta); credenziali valide verificate con un JWT **nuovo** (controlliamo il `kid`), salvate e usate da lì in poi; un file che non è una chiave → `400` senza chiamare Enable Banking; credenziali rifiutate da Enable Banking → `400`, niente salvato |
| `EnableBankingJwtFactoryTest` (+1) | senza credenziali, l'errore dice di aprire le Impostazioni |

---

## Parte C: il frontend

### Uno stato condiviso: `SetupStore`

Due componenti hanno bisogno di sapere se Enable Banking è configurato:
- la **barra in alto** (`App`), per mostrare l'avviso;
- la **pagina Impostazioni**, che lo configura.

Se ognuno chiedesse lo stato al backend per conto suo, dopo il salvataggio l'avviso in alto resterebbe lì
fino al prossimo caricamento della pagina. La soluzione è un **servizio con un signal**:

```ts
@Injectable({ providedIn: 'root' })
export class SetupStore {
  readonly status = signal<SetupStatus | null>(null);
  refresh(): void { ... }      // chiede lo stato al backend e aggiorna il signal
}
```

`providedIn: 'root'` vuol dire **una sola istanza** per tutta l'applicazione: `App` e `SettingsPage`
leggono **lo stesso** signal. Quando la pagina Impostazioni salva, fa
`this.setup.status.set(status)`, e l'avviso nella barra sparisce da solo, perché `app.html` lo legge:

```html
@if (setup.status()?.configured === false) {
  <div class="banner">Per iniziare, configura ... <a routerLink="/impostazioni">apri le Impostazioni</a>.</div>
}
```

Questo schema, un servizio che tiene uno stato condiviso in uno o più signals, si chiama spesso
**store**. In applicazioni grandi si usano librerie dedicate (es. NgRx); per noi basta un servizio.

### Leggere un file nel browser

```html
<input type="file" accept=".pem" (change)="onFile($event)" />
```

```ts
async onFile(event: Event): Promise<void> {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) {
    return;
  }
  this.fileName.set(file.name);
  this.pem.set(await file.text());
  ...
}
```

- `files?.[0]`: il primo file scelto (oppure `undefined` se l'utente ha annullato);
- `file.text()` legge il contenuto **nel browser**. Restituisce una **Promise**, l'equivalente "una tantum"
  di un Observable: un valore che arriverà. `await` aspetta quel valore senza bloccare la pagina, e si può
  usare solo dentro una funzione marcata **`async`**;
- il contenuto parte verso il backend solo quando premi "Verifica e salva".

**L'Application ID dal nome del file.** Enable Banking chiama il file con l'Application ID (es.
`cf589be3-3755-465b-a8df-a90a16a31403.pem`). Se il campo è vuoto e il nome ha la forma di un UUID
(controllata con un'**espressione regolare**), lo compiliamo noi:

```ts
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
```

### I test

| Test | Cosa verifica |
|---|---|
| `SettingsPage` (4) | mostra l'indirizzo di ritorno; compila l'Application ID dal nome del file e salva la chiave (e aggiorna lo stato condiviso); mostra il motivo del rifiuto; chiede il file prima di salvare |
| `App` (2) | i link della barra (con "Impostazioni"); l'avviso quando Enable Banking non è configurato |

Per simulare la scelta di un file, il test sostituisce la proprietà `files` dell'input e lancia l'evento
`change`:

```ts
Object.defineProperty(input, 'files', { value: [new File([content], name)] });
input.dispatchEvent(new Event('change'));
```

---

## Parte D: cosa fare

1. **Aggiorna e lancia i test:**
   ```powershell
   git pull
   cd backend
   .\mvnw.cmd test               # 74 test
   cd ..\frontend
   npm test -- --watch=false     # 23 test
   ```
2. **Avvia** backend e dashboard e apri **Impostazioni** (<https://localhost:8443/impostazioni>). Vedrai
   "✓ Configurato dal file .env" con il tuo Application ID.
3. **Facoltativo: prova la configurazione dalla dashboard.** Scegli il tuo file
   `secrets/enable-banking.pem`. Il nome del file non è l'Application ID, quindi scrivilo tu (lo trovi nel
   `.env`), poi premi **Verifica e salva**. La pagina dirà "Configurato da questa pagina".
4. **Prova un errore:** scegli un file qualsiasi che non sia una chiave (un `.txt` rinominato in `.pem`)
   e premi **Verifica e salva**: vedrai il motivo del rifiuto, e niente verrà salvato.
5. **Facoltativo: simula una prima installazione.** Commenta le righe `ENABLE_BANKING_APP_ID` e
   `ENABLE_BANKING_PRIVATE_KEY_PATH` nel `.env` (un `#` davanti) e ricrea il database vuoto in un progetto
   di prova. L'applicazione parte e mostra l'avviso in alto. Se lo fai sul tuo database vero, le credenziali
   salvate dalla dashboard al punto 3 restano valide.

> **Il programma della Fase 0** (`-Dspring-boot.run.profiles=poc`) usa le stesse credenziali, dashboard o
> `.env`.

## Cosa hai imparato

- Come rendere un'applicazione **avviabile senza configurazione**, con un percorso guidato.
- **Verificare** le credenziali prima di salvarle, firmando un JWT "di prova".
- `Optional`, una **cache** con `synchronized`, un **interceptor** di `RestClient`.
- Cosa comporta tenere una **chiave privata** nel database, e perché non deve mai uscire dalle API.
- In Angular: uno **store** condiviso con un signal, la lettura di un **file** nel browser, **Promise** e
  `async`/`await`, le espressioni regolari.

## Prossimo passo

Con questo la **Fase 2 è completa**. La **Fase 3** porta i grafici, i budget, le categorie come elenco
(con i rimborsi che riducono la spesa), l'avviso di scadenza del consenso e l'import del file CSV di Trade
Republic.
