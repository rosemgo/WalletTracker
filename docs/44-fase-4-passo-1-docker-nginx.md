# 44. Fase 4, passo 1: l'app in tre container (Dockerfile multi-stage, nginx, Docker Compose)

**Obiettivo della Fase 4:** da "tre programmi avviati a mano da un programmatore" a **un comando**, e l'app si
usa anche dal telefono, in sicurezza.

**Obiettivo di questo passo:** con `docker compose up -d --build` partono il database, il backend e un server web
(nginx) con la dashboard; l'app si apre su `http://localhost:8000`, con tutte le pagine e la PWA. L'IA locale
(Ollama) è un quarto container facoltativo.

| Passo | Che cosa | Stato |
|---|---|---|
| **1** | **tre container + Ollama facoltativo, nginx** | **questa guida** |
| 2 | configurazione e segreti: password del database generata, primo avvio guidato | da fare |
| 3 | il PIN per aprire l'app (Spring Security) | da fare |
| 4 | Tailscale: l'app dal telefono con https vero, redirect di Enable Banking | da fare |
| 5 | backup e ripristino, provato davvero | da fare |
| 6 | aggiornamenti, la guida per chi non è informatico | da fare |

> Prerequisiti: Docker Desktop avviato e `git pull`. **Prima di provare, ferma il backend avviato a mano
> (`./mvnw spring-boot:run`) e `npm start`** (parte E: perché).

