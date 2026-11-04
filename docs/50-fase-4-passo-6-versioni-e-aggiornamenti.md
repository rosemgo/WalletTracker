# 50. Fase 4, passo 6: versioni, immagini già pronte, aggiornamenti e la guida per chi non è informatico

**Obiettivo di questo passo:** che WalletTracker lo possa installare e aggiornare **chiunque**, senza Git, senza
compilare niente e senza aprire un terminale. Il passo è diviso in quattro parti:

| Parte | Che cosa | Stato |
|---|---|---|
| **6a** | **il numero di versione e le immagini già pronte** (GitHub Container Registry, release con lo ZIP) | **questa guida, parti A-H** |
| 6b | "Aggiorna": passare alla versione nuova con un comando, backup prima | da fare |
| 6c | il doppio clic (Windows e Mac) e l'avvio automatico | da fare |
| 6d | la guida per chi non è informatico (`GUIDA.md`), e la prova di `installa.ps1` su Windows | da fare |

Le parole sono quelle della `docs/47`: il **server** è la macchina su cui gira Docker con WalletTracker.

**Le scelte fatte insieme:**

| Domanda | Scelta |
|---|---|
| come arriva l'app all'utente? | **immagini già pronte**, costruite dalla CI e pubblicate su GitHub Container Registry |
| come arrivano gli aggiornamenti? | **versioni numerate** (release di GitHub): "Aggiorna" scarica l'ultima, senza Git |
| dove sta la guida per chi non è informatico? | nel repository (`GUIDA.md`), con le schermate |
| per quali sistemi il doppio clic? | Windows e Mac (Linux resta con lo script da terminale) |

**Che cosa è cambiato nel 6a:**

| Dove | Che cosa |
|---|---|
| `backend/Dockerfile`, `frontend/Dockerfile`, `docker/backup/Dockerfile` | la versione (`ARG`), le etichette standard, la compilazione sul processore di chi costruisce (`--platform=$BUILDPLATFORM`) |
| backend: `about/VersionController`, `application.yml` | `GET /api/version` |
| frontend: Impostazioni | "Informazioni": la versione installata |
| `docker-compose.yml` | `image: ghcr.io/rosemgo/wallettracker-…:${WALLETTRACKER_VERSION}` al posto di `build:` |
| `docker-compose.build.yml` (nuovo) | per costruire le immagini dal codice (sviluppo) |
| `installa.sh`, `installa.ps1` | riconoscono da soli "codice sorgente" o "versione scaricata" |
| `.github/workflows/rilascio.yml` (nuovo), `.github/ci/prepara-zip.sh` (nuovo), `ci.yml` | pubblicare una versione: test, immagini, release |
| `NOVITA.md` (nuovo) | le novità di ogni versione |

**Indice**
- Parte A: le idee di base (immagine, registro, tag, versione, release)
- Parte B: il numero di versione nell'app
- Parte C: immagini già pronte o costruite dal codice
- Parte D: pubblicare una versione: `rilascio.yml`
- Parte E: la prima versione, passo per passo
- Parte F: la sicurezza di questo passo
- Parte G: verifiche fatte e difetti trovati
- Parte H: problemi comuni
- Esercizi guidati

---

## Parte A: le idee di base

