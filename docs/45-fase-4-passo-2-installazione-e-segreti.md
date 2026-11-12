# 45. Fase 4, passo 2: lo script di installazione e la password del database

**Obiettivo di questo passo:** chi scarica WalletTracker lancia **un solo script**, e lo script fa tutto il resto:
- crea il `.env` con una **password del database generata a caso**;
- prepara le cartelle;
- controlla che Docker sia avviato;
- costruisce e avvia i container;
- aspetta che l'app sia pronta e la apre nel browser.

Lo stesso script sa anche **cambiare la password** di un database già creato: l'operazione delicata di cui
parlava `docs/44`, parte E2.

| Passo | Che cosa | Stato |
|---|---|---|
| 1 | tre container + Ollama facoltativo, nginx (`docs/44`) | fatto |
| **2** | **lo script di installazione, la password del database** | **questa guida** |
| 3 | il PIN per aprire l'app (Spring Security) | da fare |
| 4 | Tailscale: l'app dal telefono con https vero, redirect di Enable Banking | da fare |
| 5 | backup e ripristino, provato davvero | da fare |
| 6 | aggiornamenti, la guida per chi non è informatico, il doppio clic | da fare |

> Prerequisiti: il passo 1 (`docs/44`) e `git pull`. Se hai già l'app installata, leggi prima la **parte G**.

**Indice**
- Parte A: perché uno script
- Parte B: che cosa serve prima (Docker Desktop)
- Parte C: lo script, passo per passo
- Parte D: la password del database generata a caso
- Parte E: cambiare la password di un database che esiste già
- Parte F: Linux e l'utente 1000
- Parte G: per chi ha già l'app installata (anche tu)
- Parte H: dentro gli script (Bash e PowerShell)
- Parte I: la sicurezza di questo passo
- Parte J: la CI installa l'app a ogni push
- Parte K: prova
- Parte L: verifiche fatte e difetti trovati
- Parte M: problemi comuni
- Esercizi guidati

---

## Parte A: perché uno script

Dopo il passo 1, per installare l'app bisognava:
1. copiare `.env.example` in `.env`;
2. **inventare una password** per il database e scriverla nel `.env`;
3. su Linux, creare la cartella `.data` con il proprietario giusto;
4. lanciare `docker compose up -d --build`;
5. aspettare, senza sapere quanto, che il backend fosse pronto;
6. aprire `http://localhost:8000`.

Sono passaggi facili da sbagliare, soprattutto il secondo: chi non è informatico lascia la password d'esempio
(`cambia-questa-password`) o ne sceglie una corta. Lo script li fa tutti, sempre nello stesso modo, e **si può
rilanciare quante volte si vuole**: quello che c'è già (il `.env`, la chiave di cifratura, i dati) non lo tocca.
Per questo è anche il comando di tutti i giorni per avviare l'app.

Gli script sono due, uno per sistema, e fanno le stesse cose nello stesso ordine:

| Sistema | Script | Linguaggio |
|---|---|---|
| Windows | `installa.ps1` | PowerShell, che c'è già in ogni Windows |
| macOS e Linux | `installa.sh` | Bash, che c'è già nel terminale |

---

## Parte B: che cosa serve prima (Docker Desktop)

Lo script non installa Docker: controlla solo che ci sia e che sia avviato.

**Windows 10/11:**
1. scarica **Docker Desktop** dal sito di Docker (docker.com → "Download Docker Desktop" → Windows);
2. installalo lasciando spuntata l'opzione **"Use WSL 2"** (WSL è il "Linux dentro Windows" su cui gira Docker).
   Se Windows lo chiede, riavvia il PC;
3. apri Docker Desktop e aspetta che in basso a sinistra compaia **"Engine running"** (motore avviato) su fondo
   verde;
4. consigliato: in **Settings → General** spunta **"Start Docker Desktop when you sign in"**, così Docker parte da
   solo quando accendi il PC, e con lui l'app (`restart: unless-stopped`, `docs/44`).

**macOS:** come sopra, scegliendo la versione per il tuo processore (Apple Silicon o Intel).