**Indice**
- **Prima di tutto: tre domande** (i tre container, perché il database non ha più la porta, a che cosa serve nginx)
- Parte A: il quadro (perché un server web tra browser e backend; Tailscale, Cloudflare e che cosa leggerebbe, il PIN)
- Parte B: Docker in cinque parole (immagine, container, volume, rete, Compose)
- Parte B1: come si agganciano `docker-compose.yml` e i Dockerfile (Docker Hub, `image:` e `build:`, che cosa succede con `up --build`)
- Parte B2: il Dockerfile, istruzione per istruzione (che cos'è, perché è importante, i due Dockerfile riga per riga)
- Parte C: il backend in un container
- Parte D: nginx, riga per riga
- Parte E: `docker-compose.yml`
- Parte F: la sicurezza di questo passo
- Parte G: Ollama, facoltativo
- Parte H: prova
- Parte I: test e verifiche fatte
- Parte J: problemi comuni
- Esercizi guidati

---

## Prima di tutto: tre domande

Se leggi solo questa sezione, hai capito il passo 1. Il resto della guida entra nei dettagli.

### 1. Quali sono i container, e che cosa fa ognuno?

Un **container** è un programma che gira in una "scatola" isolata, con dentro tutto quello che gli serve. Tutti i
container dell'app sono descritti in **un solo file**, `docker-compose.yml`, e partono insieme con
`docker compose up -d`.

| Container | Che cosa c'è dentro | Che cosa fa | Porta aperta sul PC | Dove tiene i dati |
|---|---|---|---|---|
| `db` | PostgreSQL 18 | conserva conti, movimenti, regole, budget | **nessuna** | il volume `wallettracker_db-data` |
| `backend` | Java 21 (solo per eseguire) e il jar di Spring Boot | la "logica": le API `/api/...`, la sincronizzazione con le banche ogni 6 ore, l'IA | **nessuna** | non ne tiene: usa il database, più la chiave di cifratura in `.data/` |
| `web` | nginx e la build della dashboard Angular | è la **porta d'ingresso**: manda la dashboard al browser e passa le richieste `/api/` al backend | **`127.0.0.1:8000`, l'unica** | nessuno |
| `ollama` *(facoltativo)* | Ollama | risponde alle domande sulle categorie | nessuna | il volume `ollama-data` |

I container si parlano attraverso una **rete privata** che Docker crea per il progetto. Su quella rete ognuno si
chiama con il **nome del servizio**: `db`, `backend`, `web`, `ollama`. È come un condominio: gli appartamenti si
raggiungono dai corridoi interni, e verso la strada c'è un solo portone.

```
         Il tuo PC                    │                 Docker (rete privata "wallettracker")
                                      │
   browser ── http://localhost:8000 ──┼──▶ [ web: nginx ] ──/api/──▶ [ backend ] ──▶ [ db ]
                                      │     (unico portone)               │
                                      │                                   └──────▶ [ ollama ]
   (nessun'altra porta)               │
```

### 2. Perché il database non ha più bisogno della porta aperta?

Una **porta pubblicata** (`ports:` nel `docker-compose.yml`) è una porta tra il **tuo PC** e un container. Serve
solo a chi sta **fuori** da Docker e vuole entrare.

- **Prima**, il backend girava **sul PC** (`./mvnw spring-boot:run`), cioè fuori da Docker. Per arrivare al
  database, che era dentro Docker, gli serviva quella porta: `localhost:5432`.
- **Adesso** il backend è anche lui un container, nella **stessa rete privata** del database. Lo raggiunge
  dall'interno, con il nome `db:5432` (è `POSTGRES_HOST: db` nel `docker-compose.yml`). La porta verso il PC non
  la usa più nessuno.

E **una porta che non serve è meglio chiuderla**, perché ogni porta aperta è un posto in più da cui tentare di
entrare. Con la porta del database aperta, qualsiasi programma sul PC (anche uno malevolo) potrebbe provare a
collegarsi e a indovinare la password. Senza porta, al database arriva **solo** il backend.

Quando invece sviluppi (backend con `./mvnw`, oppure DBeaver o `psql` per guardare le tabelle) sei di nuovo
"fuori" da Docker, e la porta serve. Per questo c'è `docker-compose.dev.yml`, che la riapre, sempre e solo su
`127.0.0.1`:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d db
```

Vale lo stesso per il backend: anche lui non ha porte aperte, perché da fuori ci si arriva solo passando da nginx.

### 3. nginx: a che cosa serve, perché sta nella cartella del frontend, perché è fondamentale

**A che cosa serve.** nginx è un **server web**: un programma che risponde alle richieste del browser. Qui fa
quattro lavori:

1. **Manda la dashboard al browser.** Dopo `ng build`, il frontend Angular è solo una cartella di file
   (`index.html`, `.js`, `.css`, icone). Non è un programma che gira da solo: qualcuno deve consegnarli al
   browser quando li chiede. Durante lo sviluppo lo faceva `ng serve`; ora lo fa nginx, che è fatto per restare
   acceso giorno e notte.
2. **Passa le richieste `/api/...` al backend** (si chiama *reverse proxy*) e restituisce la risposta al
   browser. Durante lo sviluppo lo faceva `proxy.conf.json`.
3. **Fa da unico portone.** È l'unico container con una porta aperta. Backend e database restano chiusi dentro.
4. **Aggiunge le regole di cache** (perché la PWA veda le versioni nuove) e le **intestazioni di sicurezza**.
   Dal passo 4 sarà anche il punto dove arriva l'https di Tailscale.

**Perché sta nella cartella del frontend.** Il container `web` è "nginx **più** la build della dashboard": il suo
contenuto è il frontend. Per questo il suo Dockerfile è `frontend/Dockerfile`: prima compila Angular, poi mette
il risultato dentro nginx. Anche la configurazione (`frontend/nginx/default.conf`) parla soprattutto di come
servire i file del frontend. Il codice Angular invece **non cambia**: nginx non fa parte dell'app, è il modo di
consegnarla.

**Perché è fondamentale per entrambi.** Il punto da capire è che **il frontend e il backend non si parlano tra
loro "da server a server": a parlare con il backend è il browser.** Il codice Angular gira *dentro il browser*, e
da lì chiama `/api/...`. Quindi:

- **Per il frontend**: senza nginx, nessuno manderebbe al browser i file della dashboard (`ng serve` è solo per
  lo sviluppo, e non ha il service worker).
- **Per il backend**: senza nginx, il browser dovrebbe raggiungere il backend direttamente, e bisognerebbe
  aprirgli una porta. In più, la dashboard (`http://localhost:8000`) e il backend (es. `http://localhost:8080`)
  sarebbero due **origini** diverse: il browser bloccherebbe le chiamate, a meno di configurare il **CORS** nel
  backend. Con nginx davanti, tutto ha **un solo indirizzo**: `/api/...` e la dashboard arrivano da
  `localhost:8000`, e il problema non esiste.

**Un esempio passo per passo.** Apri `http://localhost:8000/movimenti/12`:

1. il browser chiede `/movimenti/12` a nginx. Non è un file: è una pagina dell'app. nginx manda `index.html`;
2. il browser legge `index.html` e chiede `main-….js` e `styles-….css`. nginx li manda dalla cartella della
   build, con "tienili un anno";
3. parte Angular, nel browser. Il router vede `/movimenti/12` e chiede i dati: `GET /api/transactions/12`
   a `localhost:8000`;
4. nginx vede che l'indirizzo comincia con `/api/` e lo passa a `http://backend:8080`, nella rete privata;
5. il backend legge dal database (`db:5432`), risponde con il JSON, e nginx lo riporta al browser;
6. Angular disegna la pagina.

Il browser ha parlato **sempre e solo con nginx**. Del backend e del database non sa nemmeno l'indirizzo.

---

## Parte A: il quadro

### A1. Perché serve un server web tra browser e backend

Avevi chiesto: *"serve un server per far comunicare backend e frontend? non capisco"*. Ecco il ragionamento.

- **Il frontend Angular, dopo `ng build`, è solo una cartella di file** (`frontend/dist/frontend/browser`:
  `index.html`, file `.js` e `.css`, icone, caratteri). Non è un programma che gira: qualcuno deve **mandare**
  quei file al browser quando li chiede. È il mestiere di un *server web*.
- **Oggi lo fa `ng serve`**, che però è un server da sviluppo: ricompila a ogni modifica, è lento, non è fatto per
  restare acceso giorno e notte, e non ha il service worker.
- **Il backend Spring Boot non manda quei file**: risponde solo a `/api/...`. Potrebbe farlo (copiando la build
  nel jar), ma mescolerebbe due cose diverse e complicherebbe gli aggiornamenti.
- **Chi parla con il backend è il browser, non il frontend "in quanto server".** Il codice Angular gira *nel
  browser*, e da lì chiama `/api/...`.
- **Perché `/api` deve passare dallo stesso indirizzo.** Per sicurezza il browser tratta
  `http://localhost:8000` e `http://localhost:8080` come due **origini** diverse, e blocca le chiamate tra origini
  diverse se il server non le autorizza (CORS). Con un solo indirizzo davanti a tutto, il problema non esiste.
  È già così oggi: `proxy.conf.json` fa fare a `ng serve` da tramite verso il backend, e
  `scripts/serve-dist.mjs` (`npm run pwa`) fa lo stesso per la build di produzione.

In produzione questo lavoro lo fa **nginx** (si pronuncia "engine-ex"), uno dei server web più usati al mondo:

```
                 PC (o telefono, dal passo 4)
                          │  http://localhost:8000
                          ▼
   ┌─────────────── Docker ─────────────────────────────────┐
   │  web (nginx) ──── /api/... ────▶ backend ────▶ db        │
   │     │                              └───────▶ ollama      │
   │     └── tutto il resto: i file della build (o index.html) │
   └──────────────────────────────────────────────────────────┘
```

nginx è l'**unico** con una porta aperta, e solo su questo PC. Backend, database e Ollama stanno nella rete
interna di Docker.

### A2. Perché Tailscale (passo 4)

Il problema: il telefono deve raggiungere il PC di casa anche fuori casa, **senza che lo raggiunga nessun altro**.

- **L'alternativa classica è aprire una porta sul router.** Vuol dire mettere l'app **su Internet**: programmi
  automatici scansionano di continuo tutti gli indirizzi del mondo, e la nostra app non ha (ancora) un login.
- **Tailscale crea una rete privata solo tra i tuoi dispositivi** (una *tailnet*):
  - **niente porte aperte sul router**: sono i dispositivi a collegarsi verso l'esterno, come un browser. Da
    fuori non c'è niente a cui bussare;
  - **cifratura da un capo all'altro** con WireGuard. I server di Tailscale si scambiano solo le chiavi: il
    traffico non lo vedono, e quando possono telefono e PC parlano direttamente;
  - **un nome con https valido**, come `pc-casa.<rete>.ts.net`, senza comprare un dominio. È quello che serve
    alla PWA per installarsi sul telefono (`docs/42`, parte F);
  - gratis per uso personale.
- Da sapere: i nomi `*.ts.net` con certificato finiscono nei registri pubblici dei certificati (*Certificate
  Transparency*). Il nome del PC diventa visibile, ma non raggiungibile.

**E come fa Enable Banking a "chiamare" l'app, se non ci sono porte aperte?** Non la chiama mai. Tutte le
connessioni partono da te:
1. il backend chiama Enable Banking (`POST /auth`, poi `POST /sessions`, poi le letture dei movimenti): sono
   connessioni **in uscita**, e la risposta torna sulla stessa connessione;
2. il "ritorno" dalla banca (`/callback?code=…&state=…`) è un ordine **al browser**: "vai a questo indirizzo".
   Lo apre il browser, che sta sul tuo PC o telefono, **dentro** la tua rete. La prova che funziona così: oggi il
   redirect è `https://localhost:8443/callback`, che da Internet non è raggiungibile in nessun modo.

Una porta aperta servirebbe solo se Enable Banking dovesse avvisarci di sua iniziativa (un *webhook*). Noi non
li usiamo: è il backend che va a chiedere, ogni 6 ore (*polling*).

### A3. Perché non Cloudflare

Cloudflare Tunnel pubblica un servizio di casa senza aprire porte, ma lo pubblica **su Internet**:

1. **l'app diventa pubblica**: chiunque conosca o indovini l'indirizzo arriva fino alla porta, e tutta la
   sicurezza ricadrebbe sul nostro login. Con Tailscale la richiesta non arriva nemmeno;
2. **Cloudflare legge tutto in chiaro**: il collegamento https finisce sui loro server, che lo decifrano e lo
   ricifrano verso casa tua. Saldi, movimenti, IBAN e credenziali passerebbero in chiaro da un'azienda terza. Con
   Tailscale la cifratura va dal telefono al PC;
3. **serve un dominio** (a pagamento) e una configurazione accurata; Cloudflare Access aggiunge un login davanti,
   ma con più pezzi e più dipendenza da un servizio esterno.

In breve: Cloudflare è fatto per **pubblicare** siti; Tailscale per **non pubblicarli** e raggiungerli lo stesso.

#### Che cosa leggerebbe Cloudflare, se il collegamento con Enable Banking è già cifrato?

I collegamenti sono **due**, e Cloudflare si metterebbe sul secondo:

```
                       Collegamento 1                       Collegamento 2
 Enable Banking ◀═════ TLS diretto ═════▶ backend ──▶ nginx ◀═══ ? ═══▶ browser del telefono
                  (Cloudflare non c'è)          (il tuo PC)
```

- **Collegamento 1, backend ↔ Enable Banking.** Ogni 6 ore il backend chiama `https://api.enablebanking.com`
  con un **TLS diretto**. Cloudflare qui non passa, in nessun caso.
- **Collegamento 2, telefono ↔ il tuo PC.** È qui che si sceglie tra Cloudflare e Tailscale. Su questo
  collegamento non viaggia la risposta di Enable Banking, ma **le pagine della tua app**: quando apri "Movimenti",
  il browser chiede `/api/transactions` e il backend risponde con un JSON nuovo, preso **dal tuo database**.

**Il JWT autorizza, HTTPS cifra.** Sono due protezioni diverse:

| | Che cosa fa | Paragone |
|---|---|---|
| **HTTPS (TLS)** | **cifra** tutta la conversazione: la richiesta, il JWT, la risposta con i movimenti | il furgone blindato |
| **JWT (token Bearer)** | dice **chi sei**: è firmato con la chiave privata (`.pem`), quindi non si può falsificare | il documento allo sportello |

Il JWT **non cifra niente**: il suo contenuto è solo codificato in Base64, e chiunque lo veda lo può leggere
(provalo incollandone uno su jwt.io). Anche i movimenti arrivano come JSON normale: è HTTPS a proteggerli
durante il viaggio.

**Perché il TLS del collegamento 1 non protegge il collegamento 2.** Un tunnel TLS finisce dove finisce. Quello
con Enable Banking termina **nel backend**, che lo decifra, perché deve leggere i movimenti per salvarli e
classificarli. Da lì sono dati normali nel tuo database. Quando il telefono li chiede, partono in un viaggio
**nuovo**, con una protezione **nuova**. È come un pacco che arriva a casa tua blindato: lo apri, e poi lo
rispedisci a te stesso in ufficio con un altro corriere.

**Che cosa vedrebbe Cloudflare, sul collegamento 2:**

```
telefono ══TLS══▶ [ server di Cloudflare ] ══TLS══▶ tunnel ══▶ nginx ──▶ backend
                    qui il TLS si apre:
                    dati in chiaro
```

Con Cloudflare il telefono apre l'https **con Cloudflare**, non con il tuo PC: il certificato del tuo indirizzo
ce l'ha Cloudflare. Così Cloudflare decifra, guarda dove va la richiesta e la rimanda a casa tua in un secondo
tunnel. In quella tappa vedrebbe in chiaro:
- i movimenti che apri, con importi, causali e IBAN delle controparti;
- i saldi e il riepilogo;
- il PIN che digiti al login, quando ci sarà (passo 3);
- se le inserisci dal telefono, le credenziali di Enable Banking e la chiave di Gemini.

Con **Tailscale**, invece, cifra il telefono e decifra solo il tuo PC: in mezzo non c'è nessuna tappa che apra il
pacco.

```
telefono ══════════ cifrato dal telefono fino al tuo PC ══════════▶ nginx ──▶ backend
```

| Tratto | Cifrato | Chi lo può aprire |
|---|---|---|
| backend ↔ Enable Banking | sì, sempre | solo il backend ed Enable Banking |
| Enable Banking ↔ la tua banca | sì | Enable Banking e la banca |
| telefono ↔ PC con Cloudflare | sì, ma in due pezzi | anche Cloudflare, a metà strada |
| telefono ↔ PC con Tailscale | sì, in un pezzo solo | solo il telefono e il PC |
| browser del PC ↔ nginx, oggi (`http://localhost:8000`) | no | nessuno: la richiesta non esce dal PC |

Enable Banking stessa vede i movimenti in chiaro: è inevitabile, è il fornitore autorizzato PSD2 che li legge
dalla banca per te. È un soggetto regolamentato, scelto apposta. Con Cloudflare si aggiungerebbe un terzo che non
ha nessun motivo per vederli.

Il problema di Cloudflare, quindi, non riguarda come scarichi i movimenti dalla banca, ma come li guardi dal
telefono.

### A4. Il PIN (passo 3)

Tailscale decide quali **dispositivi** entrano, non quali **persone**. Un telefono perso e sbloccato, o un PC
usato anche da altri, aprirebbero l'app senza chiedere niente. Per questo, **prima** di accendere Tailscale,
mettiamo un PIN (Spring Security): salvato con un hash lento, sessione in un cookie `HttpOnly` e `Secure`, e un
blocco dopo troppi tentativi sbagliati (un PIN di 6 cifre ha "solo" un milione di combinazioni).

### A5. Una domanda: serve sempre Docker Desktop?

Per app self-hosted come questa (Firefly III, Actual Budget, Immich, Paperless...) **Docker è la strada più
comune**: un solo modo di installare, uguale su Windows, Mac e Linux. Per chi non è informatico ci sono però
modi più comodi del terminale:

| Modo | Come | Per chi |
|---|---|---|
| **Docker Desktop + uno script** | un file `avvia.bat` (Windows) / `avvia.command` (Mac) da aprire con un doppio clic, che lancia `docker compose up -d` e apre il browser | chi ha un PC sempre acceso: lo faremo al passo 6 |
| **NAS o "server di casa" con un negozio di app** | Synology, Unraid, CasaOS, Umbrel, TrueNAS: si installa da un catalogo con un clic. Sotto c'è sempre Docker | chi ha già un NAS |
| **Un installatore classico** | un `.exe`/`.dmg` con dentro Java, il database e la dashboard, senza Docker (es. con `jpackage`) | il più comodo per l'utente, ma molto più lavoro per chi lo prepara e lo aggiorna su tre sistemi |
| **Un servizio nel cloud** | qualcuno lo ospita per te | contrario allo spirito dell'app: i dati bancari andrebbero sul server di un altro |

Il nostro piano: Docker Desktop, più la guida passo passo e lo script con il doppio clic (passo 6).

---

## Parte B: Docker in cinque parole

| Parola | Che cos'è | Un paragone con Java |
|---|---|---|
| **immagine** | un "pacchetto" in sola lettura con un sistema minimo, il programma e le sue librerie | la classe |
| **container** | un'immagine in esecuzione, isolata dalle altre: ha i suoi processi, i suoi file, la sua rete | l'oggetto (`new`) |
| **Dockerfile** | la ricetta per costruire un'immagine, un'istruzione per riga (`FROM`, `COPY`, `RUN`...) | il `pom.xml` più lo script di build |
| **volume** | una cartella che vive **fuori** dal container: se il container si cancella, i dati restano | il database rispetto agli oggetti in memoria |
| **rete** | Compose crea una rete privata per il progetto; lì dentro ogni servizio si chiama per **nome** (`db`, `backend`) | — |

**Docker Compose** descrive tutti i servizi in un file (`docker-compose.yml`) e li avvia insieme, nell'ordine
giusto. Ogni servizio ha `image:` (un'immagine già pronta, es. `postgres:18-alpine`) oppure `build:` (la cartella
con il Dockerfile, es. `./backend`).

---

## Parte B1: come si agganciano `docker-compose.yml` e i Dockerfile

### Le immagini esistono già: Docker Hub

Un'immagine non va per forza costruita da te. Esiste un "negozio" pubblico di immagini pronte, **Docker Hub**
(hub.docker.com), dove chi sviluppa un programma pubblica la sua: il team di PostgreSQL pubblica `postgres`,
quello di Node pubblica `node`, l'azienda di nginx pubblica `nginxinc/nginx-unprivileged`. È l'equivalente di
**Maven Central** per i jar: nel `pom.xml` scrivi il nome e la versione di una libreria, e Maven la scarica.

Il nome di un'immagine si legge così:

```
nginxinc / nginx-unprivileged : 1.29-alpine
   │              │                 │
   chi la         il nome           il "tag": la versione (nginx 1.29, su Linux Alpine)
   pubblica
```

Quando il nome non ha la prima parte (`postgres:18-alpine`, `node:24-alpine`) è un'immagine **ufficiale**,
controllata da Docker stessa.

La prima volta che Docker incontra un nome che non ha sul PC, lo **scarica** da Docker Hub (`docker pull`). Dopo,
lo tiene in memoria e lo riusa. Le vedi con `docker images`.

### Due modi di dire a Compose da dove prendere un'immagine

Nel `docker-compose.yml` ogni servizio dice da dove arriva la sua immagine, in uno di due modi:

| Servizio | Riga | Che cosa vuol dire |
|---|---|---|
| `db` | `image: postgres:18-alpine` | "**scaricala pronta** da Docker Hub". Nessun Dockerfile nostro |
| `ollama` | `image: ollama/ollama:0.40.11` | come sopra |
| `backend` | `build: ./backend` | "**costruiscila tu**, con il Dockerfile che trovi in `backend/`" |
| `web` | `build: ./frontend` | come sopra, con `frontend/Dockerfile` |

**L'aggancio è la riga `build:`.** Compose va nella cartella indicata e cerca un file che si chiama esattamente
`Dockerfile`: è il nome predefinito, per questo non lo scriviamo. Con un nome diverso bisognerebbe dirlo:

```yaml
build:
  context: ./backend           # la cartella
  dockerfile: Dockerfile.prod  # il file, se non si chiama "Dockerfile"
```

La cartella (il **contesto**) è anche l'unica cosa che la build può vedere: `COPY src ./src` nel Dockerfile vuol
dire `backend/src` del progetto. Fuori da `backend/` il Dockerfile non può prendere niente.

L'immagine costruita prende il nome `<progetto>-<servizio>`: `wallettracker-backend` e `wallettracker-web`.

### Chi decide che cosa

| | Dockerfile | `docker-compose.yml` |
|---|---|---|
| Domanda | **che cosa c'è dentro** la scatola | **come si usa** la scatola |
| Quando conta | quando si **costruisce** l'immagine | quando si **avvia** il container |
| Esempi | Java, il jar, l'utente `wallettracker`, nginx con la dashboard | le variabili (`environment`), le porte (`ports`), le cartelle condivise (`volumes`), l'ordine di avvio (`depends_on`), il riavvio automatico, la sola lettura |

Paragone: il Dockerfile è la ricetta che produce la torta; il Compose è la disposizione del buffet, dove mettere
ogni torta e con quali piatti. La stessa immagine potrebbe girare con un altro Compose e altre variabili.

### Che cosa succede con `docker compose up -d --build`, passo per passo

1. **Compose legge `docker-compose.yml`** e il `.env`, e sostituisce i `${...}` con i valori.
2. **`db` e `ollama` hanno `image:`**: se sul PC non ci sono, Docker le scarica da Docker Hub.
3. **`backend` ha `build: ./backend`**: Compose manda la cartella `backend/` a Docker (meno ciò che è in
   `.dockerignore`) e Docker esegue `backend/Dockerfile`:
   1. `FROM maven:3.10-eclipse-temurin-21-alpine`: scarica da Docker Hub un'immagine con Maven e Java 21;
   2. ci copia `pom.xml` e `src`, ed esegue `mvn package`: ottiene il jar;
   3. `FROM eclipse-temurin:21-jre-alpine`: scarica un'altra immagine pronta, con il solo Java per eseguire;
   4. aggiunge l'utente, le cartelle e il jar preso dallo stadio 1;
   5. il risultato è l'immagine `wallettracker-backend`. Lo stadio 1 non entra nell'immagine finale.
4. **`web` ha `build: ./frontend`**: stessa cosa con `frontend/Dockerfile`. Scarica `node:24-alpine`, compila
   Angular; poi scarica `nginxinc/nginx-unprivileged:1.29-alpine` (nginx **già installato e funzionante**), ci
   aggiunge i file della build e `default.conf`. Il risultato è `wallettracker-web`.
5. **Compose crea la rete privata** del progetto.
6. **Compose avvia i container nell'ordine** dei `depends_on`: prima `db`, aspetta che sia `healthy`, poi
   `backend`, poi `web`. A ognuno dà le sue variabili, le sue cartelle e, solo a `web`, la porta.

Senza `--build`, se le immagini esistono già, Compose salta i passi 3 e 4 e le riusa così come sono: per questo,
dopo una modifica al codice, serve `--build`.

### "Partire da" un'immagine: i layer

`FROM nginxinc/nginx-unprivileged:1.29-alpine` vuol dire: "prendi quell'immagine così com'è e aggiungici sopra le
mie righe". Un'immagine è fatta a **strati** (*layer*), come una pila di fogli trasparenti:

```
 wallettracker-web
 ┌──────────────────────────────────────────┐
 │ i file della build di Angular            │  ← aggiunti dalle nostre righe COPY
 │ default.conf, security-headers.conf      │
 ├──────────────────────────────────────────┤
 │ nginx installato e configurato           │  ← dall'immagine nginxinc/nginx-unprivileged
 │ Linux Alpine                             │     (scaricata da Docker Hub, non la tocchiamo)
 └──────────────────────────────────────────┘
```

Per questo non abbiamo dovuto installare nginx: il lavoro l'ha già fatto chi pubblica l'immagine. Noi aggiungiamo
solo i nostri strati. Quando esce una versione nuova di nginx (es. per una correzione di sicurezza), basta
cambiare il tag nel `FROM` e ricostruire.

### Vederlo con i tuoi occhi

```bash
docker images                                 # le immagini sul PC: quelle scaricate e le nostre due
docker compose config                         # il docker-compose.yml con i ${...} già sostituiti
docker history wallettracker-web              # gli strati dell'immagine web: in alto i nostri, sotto quelli di nginx
docker compose build web                      # costruisce solo l'immagine web, senza avviare niente
```

---

## Parte B2: il Dockerfile, istruzione per istruzione

### Che cos'è e perché è importante

Un **Dockerfile** è la **ricetta** per costruire un'immagine: un file di testo, un'istruzione per riga, che Docker
esegue dall'alto in basso. Il risultato è un'immagine con dentro un sistema minimo, il programma e tutto ciò che
gli serve.

È importante perché rende l'installazione **ripetibile**. Senza Dockerfile, per far girare WalletTracker
bisognerebbe installare a mano sul PC la versione giusta di Java, di Maven e di Node, compilare, e sperare che
sul PC di un altro utente sia tutto uguale. Con il Dockerfile la ricetta è scritta una volta, sta su Git insieme
al codice, e chiunque ottiene **la stessa identica immagine** con un comando. Sul PC di chi installa basta
Docker: niente Java, niente Node, niente Maven.

Ne abbiamo due:
- `backend/Dockerfile`: l'immagine del backend;
- `frontend/Dockerfile`: l'immagine `web`, nginx con la dashboard.

Non si usano a mano: `docker compose up -d --build` li trova grazie a `build: ./backend` e `build: ./frontend`
nel `docker-compose.yml`.

### Le istruzioni

| Istruzione | Che cosa fa | Quando succede |
|---|---|---|
| `FROM immagine AS nome` | parte da un'immagine già pronta (la "base"). `AS nome` dà un nome allo stadio, per richiamarlo dopo | build |
| `WORKDIR /cartella` | la cartella di lavoro: le istruzioni successive (e il programma, quando gira) partono da lì. Se non esiste, la crea | build |
| `COPY origine destinazione` | copia file **dal tuo progetto** dentro l'immagine | build |
| `COPY --from=stadio ...` | copia file **da uno stadio precedente**, invece che dal progetto | build |
| `RUN comando` | esegue un comando **mentre si costruisce** l'immagine (compilare, creare utenti), e ne tiene il risultato | build |
| `USER nome` | da qui in poi, e quando il container gira, si è questo utente e non root | build e avvio |
| `EXPOSE porta` | un promemoria: "il programma ascolta su questa porta". **Non apre niente**: lo fa solo `ports:` nel Compose | — |
| `ENV NOME=valore` | una variabile d'ambiente sempre presente | build e avvio |
| `ENTRYPOINT [...]` | il comando che parte **quando si avvia il container** | avvio |

La differenza da tenere a mente: `RUN` succede **una volta, durante la build**; `ENTRYPOINT` succede **a ogni
avvio** del container.

**I "layer" e la cache.** Ogni istruzione produce uno strato dell'immagine. Se ricostruisci e i file usati da
un'istruzione non sono cambiati, Docker **riusa** lo strato di prima invece di rifarlo. Per questo l'ordine delle
istruzioni conta: le cose che cambiano di rado (le dipendenze) vanno prima, quelle che cambiano spesso (il codice)
dopo.

**`.dockerignore`.** Come `.gitignore`, ma per la build: i file elencati non vengono mandati a Docker
(`target/`, `node_modules/`, `dist/`). La build è più veloce, e una compilazione vecchia non finisce per sbaglio
nell'immagine.

### Multi-stage: due immagini in un file

Entrambi i Dockerfile hanno **due** `FROM`: sono "multi-stage". Il primo stadio compila, il secondo esegue.

```
 stadio "build"                                stadio finale (quello che gira)
 ┌───────────────────────────────────┐         ┌──────────────────────────────┐
 │ Maven + JDK, codice, dipendenze   │──jar──▶ │ solo JRE + app.jar           │
 │ (centinaia di MB, poi si butta)   │         │                              │
 └───────────────────────────────────┘         └──────────────────────────────┘
```

Per compilare servono Maven, il JDK completo, il codice sorgente e centinaia di MB di dipendenze; per eseguire
basta il JRE e il jar. Il secondo stadio prende **solo** il risultato (`COPY --from=build`), e il primo si butta.
L'immagine finale è più piccola (il backend circa 170 MB: lo stadio di compilazione, con Maven, il JDK e le dipendenze, è molto più grande) e, soprattutto, più sicura: niente
compilatore e niente codice sorgente a disposizione di chi riuscisse a entrare.

### `backend/Dockerfile`, riga per riga

```dockerfile
FROM maven:3.10-eclipse-temurin-21-alpine AS build
```
- `maven:3.10-eclipse-temurin-21-alpine` è il nome dell'immagine base, nel formato `nome:tag`. Il **tag** dice la
  versione: Maven 3.10 (la stessa del wrapper `mvnw`), Java 21 nella distribuzione Eclipse **Temurin** (la stessa
  che usi sul PC), su **Alpine**, un Linux molto piccolo (pochi MB).
- `AS build` chiama questo stadio "build".

```dockerfile
WORKDIR /src
COPY pom.xml .
COPY src ./src
```
- si lavora nella cartella `/src` dell'immagine;
- si copiano il `pom.xml` e il codice (`backend/src`). Il punto `.` vuol dire "la cartella di lavoro", cioè `/src`.

```dockerfile
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
    && cp target/backend-*.jar /src/app.jar
```
- `mvn package` compila e crea il jar, come `./mvnw package` sul PC;
- `-B` (batch): senza colori e barre di avanzamento, illeggibili nei log; `-q` (quiet): scrive solo gli errori;
- `-DskipTests`: i test usano Testcontainers, che ha bisogno di Docker, e dentro una build Docker non c'è. I test
  girano prima (`./mvnw test`) e nella CI;
- `--mount=type=cache,target=/root/.m2`: la cartella dove Maven tiene le dipendenze scaricate resta **tra una
  build e l'altra**, fuori dall'immagine. La seconda volta Maven non riscarica niente;
- `&& cp ...`: il jar ha la versione nel nome (`backend-0.1.0-SNAPSHOT.jar`); lo copiamo con un nome fisso,
  `app.jar`, così il secondo stadio non deve conoscere la versione. La `\` a fine riga dice "il comando continua
  sotto".

```dockerfile
FROM eclipse-temurin:21-jre-alpine
```
Il **secondo stadio**: Java 21, ma il **JRE** (per eseguire), non il JDK (per compilare). Da qui ricomincia
un'immagine vuota: di tutto quello sopra resta solo ciò che copiamo esplicitamente.

```dockerfile
RUN addgroup -S -g 1000 wallettracker && adduser -S -u 1000 -G wallettracker wallettracker
```
Crea un gruppo e un utente `wallettracker`, senza privilegi:
- `-S`: utente "di sistema", senza password e senza accesso;
- `-g 1000` e `-u 1000`: gli id numerici. Linux ragiona per numeri, non per nomi. Il 1000 è quello del primo
  utente su quasi tutti i Linux, così i file che il backend crea in `.data/` (montata dal PC) restano tuoi.

```dockerfile
WORKDIR /app/backend
RUN mkdir -p /app/.data /app/secrets && chown wallettracker:wallettracker /app/.data
COPY --from=build /src/app.jar app.jar
```
- la cartella di lavoro è `/app/backend`, come `backend/` nel progetto;
- `mkdir -p` crea `/app/.data` e `/app/secrets`, come nel progetto; `chown` dà `.data` all'utente
  `wallettracker`, che ci deve scrivere la chiave di cifratura;
- con questa disposizione i percorsi relativi di `application.yml` (`../.data/encryption.key`) valgono uguali sul
  PC e nel container;
- `COPY --from=build` prende **solo** il jar dallo stadio di compilazione.

```dockerfile
USER wallettracker
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```
- `USER`: il backend gira come `wallettracker`, non come root. Se qualcuno riuscisse a fargli eseguire codice, non
  avrebbe i poteri dell'amministratore nel container;
- `EXPOSE 8080`: solo un promemoria (vedi parte E1);
- `ENTRYPOINT`: a ogni avvio parte `java -jar app.jar`, come `java -jar` sul PC. La forma tra parentesi quadre (un
  elenco) avvia Java direttamente, senza una shell in mezzo: così Java riceve il segnale di `docker compose stop`
  e si chiude in ordine;
- `-XX:MaxRAMPercentage=75`: Java usa al massimo il 75% della memoria data al container. Di serie, in un
  container, ne userebbe solo un quarto.

### Approfondimento: perché il backend ha un suo utente, e che cos'è l'id 1000

**A che cosa serve un utente.** Su Linux ogni programma che gira appartiene a **un utente**, e quell'utente decide
che cosa il programma può fare: quali file leggere, quali scrivere, quali comandi eseguire. È come sul tuo PC: con
il tuo account non puoi cancellare i file di sistema, con l'account amministratore sì. Su Linux l'amministratore
si chiama **root** e può fare **tutto**. Se un Dockerfile non dice niente, il programma nel container gira come
root.

**Perché non root.** Immagina che un giorno nel backend ci sia una vulnerabilità (per esempio in una libreria), e
che un attaccante riesca a fargli eseguire comandi suoi:
- **come root**, potrebbe installare programmi, modificare il backend, leggere e scrivere ovunque nel container,
  e avrebbe più possibilità di "evadere" verso il PC;
- **come utente normale**, è chiuso in un recinto: non installa niente, non modifica il programma, scrive solo
  nelle cartelle che gli abbiamo dato.

È il **principio del minimo privilegio**: a ogni programma solo i permessi che gli servono. Il backend deve leggere
il suo jar e scrivere la chiave di cifratura, niente di più.

**Che cos'è l'id 1000.** Linux non riconosce gli utenti dal **nome** ma da un **numero**, l'id (UID): il nome è
solo un'etichetta per noi. Accanto a ogni file Linux scrive il **numero** del proprietario.
- **0** è sempre root;
- **1000** è, per convenzione, il **primo utente "normale"** di un Linux: sul PC Linux di quasi tutti, il tuo
  account ha id 1000.

Conta perché la cartella `.data/` del PC è **condivisa** con il container (`./.data:/app/.data` nel
`docker-compose.yml`): è la stessa cartella vista da due parti, e Linux confronta i numeri.

```
 nel container: il backend (utente 1000) crea encryption.key  → il file appartiene all'id 1000
 sul tuo PC:    il tuo account (id 1000) apre .data/           → "il file è mio": lo leggi, lo copi nei backup
```

Con un altro numero (per esempio 5000) sul PC il file sarebbe di un utente sconosciuto, e per farne il backup
servirebbe `sudo`; con root, sarebbe di root. Su **Windows e Mac** Docker Desktop traduce i permessi da solo e il
numero conta poco; conta su **Linux**, dove molti installerebbero l'app (per esempio su un mini PC).

**Le righe, una per una:**

| Riga | Che cosa fa |
|---|---|
| `addgroup -S -g 1000 wallettracker` | crea il **gruppo** `wallettracker` con id 1000. I gruppi danno permessi a più utenti insieme; qui c'è un utente solo, ma Linux vuole che ognuno ne abbia uno |
| `adduser -S -u 1000 -G wallettracker wallettracker` | crea l'**utente** `wallettracker`, id 1000 (`-u`), nel gruppo appena creato (`-G`). `-S`: utente "di sistema", senza password e senza login: esiste solo per far girare un programma |
| `&&` | "poi, se il comando prima è andato bene" |
| `WORKDIR /app/backend` | la cartella di lavoro, come `backend/` nel progetto. Se non esiste, Docker la crea |
| `mkdir -p /app/.data /app/secrets` | crea le due cartelle (`-p`: nessun errore se esistono già) |
| `chown wallettracker:wallettracker /app/.data` | "change owner": dà `.data` all'utente e al gruppo `wallettracker`, perché ci deve scrivere la chiave di cifratura. `secrets` resta di root: il backend la può solo leggere |
| `COPY --from=build /src/app.jar app.jar` | prende **solo** il jar dallo stadio di compilazione |
| `USER wallettracker` | **da qui in poi si è `wallettracker`, non più root**: per le righe successive e soprattutto per il programma, quando il container parte |
| `ENTRYPOINT [...]` | il comando di ogni avvio, eseguito come `wallettracker` |

**Perché l'ordine conta.** `addgroup`, `mkdir` e `chown` girano ancora **come root**: servono i permessi
dell'amministratore per creare utenti e cambiare proprietari. Per questo stanno tutti **prima** di `USER`: dopo,
non si potrebbero più fare. Root prepara la casa, consegna le chiavi di una stanza (`.data`) e se ne va.

**Una precisazione su `.data` e `secrets`.** Quelle create con `mkdir` sono le cartelle **dell'immagine**. Quando
il container parte, Compose ci "appoggia sopra" le cartelle del PC (`./.data` e `./secrets`): da quel momento
il proprietario che vedi è quello delle cartelle sul PC. È per questo che su Linux `.data/` deve essere del tuo
utente (id 1000); vedi la parte J.

### `frontend/Dockerfile`, riga per riga

```dockerfile
FROM node:24-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
```
- Node 24, la versione che vuole il progetto, su Alpine;
- **prima solo** `package.json` e `package-lock.json`, poi `npm ci`. È il trucco dei layer: se cambi solo il codice
  e non le dipendenze, Docker riusa lo strato di `npm ci` e non reinstalla tutto (che richiede un minuto);
- `npm ci` installa **esattamente** le versioni scritte in `package-lock.json`, come nella CI;
- `--no-audit --no-fund`: niente controlli e messaggi superflui durante la build; la cache di npm resta fuori
  dall'immagine, come quella di Maven.

```dockerfile
COPY . .
RUN NG_CLI_ANALYTICS=false npx ng build
```
- ora tutto il resto del frontend (tranne ciò che è in `.dockerignore`);
- `ng build` crea la build di produzione in `dist/frontend/browser`;
- `NG_CLI_ANALYTICS=false`: Angular non chiede se mandare statistiche d'uso, perché nella build non c'è nessuno a
  rispondere e resterebbe ad aspettare.

```dockerfile
FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /src/dist/frontend/browser /usr/share/nginx/html
EXPOSE 8080
```
- il secondo stadio è **nginx**, nella versione ufficiale "unprivileged": gira senza root e per questo ascolta
  sulla 8080 (le porte sotto la 1024 le può aprire solo root);
- le due configurazioni vanno dove nginx le cerca: `conf.d/` per i siti, e una cartella `snippets/` per i pezzi
  da includere;
- dallo stadio `build` si prende **solo** la cartella della build e la si mette in `/usr/share/nginx/html`, la
  cartella che `default.conf` indica con `root`. Node, `node_modules` (centinaia di MB) e il codice sorgente
  restano nello stadio che si butta: l'immagine finale pesa circa 24 MB;
- qui non c'è `ENTRYPOINT`: lo eredita dall'immagine di nginx, che avvia nginx.

### Perché le versioni sono scritte (i tag)

`postgres:18-alpine`, `node:24-alpine`, `nginx-unprivileged:1.29-alpine`, `ollama/ollama:0.40.11`: ogni immagine ha
la sua versione. Senza tag Docker prende `latest`, "l'ultima uscita", e un giorno l'app potrebbe rompersi da sola,
con una versione nuova che nessuno ha provato. Con il tag si sa sempre che cosa gira, e l'aggiornamento è una
scelta (passo 6).

---

## Parte C: il backend in un container

### C1. Il Dockerfile "multi-stage"

`backend/Dockerfile` ha **due** `FROM`, cioè due immagini una dopo l'altra:

```dockerfile
# 1. Compilazione: Maven e il JDK completo
FROM maven:3.10-eclipse-temurin-21-alpine AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
    && cp target/backend-*.jar /src/app.jar

# 2. L'immagine che gira: solo il JRE e il jar
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 1000 wallettracker && adduser -S -u 1000 -G wallettracker wallettracker
WORKDIR /app/backend
RUN mkdir -p /app/.data /app/secrets && chown wallettracker:wallettracker /app/.data
COPY --from=build /src/app.jar app.jar
USER wallettracker
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```

- **Perché due stadi.** Per compilare servono Maven, il JDK, il codice e centinaia di MB di dipendenze; per
  eseguire basta il JRE e il jar. `COPY --from=build` prende **solo** il jar dal primo stadio, che poi si butta.
  L'immagine finale è più piccola e, soprattutto, contiene meno strumenti utili a un attaccante (niente
  compilatore, niente codice sorgente).
- **`--mount=type=cache`** tiene le dipendenze scaricate da Maven (`~/.m2`) tra una build e l'altra, fuori
  dall'immagine: la seconda volta la build non riscarica niente.
- **`-DskipTests`**: i test usano Testcontainers, che ha bisogno di Docker, e dentro una build Docker non c'è. I
  test girano prima (`./mvnw test`) e nella CI.
- **L'utente `wallettracker` (id 1000)**: se qualcuno riuscisse a far eseguire codice al backend, non sarebbe
  root. L'id 1000 è quello del primo utente su quasi tutti i Linux, così i file in `.data/` restano tuoi.
- **`WORKDIR /app/backend`** riproduce le cartelle del progetto: `/app/backend`, `/app/.data`, `/app/secrets`.
  I percorsi relativi di `application.yml` (`../.data/encryption.key`) valgono uguali sul PC e nel container.
- **`-XX:MaxRAMPercentage=75`**: Java, di serie, in un container usa solo un quarto della memoria che gli è data.

`backend/.dockerignore` dice che cosa **non** mandare alla build (`target/`, il wrapper di Maven): la build
ricompila tutto da sé, e una cartella `target/` vecchia non deve finire nell'immagine per sbaglio.

### C2. Quattro cose che cambiano nel container

In `application.yml` sono diventate variabili, con il valore di prima come predefinito: avviando il backend a
mano non cambia niente.

```yaml
datasource:
  url: jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5432}/${POSTGRES_DB:wallettracker}
