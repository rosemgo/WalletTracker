# Fase 4: consegne per la nuova sessione

Questo file passa il lavoro a una nuova sessione di Claude Code (nel cloud), dedicata alla **Fase 4**. Va letto per
primo, insieme al `README.md`. Le guide `docs/01`…`docs/43` raccontano tutto il resto: si consultano quando
servono, non tutte all'inizio.

---

## 1. Il progetto in breve

**WalletTracker**: un'app di finanza personale **self-hosted e didattica**. Una installazione per persona, sul suo
computer. Legge i conti dalle banche con **Enable Banking** (PSD2) e li importa da CSV. Classifica i movimenti
(regole, suggerimenti, IA facoltativa) e mostra Riepilogo, Movimenti, Analisi, Budget e Abbonamenti.

| Parte | Tecnologia | Dove |
|---|---|---|
| Backend | Spring Boot 4.1, Java 21, Spring AI, Flyway | `backend/` (API su `http://localhost:8080`) |
| Database | PostgreSQL 18 (Docker) | `docker-compose.yml` (oggi solo il servizio `db`) |
| Frontend | Angular 22, standalone, signals, zoneless | `frontend/` (`npm start` → `https://localhost:8443`) |
| PWA | `@angular/service-worker`, solo build di produzione | `frontend/ngsw-config.json`, `public/manifest.webmanifest` |

**Stato.** Le fasi 1-3 e la fase grafica sono finite (vedi "Piano di lavoro" nel README). Ultimo commit della
sessione precedente: "Analisi: Senza categoria porta ai movimenti; menu aperti ridisegnati" (`docs/43`).
- Test: backend 332 (1 saltato, `./mvnw test`, usa Testcontainers e quindi Docker); frontend 192
  (`npx ng test --watch=false`).
- Il backend non ha login: è pensato per una persona sola. Per questo **non** va mai esposto su Internet (vedi §4).

---

## 2. Chi è l'utente e come lavorare con lui

- **La lingua.** Scrive in **italiano**, e tutto va in italiano: risposte, guide, commenti nel codice. I nomi nel
  codice e i test sono in inglese, come nel resto del progetto.
- **Cosa conosce.**
  - Conosce bene **Java**.
  - Angular, CSS, Docker e reti li sta imparando: spiega in modo semplice, con il perché.
  - **Non conosce Tailscale.** La spiegazione teorica è già stata data (§4), ma va ripresa nella guida.
- **Come procedere.**
  - Prima la teoria e il piano; poi il codice, un passo alla volta.
  - L'utente prova ogni passo e risponde "Funziona, passiamo al passo N".
- **Cosa dà a ogni passo.**
  - Una **guida passo-passo** in `docs/NN-titolo.md` (la prossima è la **44**), con parti A, B, C…: spiegazioni,
    pezzi di codice commentati, sicurezza, test, prova nel browser e "esercizi guidati" alla fine.
  - Aggiorna l'indice e il "Piano di lavoro" del `README.md`, e la tabella della sicurezza `docs/17` (una riga per
    ogni nuova protezione; l'ultima è la 27).
  - Commenti nel codice in italiano, chiari, che spiegano il perché (guarda `docker-compose.yml` e
    `frontend/scripts/serve-dist.mjs` come esempio di tono).
- **Verificare sempre, prima di dire "fatto".**
  - Test, build, `npx prettier --check "src/**/*.{ts,html,css}"` nel frontend.
  - Per tutto ciò che si vede, una prova reale nel browser con Playwright (Chromium è preinstallato nel cloud:
    `require('playwright')`, `executablePath: '/opt/pw-browsers/chromium'`).
  - Racconta anche i difetti trovati provando.
- **Le risposte finali.** In italiano, ordinate: che cosa è cambiato, come è stato verificato, che cosa resta da
  decidere.

---

## 3. Regole che non si cambiano

- **Branch.** Lavorare e fare push solo sul branch indicato dalla sessione. Non creare PR se non chiesto. `git pull`
  prima di modificare.
- **Commit.** Messaggi in italiano. Alla fine le righe di attribuzione richieste dall'ambiente. **Nessun nome di
  modello** nei file del repository.
- **Mai su Git:**
  - `.env`, `secrets/`, `*.pem`, `.data/`;
  - dati veri: niente nomi di persone, IBAN veri, estratti conto. Nei test, nei documenti e negli esempi si usano
    dati **inventati**: "Banca Alfa", "ACME SRL", "BETA SPA", IBAN `IT00X…`, "MARIO ROSSI" come titolare finto.
  - I file che l'utente carica nella chat (PDF, CSV, video, screenshot) possono contenere dati veri: non vanno mai
    nel repository.
