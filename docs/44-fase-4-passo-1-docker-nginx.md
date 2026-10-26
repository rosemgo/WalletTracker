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
- Parte A: il quadro (perché un server web tra browser e backend; Tailscale, Cloudflare, il PIN)
- Parte B: Docker in cinque parole (immagine, container, volume, rete, Compose)
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