server:
  address: ${SERVER_ADDRESS:127.0.0.1}
wallettracker:
  allowed-hosts: ${WALLETTRACKER_ALLOWED_HOSTS:localhost,127.0.0.1}
```

1. **Il database non è `localhost`.** Dentro un container `localhost` è il container stesso. Il database si
   chiama `db`, il nome del servizio: il DNS interno di Docker lo trasforma nell'indirizzo giusto.
2. **Il backend ascolta su `0.0.0.0`.** Con `127.0.0.1` lo raggiungerebbe solo il container stesso, e nginx (un
   altro container) no. È sicuro lo stesso: il servizio `backend` **non pubblica porte**, quindi dal PC e dalla
   rete di casa non si raggiunge.
3. **I nomi consentiti** dal "buttafuori" (`LocalOnlyRequestFilter`, `docs/17`) diventano configurabili. Al passo
   4 aggiungeremo il nome Tailscale.
4. **Ollama** si trova su `http://ollama:11434` (parte G).

### C3. Il controllo di salute: Actuator

Docker può controllare periodicamente se un container "sta bene" (*healthcheck*). Per il database c'era già
(`pg_isready`). Per il backend abbiamo aggiunto **Spring Boot Actuator** (`spring-boot-starter-actuator` nel
`pom.xml`), che offre `/actuator/health`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health      # solo questo: niente /actuator/env, /heapdump, /loggers...
  endpoint:
    health:
      show-details: never    # solo {"status":"UP"}, senza dire che cosa controlla