- **Migrazioni Flyway già applicate: mai modificarle.** Ultima: `V28__nome_nella_causale.java`. Una modifica = una
  migrazione nuova.
- **Sicurezza prima di tutto.**
  - L'IA suggerisce soltanto, non decide.
  - Le porte su `127.0.0.1` quando non serve altro.
  - Niente esposto su Internet.

---

## 4. La Fase 4: che cosa abbiamo già deciso a voce

L'obiettivo: da "tre programmi avviati a mano da un programmatore" a "**un comando**, e l'app si usa anche dal
telefono, in sicurezza". Il piano proposto (l'utente l'ha approvato in linea di principio):

1. **Docker Compose con tre contenitori**
   - `db`: PostgreSQL, come oggi, con il volume `db-data`.
   - `backend`: il jar di Spring Boot. Dockerfile multi-stage: si compila con Maven in un'immagine, si esegue con
     solo il JRE; utente non root.
   - `web`: un piccolo server web (proposta: **Caddy**; nginx va bene uguale). Fa tre cose: serve i file della
     build di produzione del frontend, rimanda le richieste `/api/*` al backend, e mette https. Vedi §5.

   Avvio: `docker compose up -d`, con `restart: unless-stopped`. Solo `web` pubblica una porta; `db` e `backend`
   stanno nella rete interna di Docker.
2. **Configurazione e segreti**
   - `.env` (con un `.env.example` versionato);
   - la chiave di cifratura (`WALLETTRACKER_KEY_FILE`, oggi `../.data/encryption.key`), creata da sola al primo
     avvio se manca;
   - le credenziali di Enable Banking, che si inseriscono già dalla pagina Impostazioni e sono salvate cifrate;
   - le variabili lette dal backend, in `backend/src/main/resources/application.yml`: `POSTGRES_*`,
     `SERVER_PORT`, `ENABLE_BANKING_*`, `WALLETTRACKER_KEY_FILE`, `SYNC_INTERVAL`, `OLLAMA_URL`. Nel contenitore
     l'URL del database **non** sarà `localhost` ma il nome del servizio (`db`).
3. **Backup e ripristino**
   - un comando di backup (`pg_dump` più la chiave di cifratura, **senza la quale i dati cifrati non si leggono**)
     e uno di ripristino;
   - **provare il ripristino davvero**, in un test;
   - se l'utente vuole, un backup automatico.
4. **Tailscale: accesso dal telefono**
   - Una rete privata solo tra i dispositivi dell'utente: niente porte aperte sul router.
   - `tailscale serve` (o il certificato di `tailscale cert`) dà un nome tipo `pc-casa.<rete>.ts.net` con un
     **https valido**. Serve alla PWA per installarsi sul telefono (`docs/42`, parte F).
   - L'installazione la fa l'utente sui suoi dispositivi: la guida deve essere passo-passo, con le schermate dei
     menu descritte.
   - **Enable Banking:** il redirect URL oggi è `https://localhost:8443/callback`. Andrà registrato anche quello con
     il nome Tailscale, per collegare una banca dal telefono. Verificare se Enable Banking accetta più redirect
     URL per applicazione.
5. **Aggiornamenti**
   - un comando per passare alla versione nuova senza perdere dati (Flyway migra da solo);
   - il service worker mostra già "È pronta una nuova versione · Aggiorna".
6. **La guida per chi non è informatico:** installazione di Docker Desktop e di Tailscale, primo avvio, backup.