**Linux:** Docker Engine con il plugin Compose, dal pacchetto della tua distribuzione o dal sito di Docker.

---

## Parte C: lo script, passo per passo

### C1. Come si lancia

**Windows**, da PowerShell, nella cartella del progetto:

```powershell
powershell -ExecutionPolicy Bypass -File installa.ps1
```

`-ExecutionPolicy Bypass` serve perché Windows, di serie, **non esegue gli script** (la "politica di
esecuzione" è *Restricted*): è una protezione contro gli script scaricati per sbaglio. `Bypass` dà il permesso
**solo a questo comando**, senza cambiare le impostazioni del PC. Al passo 6 arriverà un file da aprire con un
doppio clic, che fa la stessa cosa.

**macOS e Linux**, dal terminale, nella cartella del progetto:

```bash
./installa.sh
```

`./` vuol dire "lo script che sta in questa cartella". Il file è già "eseguibile" (il permesso `x`, salvato anche
in Git); se il sistema dice *permission denied*, `chmod +x installa.sh` lo rende tale.

**Le opzioni:**

| Windows | macOS / Linux | Che cosa fa |
|---|---|---|
| `-Ia` | `--ia` | avvia anche Ollama (il profilo `ia`, `docs/44` parte G) |
| `-CambiaPasswordDb` | `--cambia-password-db` | genera una password nuova per il database (parte E) |
| `-ReimpostaPin` | `--reimposta-pin` | PIN dimenticato: lo azzera e chiude le sessioni aperte (dal passo 3, `docs/46` parte G) |
| `-SenzaBrowser` | `--senza-browser` | non apre il browser alla fine (lo usa la CI) |
| | `--aiuto` | mostra le opzioni |

### C2. Che cosa fa, in ordine

```
 1. Docker c'è ed è avviato?            no → "avvia Docker Desktop e riprova", e si ferma
 2. c'è il .env?                        no → lo crea da .env.example, con una password a caso
                                        sì → lo lascia com'è (avvisa se la password è quella d'esempio)
 3. le cartelle .data e secrets         le crea se mancano (su Linux, con il proprietario giusto: parte F)
 4. --cambia-password-db?               sì → password nuova nel database, poi nel .env (parte E)
 5. docker compose up -d --build        costruisce le immagini (se serve) e avvia i container
    poi aspetta il backend              finché il controllo di salute dice "healthy" (al massimo 4 minuti)
 6. apre http://localhost:8000          (o la porta di WEB_PORT)
```

Ecco che cosa si vede al primo avvio:

```
==> Controllo Docker
Docker è avviato.

==> Configurazione (.env)
Creato il file .env, con una password del database generata a caso.

==> Avvio di WalletTracker (la prima volta ci vuole qualche minuto: si scaricano e si compilano le immagini)
 ... i messaggi di Docker ...

==> Aspetto che il backend sia pronto
Il backend è pronto.

==> WalletTracker è avviato: http://localhost:8000
Per fermarlo:  docker compose down   (i dati restano)
```

**Perché aspettare il backend.** `docker compose up -d` torna subito, appena i container sono **partiti**, ma il
backend ha bisogno di qualche decina di secondi (Spring Boot, Flyway) prima di **rispondere**. Aprendo il browser
subito, si vedrebbe un errore 502. Lo script chiede a Docker lo stato del controllo di salute (`healthcheck` nel
`docker-compose.yml`, `docs/44` parte C3):

```bash
docker inspect --format '{{.State.Health.Status}}' wallettracker-backend
```

e riprova ogni 3 secondi finché la risposta è `healthy`. Se dopo 4 minuti non lo è, si ferma e dice quale
comando usare per leggere i log.

---

## Parte D: la password del database generata a caso

### D1. Come la genera

```bash
LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32
```

- `/dev/urandom` è il **generatore casuale del sistema operativo**: quello che usano anche i programmi di
  crittografia, non un `Math.random()` qualsiasi;
- `tr -dc 'A-Za-z0-9'` tiene solo lettere e cifre e scarta il resto;
- `head -c 32` prende i primi 32 caratteri.

In PowerShell si usa l'equivalente di Windows, `System.Security.Cryptography.RandomNumberGenerator`.

**Perché solo lettere e cifre.** Caratteri come `$`, `#`, `'`, `"` o lo spazio hanno un significato speciale nel
`.env`, negli URL o in SQL, e andrebbero protetti con virgolette o "escape": una fonte di errori. Con solo lettere e
cifre la password si scrive ovunque così com'è.

**È abbastanza sicura?** 32 caratteri scelti tra 62 sono 62³² combinazioni, circa 2¹⁹⁰ (190 bit). Per confronto,
le chiavi AES che cifrano i segreti nel database sono di 256 bit, e 128 bit sono già considerati impossibili da
indovinare provando. Non serve nemmeno ricordarla: la legge Docker Compose dal `.env`.

### D2. Dove va, e chi la usa

Nel `.env`, alla riga `POSTGRES_PASSWORD=`. Da lì Docker Compose la consegna a **due** container
(`docs/44`, parte E2): al `db`, che la usa per creare l'utente del database al primo avvio (la "serratura"), e al
`backend`, che la usa per entrare (la "chiave").

Sul PC il `.env` è leggibile **solo dal tuo utente**: lo script gli dà i permessi `600` su macOS e Linux (lettura
e scrittura per il proprietario, niente per gli altri). Su Windows i file nella tua cartella utente sono già
protetti così.

### D3. Senza password, Compose non parte

Nel `docker-compose.yml`:

```yaml
POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?manca POSTGRES_PASSWORD nel .env - avvia installa.ps1 o installa.sh (docs/45)}
```

`${NOME:?messaggio}` vuol dire: "se NOME manca o è vuoto, **fermati** con questo messaggio". Prima, con una
password vuota, i container partivano, e il database si fermava subito dopo con un errore in inglese, visibile solo
nei suoi log ("superuser password is not specified"). Ora Compose dice subito, prima di avviare qualsiasi cosa,
che cosa manca e che cosa fare:

```
error while interpolating services.db.environment.POSTGRES_PASSWORD: required variable POSTGRES_PASSWORD
is missing a value: manca POSTGRES_PASSWORD nel .env - avvia installa.ps1 o installa.sh (docs/45)
```

Per lo stesso motivo in `.env.example` la password è **vuota**: niente password d'esempio da dimenticare.

### D4. Un limite da conoscere

Le variabili d'ambiente di un container si vedono con `docker inspect wallettracker-backend`. Chi può usare Docker
sul tuo PC può quindi leggere la password. Non è un problema in più: chi può usare Docker può comunque fare
qualsiasi cosa con i container, anche entrare nel database. L'alternativa più rigorosa sono i *Docker secrets*
(la password in un file montato, `POSTGRES_PASSWORD_FILE`): un esercizio, alla fine.

---

## Parte E: cambiare la password di un database che esiste già

### E1. Perché non basta cambiarla nel `.env`

La password vive in **due posti** (`docs/44`, parte E2):
- **dentro il database**, scritta da PostgreSQL **al primo avvio**, quando il volume era vuoto;
- **nel `.env`**, da cui Compose la passa al backend.

Se cambi solo il `.env`, il backend riceve la password nuova, ma il database tiene la vecchia, e il backend non
entra più: `password authentication failed`. Il database va avvisato con un comando SQL:

```sql
ALTER USER "wallettracker" PASSWORD 'la-password-nuova';
```

### E2. Che cosa fa `--cambia-password-db`

```
 1. avvia (o lascia acceso) il solo database, e aspetta che risponda
 2. genera una password nuova
 3. la imposta DENTRO il database        ← se fallisce, si ferma qui: il .env è ancora quello vecchio,
                                            e tutto continua a funzionare come prima
 4. la scrive nel .env                    ← solo dopo che il database l'ha accettata
 5. docker compose up -d                  ← Compose vede che la variabile del backend è cambiata e lo ricrea:
                                            il backend riparte con la password nuova
```

L'**ordine** è la parte importante. Al contrario (prima il `.env`, poi il database), un errore a metà lascerebbe
il backend con una password che il database non conosce.

### E3. Come fa lo script a entrare nel database senza la password?

Il comando gira **dentro** il container del database:

```bash
docker compose exec -T db psql -U wallettracker -d wallettracker -c "ALTER USER ..."
```

PostgreSQL decide chi può entrare con il file `pg_hba.conf` (*host-based authentication*). Nell'immagine ufficiale
contiene, in sostanza:

```
local   all   all                  trust           ← dall'interno del container: fidato, senza password
host    all   all   127.0.0.1/32   trust           ← idem, sempre dall'interno
host    all   all   all            scram-sha-256   ← da qualunque altro posto (il backend): con la password
```

Chi è **dentro** il container del database ha già il controllo dei suoi file, quindi una password lì non
proteggerebbe niente; chi arriva **dalla rete** (il backend, o un altro container) deve darla. E per entrare nel
container serve poter usare Docker sul PC.

(`scram-sha-256` è il modo moderno in cui PostgreSQL controlla le password: la password non viaggia mai in
chiaro, nemmeno nella rete di Docker.)

### E4. Come si usa

```powershell
powershell -ExecutionPolicy Bypass -File installa.ps1 -CambiaPasswordDb     # Windows
```
```bash
./installa.sh --cambia-password-db                                          # macOS / Linux
```

I dati restano tutti: cambia solo la password dell'utente del database.

---

## Parte F: Linux e l'utente 1000

Nel container il backend è l'utente con **id 1000** (`backend/Dockerfile`, `docs/44` parte B2), e nella cartella
`.data` del PC deve scrivere la chiave di cifratura. Su Linux conta il **numero** del proprietario della cartella:

| Chi lancia lo script | Proprietario di `.data` | Che cosa fa lo script |
|---|---|---|
| il primo utente del PC (id 1000) | 1000 | niente: va già bene |
| root, o con `sudo` | 0 (root) | gliela dà all'utente 1000 (`chown 1000:1000 .data`) |
| un altro utente (es. id 1001) | 1001 | si ferma e spiega: `sudo chown 1000:1000 .data`, poi rilancia |

Su **macOS e Windows** Docker Desktop traduce i permessi da solo, e lo script non controlla niente.

Questo controllo l'abbiamo aggiunto **dopo averlo visto fallire** (parte L): nel primo tentativo la cartella era
di root, e il backend si riavviava all'infinito con "Impossibile leggere o creare la chiave di cifratura".

---

## Parte G: per chi ha già l'app installata (anche tu)

Hai già un `.env` (dalle fasi precedenti) e un database con i tuoi dati. Lo script li rispetta:

1. ferma il backend avviato a mano e `npm start`, se sono accesi (`docs/44`, parte E: due backend leggerebbero
   due volte le banche);
2. `git pull`;
3. lancia lo script:

   ```powershell
   powershell -ExecutionPolicy Bypass -File installa.ps1
   ```

   Dirà "Il file .env c'è già: lo uso così com'è", e avvierà l'app con i tuoi dati di sempre;
4. se compare

   ```
   ATTENZIONE: la password del database è quella d'esempio, o è più corta di 16 caratteri.
   ```

   la tua password è `cambia-questa-password` (o una corta). Cambiala con una generata a caso:

   ```powershell
   powershell -ExecutionPolicy Bypass -File installa.ps1 -CambiaPasswordDb
   ```

**Se avvii ancora il backend a mano** (`./mvnw spring-boot:run`, per sviluppare): legge la password dallo stesso
`.env`, quindi dopo il cambio funziona anche lui, senza fare niente.

**Una cosa che non cambia:** il collegamento di una banca nuova dall'app nei container aspetta ancora il passo 4
(`docs/44`, parte H3).

---

## Parte H: dentro gli script

I due script sono commentati riga per riga. Qui le idee che valgono la pena.

### H1. Bash (`installa.sh`)

```bash
#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")"
```

- **`#!/usr/bin/env bash`** (lo *shebang*): la prima riga dice al sistema con quale programma eseguire il file;
- **`set -e`**: se un comando fallisce, lo script si ferma, invece di andare avanti facendo danni. **`set -u`**:
  usare una variabile mai assegnata è un errore (di solito è un nome scritto male);
- **`cd "$(dirname "$0")"`**: `$0` è il percorso dello script; ci si sposta nella sua cartella, così funziona da
  qualunque cartella lo si lanci.

**Scrivere il `.env` senza rischi.** Per sostituire la riga della password:

```bash
awk -v nome="$1" -v valore="$2" '... sostituisce la riga NOME=... ...' .env >.env.nuovo
chmod 600 .env.nuovo
mv .env.nuovo .env
```

Si scrive **un file nuovo** e poi lo si mette al posto del vecchio con `mv`. `mv` nella stessa cartella è
"atomico": o è fatto tutto, o niente. Se lo script si interrompe a metà, il `.env` vecchio resta intero. Lo
stesso trucco lo usano i database e gli editor di testo.

(`sed -i`, il modo più comune per modificare un file sul posto, si scrive in modo diverso su Linux e su macOS:
`awk` funziona uguale su tutti e due.)

**Le fini riga.** Il file `.gitattributes` dice a Git di tenere `installa.sh` sempre con le fini riga di Unix
(`LF`). Su Windows Git, di solito, le trasforma in quelle di Windows (`CRLF`), e uno script Bash con i `CRLF` non
parte ("bad interpreter"). Questo conta se un giorno lo lanci da WSL.

### H2. PowerShell (`installa.ps1`)

```powershell
param(
    [switch]$Ia,
    [switch]$CambiaPasswordDb,
    [switch]$SenzaBrowser
)
```

`param` dichiara le opzioni dello script; `[switch]` è un'opzione sì/no: `-Ia` presente vuol dire `$Ia` vero.

**Gli errori dei programmi esterni.** In PowerShell i programmi come `docker` non "lanciano eccezioni": dicono
com'è andata con il **codice di uscita** (0 = tutto bene), che PowerShell mette in `$LASTEXITCODE`:

```powershell
& docker info *> $null
if ($LASTEXITCODE -ne 0) { Errore 'Docker non risponde: avvia Docker Desktop ...' }
```

`&` esegue un programma, `*> $null` butta via tutto quello che scrive. Lo script usa
`$ErrorActionPreference = 'Continue'`: con `'Stop'`, Windows PowerShell tratterebbe come un errore qualsiasi riga
che `docker` scrive sullo "standard error", anche i normali messaggi di avanzamento.

**Due trappole delle codifiche, entrambe evitate:**
1. **Il `.env` senza BOM.** Windows PowerShell, scrivendo un file in UTF-8, aggiunge all'inizio tre byte
   invisibili, il **BOM** (*byte order mark*). Docker Compose li leggerebbe come parte del primo nome di
   variabile. Lo script scrive il `.env` con `[System.IO.File]::WriteAllText(..., UTF8Encoding $false)`, cioè
   UTF-8 **senza** BOM;
2. **Lo script con il BOM.** All'opposto, il file `installa.ps1` è salvato **con** il BOM: Windows PowerShell 5.1
   legge gli script senza BOM come se fossero nella vecchia codifica di Windows, e le lettere accentate dei
   messaggi ("è", "c'è già") uscirebbero come "Ã¨".

---

## Parte I: la sicurezza di questo passo

Nella tabella di `docs/17` è la riga **31**:
- password del database **generata a caso**, 32 caratteri, circa 190 bit, dal generatore crittografico del
  sistema;
- Compose **non parte** senza password;
- `.env` leggibile solo dal tuo utente (`600`);
- cambio della password **in due tempi**, prima il database e poi il `.env`;
- in `.env.example` nessuna password d'esempio.

Dalla lista delle protezioni pianificate esce la "password del database generata all'installazione": fatta.

---

## Parte J: la CI installa l'app a ogni push

Il job "Immagini Docker" del passo 1 è diventato **"Installazione con Docker"**: a ogni push, su una macchina
Linux di GitHub, fa quello che farebbe un utente nuovo.

```yaml
- name: Prepara la cartella .data
  run: mkdir .data && sudo chown 1000:1000 .data     # su GitHub l'utente ha id 1001 (parte F)
- name: Installa e avvia
  run: ./installa.sh --senza-browser
- name: Controlla che l'app risponda
  run: |
    curl -fsS http://localhost:8000/ | grep -q '<app-root>'
    curl -fsS http://localhost:8000/api/accounts
- name: Cambia la password del database e ricontrolla
  run: |
    ./installa.sh --cambia-password-db --senza-browser
    curl -fsS http://localhost:8000/api/accounts
- name: Controlla la configurazione di nginx
  run: docker compose exec -T web nginx -t
```

Se un Dockerfile, la configurazione di nginx, il `docker-compose.yml` o lo script hanno un errore, il commit
diventa rosso. `curl -f` fallisce se la risposta è un errore (4xx o 5xx). Se qualcosa va storto, l'ultimo passo
stampa i log dei container. Lo script PowerShell la CI non lo prova (Docker sulle macchine Windows di GitHub non fa
girare container Linux): l'abbiamo provato a mano (parte L).

---

## Parte K: prova

1. `git pull` e, se c'è, ferma il backend avviato a mano.
2. Lancia lo script (parte C1). La prima volta, se le immagini sono da ricostruire, ci vuole qualche minuto.
3. Si apre `http://localhost:8000` con i tuoi dati.
4. Rilancia lo script: deve dire "Il file .env c'è già: lo uso così com'è" e non cambiare niente.
5. Se compare l'avviso sulla password, prova il cambio (parte G, punto 4). L'app deve continuare a funzionare.
6. Prova l'errore: chiudi Docker Desktop (icona nella barra in basso → **Quit Docker Desktop**) e lancia lo script.
   Deve dire "Docker non risponde: avvia Docker Desktop...". Riapri Docker Desktop.
7. Per curiosità, apri il `.env` con un editor di testo e guarda la riga `POSTGRES_PASSWORD=`.

---

## Parte L: verifiche fatte e difetti trovati

Prove su una **copia nuova** del progetto (come appena scaricato), con Docker, Bash e PowerShell 7. Per le build
nel cloud è servito un inoltro verso il proxy dell'ambiente, nelle sole righe del proxy dei Dockerfile della
copia: sul tuo PC non serve.

| Prova | Esito |
|---|---|
| `docker compose` senza `.env` | ✅ si ferma con "manca POSTGRES_PASSWORD nel .env - avvia installa..." |
| primo avvio con `installa.sh`: `.env` creato, password di 32 caratteri, permessi `600`, backend `healthy`, `/api/accounts` 200 | ✅ |
| secondo avvio: "Il file .env c'è già", password invariata | ✅ |
| `.env` con `cambia-questa-password`: avviso con il comando per cambiarla | ✅ |
| `--cambia-password-db` con un conto nel database: dalla rete di Docker la vecchia password è **rifiutata**, la nuova accettata; il conto c'è ancora; l'app risponde | ✅ |
| primo avvio con `installa.ps1` (PowerShell 7): `.env` **senza BOM** e letto da Compose, app pronta | ✅ |
| `-CambiaPasswordDb` con PowerShell: vecchia rifiutata, nuova accettata, app che risponde | ✅ |
| Docker spento (simulato), con tutti e due gli script | ✅ "Docker non risponde: avvia Docker Desktop..." |
| opzione sbagliata, `--aiuto` | ✅ |
| Linux con un utente di id 1001 | ✅ si ferma con il comando `sudo chown 1000:1000 .data` |

**Difetti trovati provando, e corretti:**
1. **`.data` di root.** Lanciato come root, lo script creava `.data` di root, e il backend (utente 1000) si
   riavviava all'infinito senza poter scrivere la chiave. Ora lo script controlla il proprietario (parte F);
2. **Uno script che si fermava in silenzio.** Se `docker compose up` falliva, `installa.sh` si fermava (per
   `set -e`) senza dire niente. Ora dice "docker compose non è riuscito ad avviare l'app: leggi il messaggio qui
   sopra";
3. **Un suggerimento sbagliato.** Il messaggio "backend non pronto" consigliava `docker compose logs
   wallettracker-backend`, ma il comando vuole il nome del **servizio** (`backend`), non del container;
4. **Una prova che non provava niente.** Per controllare che la vecchia password fosse rifiutata avevo usato `psql`
   dentro il container del database, che però lì entra **senza** password (parte E3). Rifatta dalla rete di
   Docker, come si collega il backend.

**Non provato:** lo script PowerShell sul **Windows PowerShell 5.1** vero (qui c'è PowerShell 7 su Linux).
Abbiamo evitato le funzioni che la 5.1 non ha, e gestito le sue differenze note (BOM, codifica degli script,
errori dei programmi esterni). Se vedi qualcosa di strano, mandami il testo che compare.

---

## Parte M: problemi comuni

| Sintomo | Causa probabile | Che cosa fare |
|---|---|---|
| "l'esecuzione di script è disabilitata nel sistema" | hai lanciato `.\installa.ps1` direttamente | usa `powershell -ExecutionPolicy Bypass -File installa.ps1` |
| "Docker non risponde" | Docker Desktop chiuso o ancora in avvio | aprilo e aspetta "Engine running" |
| "manca POSTGRES_PASSWORD nel .env" | `.env` copiato a mano con la password vuota, e `docker compose` lanciato senza lo script | lancia lo script: la genera |
| backend non pronto, nei log `password authentication failed` | la password del `.env` è stata cambiata **a mano** | rimetti quella di prima, poi usa `-CambiaPasswordDb` / `--cambia-password-db` |
| "La cartella .data è dell'utente ..." (Linux) | il tuo utente non ha id 1000 | `sudo chown 1000:1000 .data` e rilancia |
| `permission denied: ./installa.sh` | il file non è eseguibile | `chmod +x installa.sh` |
| `bad interpreter: /usr/bin/env: bash^M` | lo script ha le fini riga di Windows | `git checkout -- installa.sh` (con il `.gitattributes` torna con quelle di Unix) |
| backend non pronto dopo 4 minuti | un errore all'avvio | `docker compose logs backend` e leggi le prime righe con `ERROR` |
| "Manca il file .env, ma c'è già il database di un'installazione precedente" | hai cancellato il `.env` (o hai scaricato il progetto in un'altra cartella), ma i dati di prima sono ancora in Docker, con la loro password | rimetti il vecchio `.env` e rilancia lo script. **Solo** se vuoi ripartire da zero, perdendo tutti i dati di prima: `docker rm -f wallettracker-backend wallettracker-web wallettracker-db` e poi `docker volume rm wallettracker_db-data` |

---

## Esercizi guidati

1. **Leggi lo script.** Apri `installa.sh` (o `installa.ps1`) e ritrova, nei commenti, i sei passi della parte C2.
2. **Una password a mano.** In un terminale macOS/Linux (o WSL, o Git Bash su Windows) prova
   `LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32; echo` più volte. Che cosa cambia se togli
   `head -c 32`? (Premi Ctrl+C per fermarlo.)
3. **Compose che si rifiuta.** Fai una copia del `.env`, svuota la riga `POSTGRES_PASSWORD=` e lancia
   `docker compose config`. Leggi il messaggio, poi rimetti il `.env` com'era.
4. **Le due serrature.** Con l'app avviata:
   `docker compose exec db psql -U wallettracker -d wallettracker -c "select 1"` entra senza password. Perché?
   (parte E3). Ora `docker compose exec db cat /var/lib/postgresql/18/docker/pg_hba.conf` e ritrova le righe
   `trust` e `scram-sha-256`.
5. **Che cosa vede `docker inspect`.** `docker inspect wallettracker-backend --format '{{json .Config.Env}}'`:
   trovi la password? Pensa a chi può lanciare questo comando sul tuo PC (parte D4).
6. **Per approfondire: i Docker secrets.** L'immagine di PostgreSQL accetta `POSTGRES_PASSWORD_FILE` al posto di
   `POSTGRES_PASSWORD`: la password sta in un file, e non compare più tra le variabili d'ambiente. Cerca nella
   documentazione di Docker Compose la sezione `secrets:`, e pensa a come dovrebbe leggerla il backend (in Spring:
   `spring.config.import: configtree:...`).