```

Risponde `UP` se il backend funziona **e** il database risponde. Gli altri endpoint di Actuator (che
mostrerebbero configurazione, password, memoria) restano spenti, e `HealthEndpointTest` lo verifica. In più nginx
inoltra solo `/api/`: da fuori nemmeno `/actuator/health` si raggiunge (provalo: `http://localhost:8000/actuator/health`
mostra la dashboard, non il JSON).

### C4. La chiave di cifratura e i dati di prima

- `./.data` del progetto è montata nel container come `/app/.data`. È la **stessa** cartella usata finora: i
  segreti già salvati (credenziali di Enable Banking, chiave di Gemini) si leggono ancora. Se la cartella è vuota,
  il backend crea una chiave nuova al primo avvio.
- Il **volume del database** si chiama sempre `wallettracker_db-data`: il backend nel container trova i tuoi dati
  di sempre, e Flyway applica solo le migrazioni mancanti (nessuna, se eri aggiornato).
- **Il fuso orario**: un container di serie vive in UTC. `TZ=Europe/Rome` (modificabile nel `.env`) evita che
  "oggi" e "questo mese" siano sbagliati di un paio d'ore vicino alla mezzanotte.

---

## Parte D: nginx, riga per riga

### D0. Come entra nginx nel progetto, e chi sta dentro chi