![Dal tuo codice al computer di chi usa l'app](img/rilascio.svg)

### A1. Perché immagini già pronte

Fino al passo 5, chi installava WalletTracker **compilava** l'app sul suo computer: lo script lanciava
`docker compose up --build`, e Docker eseguiva i Dockerfile. Maven scaricava le dipendenze di Java e compilava il
backend, npm quelle di Angular e costruiva la dashboard. Funziona, ma:
- ci vogliono **minuti**, e centinaia di MB di download;
- ogni installazione è una compilazione diversa, e **può andare storta** (una dipendenza non raggiungibile, poca memoria);
- per aggiornare bisogna avere **Git** e ricompilare.

Il risultato di quella compilazione, però, è sempre lo stesso: un'**immagine** Docker (`docs/44`, parte B). Allora la si
costruisce **una volta sola**, sulla CI di GitHub, e la si **pubblica**. Chi installa la scarica già pronta, come
scarica `postgres:18-alpine`.

### A2. Il registro: GitHub Container Registry

Un **registro** è un "negozio" di immagini Docker: ci si pubblica (*push*) e ci si scarica (*pull*). Docker Hub è il
più famoso (da lì arrivano `postgres` e `nginx`). GitHub ne ha uno suo, **GitHub Container Registry**, all'indirizzo
`ghcr.io`. È gratuito per i repository pubblici, e la CI ci può pubblicare senza password in più (con il gettone
`GITHUB_TOKEN`, parte D4).

Il nome completo di un'immagine ha tre parti:

```
ghcr.io / rosemgo/wallettracker-backend : 1.0.0
registro   nome (proprietario/immagine)    tag
```

Il **tag** dell'immagine è la sua etichetta di versione: `1.0.0`, `1.1.0`… e `latest`, "l'ultima", che si sposta a ogni
versione nuova. Noi nel `docker-compose.yml` usiamo sempre il numero preciso, mai `latest`: così si sa sempre che cosa
gira, e un aggiornamento avviene solo quando lo si chiede.

### A3. Il numero di versione: MAGGIORE.MINORE.CORREZIONE

Si usa la regola più diffusa, il **versionamento semantico** (*semantic versioning*):
- `1.0.0` → `1.0.1`: una **correzione**, niente di nuovo;
- `1.0.0` → `1.1.0`: **qualcosa di nuovo**, e tutto quello che c'era funziona come prima;
- `1.0.0` → `2.0.0`: un **cambiamento importante** per chi usa l'app (per esempio qualcosa da fare a mano
  nell'aggiornamento).

### A4. Il tag di Git e la release di GitHub

Due cose con nomi simili, da non confondere con il tag dell'immagine:
- un **tag di Git** è un'etichetta messa su un commit: "questo commit è la versione 1.0.0". Si scrive `v1.0.0`;
- una **release di GitHub** è una pagina del repository legata a un tag, con un titolo, una descrizione (le novità) e
  dei file da scaricare. La nostra contiene lo **ZIP** per chi installa e la sua **impronta** (`SHA256SUMS`).

Il gesto per pubblicare una versione è uno solo: **creare il tag di Git**. Il resto lo fa la CI (parte D).

### A5. Sviluppare e pubblicare sono due cose separate

Dopo questo passo puoi fare tutti i commit e i push che vuoi: **nessun utente riceve niente**. Solo quando crei un tag
nasce una versione, e solo allora "Aggiorna" (passo 6b) la propone. Se una versione ha un problema, se ne pubblica
un'altra (`1.0.1`) con la correzione.

---

## Parte B: il numero di versione nell'app

### B1. Dal Dockerfile al backend

Nel Dockerfile del backend, nell'immagine finale:

```dockerfile
ARG WALLETTRACKER_VERSION=sviluppo
ENV WALLETTRACKER_VERSION=$WALLETTRACKER_VERSION
```

- **`ARG`** è un valore che si passa **mentre si costruisce** l'immagine (`docker build --build-arg
  WALLETTRACKER_VERSION=1.0.0`). Se nessuno lo passa, vale `sviluppo`;
- **`ENV`** lo trasforma in una **variabile d'ambiente** dell'immagine: c'è ogni volta che il container parte.

In `application.yml`:

```yaml
wallettracker:
  version: ${WALLETTRACKER_VERSION:sviluppo}
```

e `VersionController` la restituisce:

```java
@GetMapping("/api/version")
public Version version() {
    return version; // {"version": "1.0.0"}, oppure "sviluppo"
}
```

La pagina **Impostazioni**, in fondo, alla voce **Informazioni**, la mostra: "WalletTracker, versione 1.0.0", oppure
"versione di sviluppo" per un'app costruita dal codice. Serve per sapere che cosa hai installato, e per chiedere aiuto.

### B2. Le etichette dell'immagine

```dockerfile
LABEL org.opencontainers.image.title="WalletTracker backend" \
      org.opencontainers.image.version="$WALLETTRACKER_VERSION" \
      org.opencontainers.image.source="https://github.com/rosemgo/WalletTracker"
```

Le **etichette** (*label*) sono informazioni scritte nell'immagine. Queste sono quelle **standard** (OCI, *Open Container
Initiative*): il titolo, la versione e il repository del codice. GitHub usa `source` per collegare l'immagine al
repository. Per leggerle: `docker inspect ghcr.io/rosemgo/wallettracker-backend:1.0.0`.

---

## Parte C: immagini già pronte o costruite dal codice

### C1. Un solo `docker-compose.yml`, due modi

In `docker-compose.yml`, al posto di `build: ./backend`, ora c'è:

```yaml
image: ghcr.io/rosemgo/wallettracker-backend:${WALLETTRACKER_VERSION:-sviluppo}
```

e lo stesso per `web` e `backup`. Il file nuovo **`docker-compose.build.yml`** aggiunge solo i `build:`:

```yaml
services:
  backend:
    build: ./backend
  web:
    build: ./frontend
  backup:
    build: ./docker/backup
```

Docker Compose **unisce** i file che gli si danno con `-f` (come con `docker-compose.dev.yml`, `docs/44` parte E):
- **solo `docker-compose.yml`**: le immagini si **scaricano** dal registro (`docker compose pull`);
- **`-f docker-compose.yml -f docker-compose.build.yml`**: c'è `build` accanto a `image`, e Compose **costruisce**
  l'immagine dal codice e le dà quel nome (`ghcr.io/rosemgo/wallettracker-backend:sviluppo`). Resta sul tuo computer:
  non si pubblica mai.

### C2. Lo script riconosce da solo dove si trova

All'inizio di `installa.sh` (parte `0.`) e di `installa.ps1`:

```bash
if [ -f backend/pom.xml ]; then
  DAL_CODICE=true
  WALLETTRACKER_VERSION=sviluppo
else
  DAL_CODICE=false
  WALLETTRACKER_VERSION=$(tr -d ' \r\n' <VERSION 2>/dev/null || true)
  ...
fi
export WALLETTRACKER_VERSION
```

- **c'è il codice** (`backend/pom.xml`): è il progetto clonato con Git, il **tuo** caso. Si costruisce dal codice, come
  prima;
- **non c'è il codice**: è lo ZIP di una versione, che contiene il file **`VERSION`** (per esempio `1.0.0`). Si
  scaricano le immagini di quella versione;
- **`export`**: la variabile deve arrivare a `docker compose`, che la usa nei nomi delle immagini.

Poi, all'avvio:

| Caso | Comando |
|---|---|
| dal codice | `docker compose -f docker-compose.yml -f docker-compose.build.yml up -d --build` |
| versione scaricata | `docker compose pull`, poi `docker compose up -d` |

**Senza Internet.** Se `docker compose pull` non riesce (niente rete), ma le immagini di quella versione ci sono già
sul computer (le ha scaricate un avvio precedente), lo script va avanti con quelle e lo dice ("non ho potuto
controllare le immagini su Internet"). L'app non ha bisogno di Internet per partire; solo per leggere le banche.

### C3. Che cosa cambia per te

Niente, se usi lo script: sul tuo PC trova il codice e costruisce come sempre. Cambia solo un comando scritto a mano:
per costruire dal codice senza lo script, ora serve anche `-f docker-compose.build.yml` (il README è aggiornato). Un
`docker compose up -d --build` da solo proverebbe a **scaricare** l'immagine `…:sviluppo`, che non esiste nel registro.

---

## Parte D: pubblicare una versione: `rilascio.yml`

### D1. Quando parte

```yaml
on:
  push:
    tags: ['v[0-9]+.[0-9]+.[0-9]+']
    branches: ['**']
    paths:
      - 'backend/Dockerfile'
      - 'frontend/Dockerfile'
      - 'docker/backup/**'
      - '.github/workflows/rilascio.yml'
```

- **un tag** come `v1.0.0`: si pubblica una versione. Le parentesi `[0-9]+` vogliono dire "una o più cifre": `v1.0`
  o `v1.0.0-prova` non fanno partire niente;
- **un push su un branch che cambia un Dockerfile** (o questo file): le immagini si **costruiscono** per provarle,
  ma non si pubblicano. Così un Dockerfile rotto si scopre subito, non il giorno della versione.

E in `ci.yml` due righe nuove: `branches: ['**']` (la CI normale non parte per i tag: ci pensa `rilascio.yml`) e
`workflow_call:`, che permette a `rilascio.yml` di **chiamarla**.

### D2. Primo: tutti i test

```yaml
ci:
  if: startsWith(github.ref, 'refs/tags/v')
  uses: ./.github/workflows/ci.yml
```

Con `uses:` un job esegue un altro workflow intero: la CI di sempre (backend, frontend, installazione con Docker). Se un
test fallisce, i job dopo non partono: **non si pubblica niente**.

### D3. Le immagini, per due processori

Il job `immagini` è una **matrix**: lo stesso job tre volte, in parallelo, una per immagine:

```yaml
strategy:
  matrix:
    include:
      - { nome: backend, cartella: backend }
      - { nome: web, cartella: frontend }
      - { nome: backup, cartella: docker/backup }
```

I computer hanno due famiglie di processori: **amd64** (quasi tutti i PC Windows e Linux) e **arm64** (i Mac con chip
Apple M1, M2…, e i Raspberry Pi). Un'immagine è fatta per una famiglia; ne facciamo due, sotto lo stesso nome, e Docker
sceglie da solo quella giusta per il computer che la scarica.

- **`docker/setup-qemu-action`**: QEMU è un **emulatore**, fa finta di essere un processore arm64 sulla macchina della
  CI (amd64). Serve per i `RUN` dell'immagine finale (creare l'utente, installare OpenSSL);
- **`--platform=$BUILDPLATFORM`** nei Dockerfile: la **compilazione** (Maven, npm) gira invece sul processore vero della
  macchina, perché il jar e i file di Angular sono uguali per tutti. Emulare Maven sarebbe lentissimo;
- **`docker/build-push-action`**: costruisce per `linux/amd64,linux/arm64`, passa `WALLETTRACKER_VERSION` e, con il tag,
  pubblica `…:1.0.0` e `…:latest`;
- **la cache** (`type=gha`): la volta dopo le dipendenze già scaricate si riusano.

### D4. Il permesso di pubblicare

```yaml
permissions:
  contents: read
  packages: write
```

La CI ha di serie solo il permesso di leggere (`ci.yml`). Questo job chiede **in più** di scrivere i "pacchetti"
(le immagini), solo per sé. La password è `secrets.GITHUB_TOKEN`: un gettone che GitHub crea per ogni esecuzione e che
scade alla fine. Nessuna password da salvare.

### D5. La release

Il job `release` (solo con il tag, e solo se le tre immagini sono pubblicate) chiede `contents: write` e:
1. lancia **`.github/ci/prepara-zip.sh 1.0.0`**, che:
   - prende da `NOVITA.md` la sezione `## 1.0.0` (se manca, si ferma: niente versione senza novità);
   - prepara la cartella `WalletTracker-1.0.0/` con gli script, `.env.example`, `NOVITA.md`, il file `VERSION` e
     `docker-compose.yml`, in cui `:-sviluppo}` diventa `:-1.0.0}` (così anche un `docker compose up -d` scritto a mano
     prende le immagini giuste);
   - la comprime nello ZIP e ne scrive l'impronta SHA-256 in `SHA256SUMS`;
2. crea la release con **`gh release create`** (il programma di GitHub, già installato sulle macchine della CI): lo ZIP,
   `SHA256SUMS`, e le novità come descrizione.

Lo ZIP **non contiene codice**: solo quello che serve a installare. Lo script si può provare anche a mano (esercizio 2).

---

## Parte E: la prima versione, passo per passo

Quando sei pronto a pubblicare la 1.0.0 (dopo aver provato l'app):

1. **Le novità.** Controlla la sezione `## 1.0.0` di `NOVITA.md` (c'è già una prima stesura).
2. **Il tag**, dal tuo PC, sul branch che vuoi pubblicare (il codice deve essere quello giusto, e la CI verde):

   ```
   git tag -a v1.0.0 -m "WalletTracker 1.0.0"
   git push origin v1.0.0
   ```

   `-a` crea un tag "annotato", con un messaggio, l'autore e la data.
3. **GitHub, scheda Actions:** parte "Rilascio". Dura 15-25 minuti (la CI completa, poi le immagini per due
   processori). Alla fine tutti i job devono essere verdi.
4. **Le immagini devono essere pubbliche.** La prima volta che si pubblica un'immagine, GitHub può crearla **privata**,
   anche se il repository è pubblico. Controlla: sul tuo profilo GitHub → **Packages** → `wallettracker-backend` →
   **Package settings** → in fondo, **Change visibility** → **Public**. Lo stesso per `wallettracker-web` e
   `wallettracker-backup`. Basta farlo una volta: le versioni dopo restano pubbliche.
5. **La release:** nella pagina del repository, **Releases** → "WalletTracker 1.0.0", con lo ZIP e `SHA256SUMS`.
6. **La prova vera:** in una cartella nuova, apri lo ZIP e lancia lo script. Deve dire "Avvio di WalletTracker 1.0.0"
   e scaricare le immagini; in Impostazioni → Informazioni, "versione 1.0.0".

> Il tag lo puoi creare tu, oppure lo creo io quando me lo dici: pubblicare una versione è un gesto "verso fuori"
> (chiunque la vede e la può scaricare), per questo non lo faccio da solo.

---

## Parte F: la sicurezza di questo passo

| Protezione | Contro che cosa |
|---|---|
| le immagini le costruisce **solo la CI**, dal codice del tag, e **solo dopo tutti i test** | un'immagine costruita a mano, da codice diverso o non provato |
| nelle immagini **niente di personale**: si costruiscono dalle cartelle `backend`, `frontend`, `docker/backup`, dove non ci sono né il `.env`, né la chiave, né i dati | segreti pubblicati su Internet per sbaglio |
| `GITHUB_TOKEN` con i **permessi minimi**, per job (`packages: write` solo per le immagini, `contents: write` solo per la release) | un passo della CI compromesso che fa più di quello che deve |
| lo ZIP con la sua **impronta SHA-256** | uno ZIP rovinato o sostituito: "Aggiorna" (6b) la controllerà prima di usarlo |
| nel `docker-compose.yml` sempre il **numero preciso** della versione, mai `latest` | un aggiornamento che arriva da solo, senza che tu lo sappia |

È la riga **48** della tabella della sicurezza (`docs/17`).

**Un consiglio per dopo:** nelle impostazioni del repository, **Rules** → **Rulesets**, si può dire che i tag `v*` li
possa creare solo tu. Con un solo sviluppatore non cambia niente; con altri, impedisce che qualcuno pubblichi una
versione al posto tuo.

---

## Parte G: verifiche fatte e difetti trovati

**Fatto, qui nel cloud:**
- **test**: backend (con `VersionControllerTest`) e frontend (256, con la versione nelle Impostazioni); Prettier e build;
- **dal codice**: tutti i comandi della CI del job Docker, da zero: lo script dice "Avvio di WalletTracker dal codice", le
  immagini si chiamano `ghcr.io/rosemgo/wallettracker-…:sviluppo`, `GET /api/version` risponde `sviluppo`; tutti i passi
  passano;
- **da una versione scaricata**: ho costruito le tre immagini `1.0.0` come la CI (con `--build-arg`), preparato lo ZIP
  con `prepara-zip.sh`, e installato da una cartella con **solo** lo ZIP aperto. Lo script ha detto "Avvio di
  WalletTracker 1.0.0"; non potendo scaricare le immagini (la versione 1.0.0 non esiste ancora su GitHub) ha usato quelle
  presenti, con l'avviso; l'app è partita con le immagini `1.0.0`, e le Impostazioni dicono "versione 1.0.0";
- le **etichette** delle immagini (versione e repository), e i due file della CI letti come YAML validi.

**Non fatto, perché qui non si può:**
- `rilascio.yml` **sulla CI vera**: lo vedrai partire al primo push di questi file (le immagini si costruiscono, senza
  pubblicarle), e davvero con il primo tag (parte E). Da questa sessione non posso leggere i risultati di GitHub Actions:
  controllali nella scheda Actions;
- le immagini **arm64**: qui ho costruito solo quelle amd64;
- `installa.ps1`: come nel passo 5, qui non posso eseguire PowerShell. Lo proverai nel 6d.

**Difetti trovati (e corretti):**
- `prepara-zip.sh` lanciato a mano con una cartella di uscita **che non esisteva** si fermava con "No such file or
  directory"; e una cartella **relativa** (`prova`) finiva nella radice del progetto invece che accanto a chi l'aveva
  lanciato. Nella CI non succedeva (la cartella è `.`), ma l'esercizio 2 l'avrebbe incontrato. Ora lo script crea la
  cartella e la rende assoluta prima di spostarsi.

---

## Parte H: problemi comuni

| Che cosa vedi | Perché | Che cosa fare |
|---|---|---|
| "Manca il file VERSION" | lo script è stato lanciato in una cartella senza codice e senza VERSION | scarica lo ZIP dalla pagina delle release e lancialo da lì |
| `docker compose up -d --build` scritto a mano: "pull access denied … :sviluppo" | senza `docker-compose.build.yml` Compose cerca l'immagine nel registro | `docker compose -f docker-compose.yml -f docker-compose.build.yml up -d --build`, o lo script |
| "Non riesco a scaricare le immagini della versione …" | niente Internet, e le immagini non ci sono ancora | collegati a Internet e rilancia |
| dallo ZIP: "denied" o "unauthorized" scaricando le immagini | le immagini su GitHub sono ancora private | parte E, punto 4 |
| "Rilascio" rosso nella scheda Actions | un test, o un'immagine, non è andato | apri il job rosso e leggi il primo errore: niente è stato pubblicato |
| "In NOVITA.md manca la sezione ## 1.0.0" | si è creato il tag senza scrivere le novità | aggiungi la sezione, poi crea un tag nuovo (per esempio `v1.0.1`) |

---

## Esercizi guidati

1. **Leggere un'immagine.** Dopo aver lanciato lo script dal codice: `docker inspect ghcr.io/rosemgo/wallettracker-backend:sviluppo`.
   Trova le etichette (`Labels`) e la variabile `WALLETTRACKER_VERSION` (`Env`). Che valore ha, e perché?
2. **Lo ZIP a mano.** Lancia `.github/ci/prepara-zip.sh 9.9.9 /tmp/prova`. Che errore dà, e perché? Poi aggiungi in
   `NOVITA.md` una sezione `## 9.9.9`, rilancialo, e guarda dentro lo ZIP: che cosa è cambiato in `docker-compose.yml`?
   (Ricordati di togliere la sezione.)
3. **Unire i file di Compose.** Confronta `docker compose config` con
   `docker compose -f docker-compose.yml -f docker-compose.build.yml config`: che cosa compare nel secondo?
4. **Il processore.** Su un Mac con chip Apple, `docker image inspect --format '{{.Architecture}}' postgres:18-alpine`
   dice `arm64`; su un PC Windows `amd64`. Perché lo stesso nome dà due immagini diverse?
5. **Versione semantica.** Per ognuna, scegli il numero dopo la `1.4.2`: una correzione a un grafico; una pagina nuova;
   un cambiamento che obbliga a rifare la configurazione di Tailscale.