**Decisioni ancora aperte (da chiedere all'utente):**
- **Un PIN o una password** per aprire l'app, anche dentro Tailscale (telefono perso, dispositivi condivisi). Il
  backend oggi non ha autenticazione.
- **Il PC di casa resta acceso?** Se no: un mini PC o un Raspberry Pi sempre acceso. A PC spento la PWA mostra gli
  ultimi dati scaricati.
- **Caddy o nginx** (proposta: Caddy, configurazione più corta e https semplice).
- L'IA locale (Ollama) in un contenitore o fuori? Oggi l'app la raggiunge su `OLLAMA_URL`, e da un contenitore
  `localhost` non è il PC: servirà `host.docker.internal`.

---

## 5. La domanda in sospeso: perché un server "web" tra frontend e backend

L'utente ha chiesto: *"serve un server per far comunicare backend e frontend? non capisco"*. La risposta è già
stata data in chat. La riprendiamo qui, perché va messa nella guida del passo 1.

- **Il frontend Angular, dopo `ng build`, è solo una cartella di file statici** (`frontend/dist/frontend/browser`:
  `index.html`, file `.js` e `.css`, icone, caratteri). Non è un programma che gira: qualcuno deve **mandare** quei
  file al browser quando li chiede. È il mestiere di un server web.
- **Oggi lo fa `ng serve`**, che però è un server da sviluppo: ricompila a ogni modifica, è lento, non è fatto per
  restare acceso. Inoltre è senza service worker.
- **Il backend Spring Boot non manda quei file**: risponde solo a `/api/...`. Si potrebbe fargli servire anche il
  frontend (copiandolo nel jar), ma mescola le due cose e complica gli aggiornamenti.
- **Chi parla con il backend è il browser, non il frontend "in quanto server".** Il codice Angular gira nel
  browser, e da lì chiama `/api/...`.
- **Perché `/api` deve passare dallo stesso indirizzo.** Sempre per sicurezza, il browser tratta `https://casa:443`
  e `http://casa:8080` come due "origini" diverse: le chiamate tra origini diverse sono bloccate se non si
  configura il CORS. Con un solo indirizzo davanti a tutto, il problema non esiste. È già così oggi:
  `proxy.conf.json` fa fare a `ng serve` da tramite verso `localhost:8080`, e `frontend/scripts/serve-dist.mjs`
  (`npm run pwa`) fa lo stesso per la build di produzione.
- **Il server web (Caddy) fa in produzione quello che oggi fanno `ng serve` e `serve-dist.mjs`:**
  - manda i file statici;
  - per un indirizzo come `/movimenti/12` manda `index.html`, perché le pagine le gestisce il router di Angular;
  - rimanda `/api/*` al contenitore `backend`;
  - mette https (con il certificato di Tailscale);
  - è l'**unico** con una porta aperta verso fuori: backend e database restano chiusi nella rete interna di Docker.

  Nel disegno: `telefono/PC → Caddy (https) → /api → backend → db`; il resto sono file della build.
- **Le intestazioni di cache** decise in `serve-dist.mjs`: `index.html`, `ngsw.json`, `ngsw-worker.js`, il manifest
  e le icone senza cache lunga; i file con l'hash nel nome con cache lunga. Vanno riportate nella configurazione di
  Caddy, altrimenti la PWA non vede le versioni nuove.

---

## 6. Cose utili sull'ambiente cloud (imparate nella sessione precedente)

- **Node.** Il frontend vuole **Node 24**. Se nel contenitore c'è una versione diversa, scarica Node 24 in una
  cartella temporanea e mettilo nel `PATH`.
- **Docker.** Può servire avviarlo a mano: `nohup dockerd > /tmp/dockerd.log 2>&1 &`, poi aspettare che
  `docker info` risponda. Serve anche ai test del backend (Testcontainers).
- **Avviare tutto per le prove nel browser** (fino alla Fase 4):
  1. un PostgreSQL in Docker su `127.0.0.1:55432`;
  2. il backend con `POSTGRES_PORT=55432 POSTGRES_PASSWORD=… WALLETTRACKER_KEY_FILE=<file temporaneo>
     SYNC_INTERVAL=1000d ./mvnw -q -o spring-boot:run`;
  3. il frontend con `npx ng serve --ssl --port 8443`.

  Per avere dati, inserire con `psql` un anno di **movimenti inventati** su un conto "Banca Alfa", poi mettere il
  conto come principale (`PUT /api/accounts/{id}/role` con `{"role":"MAIN"}`), che riclassifica tutto.
- **Fermare i processi.** Usare pattern con le parentesi: `kill $(pgrep -f 'ng serv[e]')`,
  `pgrep -f 'WalletTrackerApplicatio[n]'`. Un comando che contiene il testo esatto cercato uccide anche la shell
  stessa (exit 144).
- **Playwright e "senza rete".** `context.setOffline(true)` **non** ferma le richieste del service worker in
  Chromium. Per provare davvero senza rete, spegnere il server (`docs/42`, parte H).
- **Le icone PNG** si generano da `frontend/public/icons/*.svg` con Chromium/Playwright (`docs/42`, parte D).

---

## 7. Da dove partire nella nuova sessione

1. Leggere questo file, il `README.md`, `docker-compose.yml`, `backend/src/main/resources/application.yml`,
   `frontend/scripts/serve-dist.mjs`, `frontend/ngsw-config.json` e `docs/17-sicurezza.md`.
2. Rispondere all'utente sulle decisioni aperte (§4), se non le ha già date.
3. Passo 1: Dockerfile del backend, del frontend (build) e configurazione di Caddy; `docker-compose.yml` con i tre
   servizi; prova: `docker compose up -d`, l'app su `https://localhost` (o la porta scelta), le pagine e la PWA
   funzionanti, i test che passano. Guida `docs/44`.