**nginx non si "installa" nel progetto.** Non è una libreria: non c'è in `package.json` né nel `pom.xml`, e il
codice Angular non cambia di una riga. nginx arriva **già pronto**, come immagine ufficiale scaricata da Docker
Hub. Lo sceglie **una sola riga**, il secondo `FROM` di `frontend/Dockerfile`:

```dockerfile
FROM nginxinc/nginx-unprivileged:1.29-alpine
```

La scelta "nginx o Caddy" è stata implementata con **tre file nuovi e una riga del Compose**:

| File | Che cosa fa |
|---|---|
| `frontend/Dockerfile` | prima compila Angular, poi parte dall'immagine di nginx e ci copia dentro la build e la configurazione |
| `frontend/nginx/default.conf` | dice a nginx che cosa fare: servire i file, passare `/api/` al backend, la cache |
| `frontend/nginx/security-headers.conf` | le intestazioni di sicurezza, incluse in ogni `location` |
| `docker-compose.yml`, servizio `web` | `build: ./frontend`: "costruisci l'immagine con il Dockerfile di quella cartella", e la porta `127.0.0.1:8000` |

Con Caddy sarebbe cambiato solo questo: `FROM caddy:2-alpine` al posto di nginx, e un file `Caddyfile` al posto
di `default.conf`. Il resto (Angular, il backend, il Compose) sarebbe rimasto uguale.

**Chi sta dentro chi.** Il **frontend** sta davvero dentro nginx; il **backend** no.

```
 ┌─ container "web" ─────────────────────┐          ┌─ container "backend" ─┐     ┌─ "db" ─┐
 │  nginx (il programma)                  │          │  Java + app.jar        │     │Postgres│
 │  + i file della build di Angular       │──/api/──▶│                        │────▶│        │
 │    (/usr/share/nginx/html)             │  inoltra │                        │     │        │
 │  + default.conf                        │          └────────────────────────┘     └────────┘
 └────────────────────────────────────────┘
```

- **Il frontend è dentro nginx**: dopo `ng build` è solo una cartella di file, e il Dockerfile la **copia
  fisicamente** dentro l'immagine di nginx. Container `web` = nginx + dashboard.
- **Il backend è fuori**, in un container suo, con Java. nginx non lo contiene: gli **passa** le richieste
  `/api/...` attraverso la rete di Docker e riporta indietro le risposte, come un centralino che passa le
  telefonate a un altro ufficio.

**Perché i file stanno nella cartella `frontend/`.** È solo una scelta di ordine: il Dockerfile del container `web`
sta vicino a ciò che quel container contiene, cioè il frontend. Il Dockerfile deve poter leggere `package.json`
e `src/`, e Docker vede solo i file della cartella indicata in `build:` (il "contesto"). Si poteva fare anche una
cartella `web/` separata, con un `build:` che punta al frontend: il risultato sarebbe identico.

### D1. L'immagine

`frontend/Dockerfile` è multi-stage anche lui: `node:24-alpine` fa `npm ci` e `ng build`; l'immagine finale è
`nginxinc/nginx-unprivileged:1.29-alpine` con dentro solo la cartella della build e due file di configurazione.
*Unprivileged* vuol dire che nginx gira senza root: per questo ascolta sulla porta **8080** (le porte sotto la
1024 le può aprire solo root).

Nota sull'ordine: prima `COPY package.json package-lock.json` e `npm ci`, **poi** `COPY . .`. Docker riusa ogni
passo se i file copiati non sono cambiati: se modifichi solo il codice, `npm ci` non si ripete.

### D2. Come si legge una configurazione di nginx

`frontend/nginx/default.conf` (commentato riga per riga). La sintassi: `direttiva valore;` (sempre con il punto e
virgola) e blocchi `{ ... }`.

```nginx
server {
    listen 8080;
    root /usr/share/nginx/html;
    server_tokens off;            # non dire la versione di nginx a chi attacca
    client_max_body_size 6m;      # come max-request-size di Spring: oltre, 413
    gzip on;                      # JavaScript e CSS compressi: molto più leggeri sul telefono
    ...
}
```

### D3. Le richieste `/api/` al backend

```nginx
resolver 127.0.0.11 valid=10s ipv6=off;
set $backend http://backend:8080;

location /api/ {
    proxy_pass $backend;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 120s;
    ...
}
```

- **`proxy_pass`** è il cuore di un *reverse proxy*: nginx riceve la richiesta, la rifà al backend e ti restituisce
  la risposta. È quello che faceva `proxy.conf.json` per `ng serve`.
- **Perché il nome in una variabile.** Con `proxy_pass http://backend:8080;` nginx cercherebbe l'indirizzo di
  `backend` **una volta sola, all'avvio**: se il backend non c'è ancora, nginx non parte ("host not found in
  upstream"); se il backend viene ricreato e cambia indirizzo, nginx continua a cercarlo al vecchio. Con una
  variabile e un `resolver` (il DNS interno di Docker, sempre `127.0.0.11`), nginx chiede l'indirizzo quando
  serve, al massimo ogni 10 secondi. L'abbiamo provato: ricreato il backend, l'app torna a rispondere da sola.
- **`proxy_set_header Host $host`**: senza, nginx manderebbe al backend `Host: backend`, e il nostro buttafuori la
  rifiuterebbe ("indirizzo backend non consentito"). Così arriva il nome che ha usato il browser (`localhost`).
  L'header `Origin` passa da solo.
- **`proxy_read_timeout 120s`**: "Aggiorna ora" e "Prova la connessione" dell'IA possono durare decine di
  secondi; di serie nginx rinuncia dopo 60.

### D4. I file della build e la cache

Le stesse regole di `serve-dist.mjs`. nginx sceglie il blocco `location` così: prima i blocchi `=` (indirizzo
esatto), poi le **espressioni regolari** (`~`) **nell'ordine in cui sono scritte**, poi il prefisso più lungo
(`location /`).

| Blocco | Per quali file | `Cache-Control` | Se il file non c'è |
|---|---|---|---|
| `location ~ "(-[A-Za-z0-9_]{8,}\.(js\|css)$)\|(^/media/)"` | con l'hash nel nome (`main-JDQTWBUY.js`) e i caratteri | `public, max-age=31536000, immutable`: un anno senza chiedere | 404 |
| `location = /manifest.webmanifest` | il manifest della PWA | `no-cache` | — |
| `location ~ "\.[A-Za-z0-9]+$"` | gli altri file con un'estensione: `ngsw.json`, `ngsw-worker.js`, le icone | `no-cache`: "chiedi ogni volta se è cambiato" | 404 |
| `location /` | tutto il resto: le pagine dell'app (`/movimenti/12`) | `no-cache` | `index.html` |

- **`try_files $uri /index.html`**: "se esiste un file con questo nome mandalo, altrimenti manda `index.html`".
  Così un indirizzo come `/analisi` aperto direttamente (o ricaricando la pagina) funziona: arriva
  `index.html`, e il router di Angular mostra la pagina giusta.
- **Perché 404 per i file con estensione.** Un `.js` che non esiste non deve diventare `index.html`: il browser
  proverebbe a eseguire una pagina HTML come JavaScript, con errori incomprensibili.
- **Perché `no-cache` per `index.html`, `ngsw.json` e il worker.** Se il browser li tenesse in memoria, non
  saprebbe mai che c'è una versione nuova, e l'avviso "È pronta una nuova versione · Aggiorna" non comparirebbe.
- **Il manifest.** nginx riconosce il tipo di un file dall'estensione (il file `mime.types`), ma `.webmanifest`
  non c'è, e lo mandava come `application/octet-stream`, "dati qualsiasi". **L'abbiamo trovato provando**; ora un
  blocco apposta usa `default_type application/manifest+json`.

### D5. Le intestazioni di sicurezza, e una trappola di nginx

Le intestazioni sono in `frontend/nginx/security-headers.conf`, **incluse in ogni `location`**. La ragione è una
regola di nginx che sorprende tutti: **se un blocco `location` contiene anche un solo `add_header`, gli
`add_header` del blocco `server` non valgono più lì dentro.** Noi in ogni `location` mettiamo il suo
`Cache-Control`, quindi le intestazioni di sicurezza scritte una volta sola nel `server` sparirebbero. Con
`include` sono sempre le stesse, e non si dimenticano.

| Intestazione | Che cosa fa |
|---|---|
| `Content-Security-Policy` | la lista di ciò che la pagina può caricare ed eseguire: solo i nostri file, nessuno script scritto dentro la pagina, nessuna chiamata verso altri siti, nessun riquadro. Se qualcuno riuscisse a infilare uno `<script>` (XSS), il browser non lo eseguirebbe, e non potrebbe mandare i dati altrove |
| `X-Content-Type-Options: nosniff` | il browser non "indovina" il tipo di un file |
| `Referrer-Policy: no-referrer` | aprendo un link esterno, non dire da quale pagina arrivi (`/callback?code=...`) |
| `Permissions-Policy` | fotocamera, microfono, posizione, pagamenti spenti |
| `X-Frame-Options: DENY` | nessun sito può mostrare l'app in un suo riquadro (clickjacking) |

**Il difetto trovato provando con la CSP.** Al primo tentativo la pagina restava **senza grafica**. Nella console
del browser:

```
Executing inline script violates the following Content Security Policy directive 'script-src 'self''.
```

Nella build di produzione Angular mette nella pagina il CSS "critico" (*inline critical CSS*) e carica il resto
con `media="print"`, più un piccolo script scritto **dentro** `index.html` che lo trasforma in `media="all"`. La
nostra CSP, giustamente, blocca gli script dentro la pagina, e il CSS restava "solo per la stampa". La soluzione
è in `angular.json`:

```json
"optimization": {
  "scripts": true,
  "styles": { "minify": true, "inlineCritical": false },
  "fonts": true
}
```

Senza l'*inline critical CSS* la pagina carica `styles-….css` normalmente, senza script, e la CSP può restare
rigida. Il costo è un istante in più alla primissima apertura, che per un'app sul PC di casa non si nota (e poi
ci pensa il service worker). L'alternativa sarebbe stata autorizzare quello script con la sua "impronta"
(`'sha256-…'`), che però cambierebbe a ogni versione di Angular.

`style-src` ha invece `'unsafe-inline'`: Angular mette gli stili dei componenti in tag `<style>` nella pagina.
Gli stili non eseguono codice, quindi il rischio è piccolo.

---

## Parte E: `docker-compose.yml`

Il file è commentato riga per riga; qui le idee principali.

```yaml
services:
  db:        # come prima, ma senza "ports"
  backend:
    build: ./backend
    depends_on:
      db:
        condition: service_healthy     # parte quando il database risponde
    environment:
      POSTGRES_HOST: db
      SERVER_ADDRESS: 0.0.0.0
      ...
    volumes:
      - ./.data:/app/.data
      - ./secrets:/app/secrets:ro
    read_only: true
    tmpfs: [/tmp]
    cap_drop: [ALL]
    security_opt: [no-new-privileges:true]
    healthcheck:
      test: ["CMD", "wget", "-q", "-O", "/dev/null", "http://localhost:8080/actuator/health"]
      start_period: 90s
  web:
    build: ./frontend
    ports:
      - "127.0.0.1:${WEB_PORT:-8000}:8080"   # l'UNICA porta aperta, solo su questo PC
  ollama:
    profiles: ["ia"]                         # parte solo se chiesto
```

### E1. "Porta" vuol dire tre cose diverse

Nel `docker-compose.yml` i numeri di porta compaiono in molti punti, ma solo **uno** apre qualcosa verso il PC:

| Dove | Esempio | Che cosa vuol dire | Apre una porta sul PC? |
|---|---|---|---|
| `environment:` del backend | `SERVER_PORT: 8080` | "backend, **ascolta** sulla porta 8080 **dentro il tuo container**" | no |
| `environment:` del backend | `POSTGRES_PORT: 5432` | "backend, il database **lo trovi** sulla porta 5432 di `db`" (è un indirizzo da chiamare, come un numero di telefono) | no |
| `EXPOSE 8080` nel Dockerfile | | solo un promemoria: "questo programma ascolta sulla 8080" | no |
| **`ports:`** di `web` | `"127.0.0.1:8000:8080"` | "**collega** la porta 8000 del PC alla porta 8080 del container" | **sì, l'unica** |

Ogni programma che riceve richieste **ascolta** su una porta: PostgreSQL sulla 5432, il backend sulla 8080, nginx
sulla 8080 del suo container. Ma quella porta esiste **dentro il container**, sulla rete privata di Docker: la
raggiungono solo gli altri container. Diventa raggiungibile dal PC solo se la **pubblichi** con `ports:`. Il
backend e il database non hanno `ports:`, e quindi dal PC non si raggiungono (prova: `curl http://localhost:8080`
non risponde).

### E2. Perché le credenziali del database compaiono due volte

`POSTGRES_DB`, `POSTGRES_USER` e `POSTGRES_PASSWORD` sono sia in `db` sia in `backend`. Non è un doppione: ogni
container è isolato e vede **solo** le variabili scritte nel suo blocco `environment:`, e i due le usano per
due cose opposte.

- **Nel `db` creano la serratura.** L'immagine di PostgreSQL le legge **al primo avvio**, quando il volume è
  vuoto: "crea il database `wallettracker`, con l'utente `wallettracker` e questa password".
- **Nel `backend` sono la chiave.** Spring Boot le usa per collegarsi (`spring.datasource` in `application.yml`):
  "entra nel database `wallettracker` con questo utente e questa password".

I valori arrivano dallo **stesso posto**, il `.env`: `${POSTGRES_PASSWORD}` vuol dire "metti qui il valore del
`.env`". La password la scrivi una volta sola e Compose la consegna a entrambi, così serratura e chiave
coincidono per forza.

Una conseguenza da sapere: il `db` usa la password **solo al primo avvio**. Se poi la cambi nel `.env`, il
backend riceve quella nuova, mentre il database tiene quella vecchia, e il backend non entra più ("password
authentication failed"). Cambiare la password di un database già creato è un'operazione a parte (passo 2).

- **Le variabili del backend una per una**, e non tutto il `.env` (`env_file`): si vede che cosa riceve, e un
  valore pensato per il PC (es. `POSTGRES_PORT=5433`) non finisce per sbaglio nel container.
- **`depends_on` con `service_healthy`**: Flyway, all'avvio, deve già poter entrare nel database.
- **`restart: unless-stopped`**: Docker riavvia i container se si fermano e quando riparte (dopo aver riacceso il
  PC), finché non li fermi tu.
- **`ports: "127.0.0.1:8000:8080"`**: "porta 8000 del PC, solo per chi è sul PC, verso la porta 8080 del
  container". Senza `127.0.0.1` la porta sarebbe aperta anche alla rete di casa. Al passo 4, Tailscale parlerà
  con nginx proprio da qui, dal PC stesso.
- **Il database non ha più porte aperte.** Per lo sviluppo c'è `docker-compose.dev.yml`, che le aggiunge:

  ```bash
  docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d db
  ```

  Compose unisce i due file: il secondo aggiunge solo `ports` al servizio `db`.

> **Attenzione: non tenere accesi insieme il backend del PC (`./mvnw spring-boot:run`) e quello nel container.**
> Sono due programmi che aggiornano gli stessi conti: le letture dalle banche raddoppierebbero, oltre il limite di
> 4 al giorno (PSD2).

---

## Parte F: la sicurezza di questo passo

Nella tabella di `docs/17` sono le righe 1 (aggiornata), **28**, **29** e **30**:

- **28. Container**:
  - utente senza privilegi in backend e nginx;
  - **file system in sola lettura** (`read_only: true`): un attaccante non potrebbe modificare il programma né
    lasciare file. Restano scrivibili solo `/tmp` (in memoria) e `.data/`;
  - **nessuna capability** di Linux (`cap_drop: ALL`): i "poteri speciali" di root a pezzi, come cambiare i
    permessi dei file o aprire porte basse. A backend e nginx non ne serve nessuno;
  - `no-new-privileges`: nessun programma nel container può diventare più potente di com'è partito;
  - immagini multi-stage, senza compilatori né sorgenti; `secrets/` in sola lettura.
- **29. Intestazioni di sicurezza** di nginx (parte D5), e `Cache-Control: no-store` sulle API.
- **30. Actuator**: solo `health`, senza dettagli, e non raggiungibile da fuori; il limite di 6 MB sui file
  caricati già in nginx.

Il database **non** ha `read_only` e `cap_drop`: all'avvio PostgreSQL deve sistemare i permessi della sua
cartella. Resta chiuso nella rete interna, senza porte.

---

## Parte G: Ollama, facoltativo

Le tre possibilità, e la scelta:

| Come | Pro | Contro |
|---|---|---|
| **A. container `ollama` con il profilo `ia`** (scelta) | un solo comando; nessuna porta aperta; si aggiorna con il resto | dentro Docker la scheda video si usa solo con NVIDIA su Windows/Linux; sui Mac solo il processore (più lento, ma per le poche domande in background va bene) |
| B. Ollama installato sul PC (ollama.com) | usa sempre la scheda video, anche sui Mac | un programma in più da installare e aggiornare; su Linux va fatto ascoltare fuori da `127.0.0.1` |
| C. niente IA locale | l'app funziona benissimo lo stesso | — (resta Gemini, che però manda le causali a Google) |

```bash
docker compose up -d                 # senza IA locale (si può usare Gemini)
docker compose --profile ia up -d    # con Ollama
```

- **Senza il profilo**, il backend parte lo stesso: Spring AI si collega a Ollama solo alla prima domanda. In
  Impostazioni la sezione di Ollama dice che non risponde, e si può scegliere Gemini o nessuna IA.
- **Il modello** si scarica come prima dalla pagina Impostazioni, e resta nel volume `ollama-data`.
- **Opzione B**: nel `.env` `OLLAMA_URL=http://host.docker.internal:11434`. `host.docker.internal` è un nome
  speciale che, dentro un container, vuol dire "il PC che mi ospita".
- **Se avevi Ollama nel container creato a mano** (`docker run ... --name ollama`, `docs/27`): fermalo
  (`docker stop ollama`), oppure usalo come opzione B. Il nuovo container ha un volume suo, quindi il modello va
  riscaricato da Impostazioni (una volta sola).

---

## Parte H: prova

### H1. Il primo avvio

1. Ferma `npm start`, il backend avviato a mano e, se c'è, il vecchio container `ollama`.
2. Dalla radice del progetto:

   ```bash
   git pull
   docker compose up -d --build
   ```

   La prima volta ci vogliono alcuni minuti: Docker scarica le immagini di base e compila backend e dashboard.
   Le volte successive bastano pochi secondi.
3. Controlla lo stato:

   ```bash
   docker compose ps
   ```

   Dopo circa un minuto tutti e tre devono essere `Up ... (healthy)`. Solo `wallettracker-web` ha una porta:
   `127.0.0.1:8000->8080/tcp`.
4. Apri **`http://localhost:8000`**: ci sono i tuoi conti e movimenti di sempre (il volume del database è lo
   stesso).

In Docker Desktop vedi lo stesso nella scheda **Containers**: il gruppo `wallettracker` con i tre container, i
loro log (clic sul nome) e i pulsanti ▶ / ■.

### H2. Che cosa provare

| Prova | Risultato atteso |
|---|---|
| Riepilogo, Movimenti, un movimento, Analisi, Budget, Abbonamenti, Conti, Impostazioni | si aprono, come con `npm start` |
| ricarica la pagina su `/analisi` (F5) | resta su Analisi (`try_files`) |
| scrivi una nota a un movimento, poi ricarica | resta salvata (la richiesta passa da nginx al backend con l'`Origin` giusto) |
| `http://localhost:8000/actuator/health` | la dashboard, non il JSON: da fuori Actuator non si vede |
| strumenti per sviluppatori (F12) → **Network** → `main-….js` | `Cache-Control: public, max-age=31536000, immutable`, `Content-Encoding: gzip` |
| F12 → `index.html` o `ngsw.json` | `Cache-Control: no-cache`, e le intestazioni di sicurezza |
| F12 → **Console** | nessun errore (in particolare nessun "Content Security Policy") |
| F12 → **Application** → Service workers | `ngsw-worker.js` attivo: l'app è installabile (icona nella barra degli indirizzi). Su `localhost` il browser la considera sicura anche senza https |
| `docker compose restart backend`, poi ricarica | per qualche secondo "Senza rete" o i dati salvati, poi tutto torna (nginx ritrova il backend) |
| `docker compose down` e poi `docker compose up -d` | i dati ci sono ancora |
| dal telefono, nella stessa Wi-Fi, `http://<ip-del-PC>:8000` | **non** si apre: la porta è solo su `127.0.0.1` |

### H3. Due limiti di questo passo (si risolvono più avanti)

- **Collegare una banca nuova (o "Ricollega") dall'app nel container.** Il redirect registrato su Enable Banking è
  `https://localhost:8443/callback`, cioè `ng serve`: al ritorno dalla banca il browser andrebbe lì, dove ora non
  c'è nessuno. Le banche già collegate continuano ad aggiornarsi (sono connessioni in uscita). Se nel frattempo
  devi ricollegare una banca, usa per qualche minuto l'avvio da sviluppo (`docs/13`), oppure, se il pannello di
  Enable Banking accetta anche un indirizzo `http://localhost`, registra `http://localhost:8000/callback` e
  mettilo in `ENABLE_BANKING_REDIRECT_URL` nel `.env`. Al passo 4 registreremo l'indirizzo Tailscale, che varrà
  da PC e telefono.
- **Se avevi installato la PWA da `http://localhost:4300`** (`npm run pwa`, `docs/42`): è un'altra "origine", un'app
  diversa. Disinstallala e installa quella di `http://localhost:8000`.

### H4. Comandi di tutti i giorni

```bash
docker compose ps                       # stato
docker compose logs -f backend          # i log del backend (Ctrl+C per uscire)
docker compose logs --tail 50 web       # le ultime 50 righe di nginx
docker compose exec web nginx -t        # controlla la configurazione di nginx
docker compose up -d --build            # dopo un git pull: ricostruisce solo ciò che è cambiato
docker compose down                     # ferma tutto (i dati restano nei volumi)
```

---

## Parte I: test e verifiche fatte

- **Backend**: 322 test, tutti verdi (1 saltato, come prima); nuovo `HealthEndpointTest` (3): `/actuator/health`
  è `UP` senza dettagli; `/actuator/env`, `configprops`, `loggers`, `heapdump`, `beans` danno 404; Actuator passa
  dal buttafuori.
- **Frontend**: 192 test verdi, Prettier, build di produzione.
- **CI**: nuovo job "Immagini Docker" che costruisce le immagini e controlla la configurazione di nginx
  (`nginx -t`) a ogni push.
- **Prove dal vivo** con `docker compose up`, un anno di movimenti inventati sul conto "Banca Alfa" e Chromium:

| Prova | Esito |
|---|---|
| i tre container `healthy`; solo `web` con una porta, `127.0.0.1:8000` | ✅ |
| Flyway nel container: 28 migrazioni su un database vuoto | ✅ |
| chiave di cifratura creata in `.data/` con permessi `600` | ✅ |
| ora del backend in `+01:00` (`TZ`) | ✅ |
| tutte le pagine nel browser (formato telefono), senza errori nella console né risposte ≥ 400 | ✅ |
| service worker attivo e pagina controllata dal worker dopo una ricarica | ✅ |
| `/movimenti/12` e `/analisi` aperti direttamente | ✅ `index.html` |
| file con hash: cache di un anno e gzip; `index.html`, `ngsw.json`, manifest: `no-cache` | ✅ |
| `/nonesiste.js` | ✅ 404 |
| `/../../etc/passwd` | ✅ 400 |
| `/actuator/health` da nginx | ✅ la dashboard, non il JSON |
| una nota scritta dal browser (`PUT` attraverso nginx), visibile dopo la ricarica | ✅ 200 |
| `fetch('https://example.com')` dalla pagina | ✅ bloccata dalla CSP |
| scrivere un file nel container del backend | ✅ "Read-only file system" |
| `Host: evil.example` (DNS rebinding) | ✅ 403 |
| `POST /api/sync` con `Origin: https://evil.example` | ✅ 403 |
| file di 7 MB caricato | ✅ 413 |
| backend ricreato (indirizzo nuovo) | ✅ l'app torna a rispondere da sola dopo ~16 s |
| `down` e `up`: i movimenti ci sono ancora | ✅ |
| con `--profile ia` il backend vede Ollama (`ollamaReachable: true`); fermato Ollama, `false` senza errori | ✅ |

**Difetti trovati provando, e corretti:** il manifest mandato come `application/octet-stream` (parte D4); la pagina
senza grafica per la CSP e l'*inline critical CSS* (parte D5).

**Non provato qui:** lo scaricamento di un modello di Ollama dentro il container (l'ambiente di prova non
raggiunge `registry.ollama.ai`); il collegamento di una banca vera.

---

## Parte J: problemi comuni

| Sintomo | Causa probabile | Che cosa fare |
|---|---|---|
| `Bind for 127.0.0.1:8000 failed: port is already allocated` | un altro programma usa la porta 8000 | nel `.env` `WEB_PORT=8001` (o un'altra), poi `docker compose up -d` |
| `wallettracker-backend` resta `unhealthy` o si riavvia | il backend non parte | `docker compose logs backend`: di solito la password del database (`POSTGRES_PASSWORD` diversa da quella con cui il volume è stato creato) |
| "Il file ... non contiene una chiave di cifratura valida" | `.data/encryption.key` rovinato | rimetti quello del backup; senza, ricarica il `.pem` da Impostazioni |
| su Linux: "Impossibile leggere o creare la chiave di cifratura" | Docker ha creato `.data/` come root | `mkdir .data` (come tuo utente) **prima** del primo `up`, o `sudo chown 1000:1000 .data` |
| 502 Bad Gateway | il backend non è (ancora) pronto | aspetta che sia `healthy` (`docker compose ps`) |
| 403 "indirizzo ... non consentito" | apri l'app con un nome non in `WALLETTRACKER_ALLOWED_HOSTS` | usa `localhost`, o aggiungi il nome nel `.env` |
| Ollama "non risponde" in Impostazioni | il profilo `ia` non è attivo | `docker compose --profile ia up -d` |
| non vedo le modifiche al codice | l'immagine è quella vecchia | `docker compose up -d --build` |
| la pagina è bianca o senza grafica | intestazioni o file vecchi | F12 → Console; poi "Svuota la cache e ricarica" |
| psql/DBeaver non si collegano più al database | ora il database non ha porte aperte | `docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d db` |

---

## Esercizi guidati

1. **Leggi una richiesta in nginx.** `docker compose logs -f web`, poi apri una pagina: per ogni richiesta c'è
   una riga con metodo, indirizzo, codice (200, 304...) e dimensione. Che cosa vuol dire `304`? (Suggerimento:
   `no-cache` e la domanda "è cambiato?".)
2. **La trappola di `add_header`.** In `default.conf` togli la riga `include .../security-headers.conf;` dal
   blocco `location /`, ricostruisci (`docker compose up -d --build web`) e guarda le intestazioni di
   `http://localhost:8000/riepilogo` con F12. Che cosa manca? Rimettila.
3. **La CSP al lavoro.** Nella console del browser scrivi
   `fetch('https://example.com')`. Che errore compare, e quale direttiva lo blocca?
4. **Il DNS di Docker e il buttafuori.** `docker compose exec backend wget -q -O - http://web:8080/ | head -3`:
   il backend raggiunge nginx con il suo nome, `web`. Ora il contrario:
   `docker compose exec web wget -q -O - http://backend:8080/actuator/health`. Risponde **403**: perché?
   (Suggerimento: quale header `Host` manda `wget`, e quali nomi accetta `LocalOnlyRequestFilter`? È lo stesso
   motivo per cui in `default.conf` c'è `proxy_set_header Host $host`.) E perché dal browser
   `http://backend:8080` non si apre proprio?
5. **Sola lettura.** `docker compose exec backend touch /app/backend/prova`: che errore dà? E
   `docker compose exec backend touch /tmp/prova`?
6. **La porta chiusa.** Dal telefono, nella stessa Wi-Fi, prova `http://<ip-del-PC>:8000`. Poi, per un minuto,
   cambia `127.0.0.1:` in `0.0.0.0:` nella riga `ports` di `web` e riprova (`docker compose up -d`). Rimetti
   `127.0.0.1` subito dopo: con l'app senza PIN, chiunque nella Wi-Fi vedrebbe i tuoi movimenti.
7. **Per curiosità: https con nginx.** Oggi https lo metterà Tailscale (`tailscale serve`, passo 4). L'alternativa
   è `tailscale cert`, che crea i file del certificato da dare a nginx (`listen 443 ssl;`, `ssl_certificate`,
   `ssl_certificate_key`). Cerca nella documentazione di nginx come si scrive, e pensa a che cosa succede dopo 90
   giorni, quando il certificato scade.
8. **Chi sono, nel container?** Con l'app avviata, `docker compose exec backend id`: risponde
   `uid=1000(wallettracker) gid=1000(wallettracker)`, quindi il backend non è root. Poi
   `docker compose exec backend ls -ln /app`: con `-n` Linux mostra i **numeri** dei proprietari invece dei nomi.
   Di chi risultano `backend`, `.data` e `secrets`? Confrontalo con `ls -ln` della cartella del progetto sul PC
   (su Windows, in WSL o in Git Bash). Infine prova `docker compose exec backend touch /app/.data/prova` (riesce:
   è la stanza del backend; poi cancella il file) e `docker compose exec backend touch /app/secrets/prova` (non
   riesce: è in sola lettura).
