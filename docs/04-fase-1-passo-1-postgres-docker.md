# 4. Fase 1, passo 1: PostgreSQL con Docker

**Obiettivo:** avere un database PostgreSQL che gira sul tuo PC dentro Docker, capire cosa
succede a ogni comando e collegarti al database da IntelliJ.

In questo passo non c'è codice Java: lo collegheremo a Spring Boot nel passo 2.

> Prerequisiti: Docker Desktop avviato ("Engine running" in verde) e `git pull` fatto.

---

## Parte A: i concetti di Docker

### Il problema che Docker risolve

Per usare PostgreSQL senza Docker dovresti installarlo su Windows, configurarlo, ricordarti
quale versione hai e sperare che sul server finale ci sia la stessa. Con Docker invece scarichi
un **pacchetto già pronto** e lo avvii con un comando, identico su ogni macchina: il tuo PC, il
server di casa, un VPS.

### Le quattro parole da conoscere

| Concetto | Cos'è | Analogia con Java |
|---|---|---|
| **Immagine** | un pacchetto in sola lettura con tutto il necessario: un mini sistema Linux, PostgreSQL, le sue librerie | la **classe** (o il file `.jar`) |
| **Container** | un'immagine **in esecuzione**: un processo isolato, con il suo filesystem e la sua rete | un **oggetto**: `new` di una classe; ne puoi creare tanti dalla stessa immagine |
| **Registry** | il "negozio" da cui si scaricano le immagini; quello pubblico è **Docker Hub** | Maven Central per le librerie |
| **Volume** | uno spazio su disco gestito da Docker, **fuori** dal container, dove salvare dati persistenti | un file su disco, che sopravvive alla chiusura del programma |

Il punto chiave: **un container è usa e getta**. Puoi cancellarlo e ricrearlo in un secondo.
Tutto quello che scrive al suo interno sparisce con lui, **tranne quello che sta in un volume**.
Per un database è fondamentale: i dati vanno nel volume.

### Come comunica un container

Ogni container ha la sua rete isolata. PostgreSQL al suo interno ascolta sulla porta **5432**, ma
dal tuo PC non è raggiungibile finché non **pubblichi la porta**, cioè finché non dici a Docker
"collega la porta 5432 del mio PC alla 5432 del container".

```
 Il tuo PC (Windows)                       Container "wallettracker-db" (Linux)
 ───────────────────                       ─────────────────────────────────────
 IntelliJ / Spring Boot                     PostgreSQL in ascolto sulla 5432
        │                                              ▲
        └──► localhost:5432 ──── porta pubblicata ─────┘
                                                       │
                                     dati salvati nel volume "db-data"
```

---

## Parte B: un container a mano con `docker run`

Prima facciamo tutto a mano, per vedere ogni pezzo. Poi lo stesso con Docker Compose.

### 1. Scarica l'immagine

```powershell
docker pull postgres:18-alpine
```

- `postgres` è il nome dell'immagine ufficiale su Docker Hub.
- `18-alpine` è il **tag**: la versione 18 di PostgreSQL, costruita su Alpine Linux, una
  distribuzione minimale. L'immagine è più piccola di quella standard.

Verifica con `docker images`: devi vedere `postgres` con tag `18-alpine`.

### 2. Avvia un container

Tutto su una riga (in PowerShell):

```powershell
docker run --name pg-prova -e POSTGRES_PASSWORD=prova -p 127.0.0.1:5432:5432 -d postgres:18-alpine
```

Pezzo per pezzo:

| Parte | Significato |
|---|---|
| `docker run` | crea un container da un'immagine e lo avvia |
| `--name pg-prova` | il nome del container (altrimenti Docker ne inventa uno) |
| `-e POSTGRES_PASSWORD=prova` | **variabile d'ambiente** passata al container. L'immagine di PostgreSQL la legge al primo avvio per impostare la password dell'utente `postgres` |
| `-p 127.0.0.1:5432:5432` | **pubblica la porta**: `indirizzo-sul-PC:porta-sul-PC:porta-nel-container`. Con `127.0.0.1` il database è raggiungibile solo dal tuo PC, non dagli altri dispositivi della rete |
| `-d` | *detached*: il container gira in background e il terminale resta libero |
| `postgres:18-alpine` | l'immagine da usare |

### 3. Guarda cosa succede

```powershell
docker ps                 # i container in esecuzione: deve esserci pg-prova
docker logs pg-prova      # i log di PostgreSQL: cerca "database system is ready to accept connections"
```

### 4. Entra nel database

```powershell
docker exec -it pg-prova psql -U postgres
```

- `docker exec` esegue un comando **dentro** un container già avviato;
- `-it` apre una sessione interattiva (puoi scrivere);
- `psql -U postgres` è il client a riga di comando di PostgreSQL, con l'utente `postgres`.

Ora sei dentro PostgreSQL. Prova:

```sql
SELECT version();
CREATE TABLE prova (id INT, nota TEXT);
INSERT INTO prova VALUES (1, 'ciao');
SELECT * FROM prova;
\q
```

(`\q` esce da psql.)

### 5. L'esperimento importante: cosa succede ai dati?

```powershell
docker rm -f pg-prova     # cancella il container (-f lo ferma anche se è in esecuzione)
docker run --name pg-prova -e POSTGRES_PASSWORD=prova -p 127.0.0.1:5432:5432 -d postgres:18-alpine
docker exec -it pg-prova psql -U postgres -c "SELECT * FROM prova;"
```

Risultato: `ERROR: relation "prova" does not exist`. **La tabella è sparita**, perché era scritta
dentro il container e non in un volume. È proprio quello che Docker Compose, con il volume, eviterà.

### 6. Pulizia

```powershell
docker rm -f pg-prova
```

Fallo prima di passare alla Parte C: il container occupa la porta 5432, che serve al prossimo.

---

## Parte C: lo stesso con Docker Compose

Il comando `docker run` diventa lungo e difficile da ricordare, e un'applicazione vera ha più
container (database, backend, frontend). **Docker Compose** descrive tutto in un file,
`docker-compose.yml`, versionato con il codice. Un solo comando avvia tutto.

### Il file `docker-compose.yml`, riga per riga

È nella radice del progetto:

```yaml
name: wallettracker
```
Nome del progetto. Docker lo usa come prefisso per volumi e reti (es. `wallettracker_db-data`).

```yaml
services:
  db:
```
L'elenco dei servizi, cioè dei container. Per ora ce n'è uno, chiamato `db`.

```yaml
    image: postgres:18-alpine
    container_name: wallettracker-db
```
Corrispondono a `postgres:18-alpine` e `--name` di `docker run`.

```yaml
    restart: unless-stopped
```
Se il container si ferma per un errore o Docker si riavvia, lo riavvia da solo, tranne se l'hai
fermato tu.

```yaml
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
```
Corrispondono ai `-e` di `docker run`. Al **primo avvio** PostgreSQL crea un database e un utente
con questi nomi. I valori `${...}` arrivano dal file **`.env`**, che Docker Compose legge da solo
se è nella stessa cartella: così la password non finisce su Git.

```yaml
    ports:
      - "127.0.0.1:5432:5432"
```
Corrisponde a `-p`.

```yaml
    volumes:
      - db-data:/var/lib/postgresql
```
Il pezzo che mancava nella Parte B: la cartella del container dove PostgreSQL salva i dati
(`/var/lib/postgresql`) viene collegata al volume `db-data`. I dati vivono nel volume, quindi
**sopravvivono alla cancellazione del container**.

```yaml
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 10s
      timeout: 5s
      retries: 5
```
Ogni 10 secondi Docker esegue `pg_isready`, un comando di PostgreSQL che risponde "pronto" quando
il database accetta connessioni. Lo stato diventa `healthy` o `unhealthy`. Più avanti il backend
aspetterà che il database sia `healthy` prima di partire.

```yaml
volumes:
  db-data:
```
Dichiara il volume: Docker lo crea al primo avvio.

### 1. Prepara il file `.env`

Nel `.env.example` ci sono tre nuove variabili. Copiale nel tuo `.env`, che non va sovrascritto
perché contiene già i dati di Enable Banking:

```properties
POSTGRES_DB=wallettracker
POSTGRES_USER=wallettracker
POSTGRES_PASSWORD=una-password-scelta-da-te
```

⚠️ **La password viene usata solo al primo avvio**, quando il volume è vuoto. Se la cambi dopo, il
database tiene quella vecchia. Per ricominciare da zero vedi `docker compose down -v` più sotto.

### 2. Avvia

Dalla cartella `WalletTracker` (dove c'è `docker-compose.yml`):

```powershell
docker compose up -d
```

La prima volta vedi la creazione di rete, volume e container. Poi controlla:

```powershell
docker compose ps
```

Dopo qualche secondo la colonna STATUS deve mostrare `Up ... (healthy)`.

### 3. Entra nel database

```powershell
docker compose exec db psql -U wallettracker -d wallettracker
```

Con Compose ti riferisci al **servizio** (`db`), non al nome del container. Ripeti l'esperimento:

```sql
CREATE TABLE prova (id INT, nota TEXT);
INSERT INTO prova VALUES (1, 'sopravvivo?');
\q
```

```powershell
docker compose down        # ferma e CANCELLA il container
docker compose up -d       # ne crea uno nuovo
docker compose exec db psql -U wallettracker -d wallettracker -c "SELECT * FROM prova;"
```

Questa volta la riga c'è: il container è nuovo, ma i dati erano nel volume. Poi elimina la tabella
di prova:

```powershell
docker compose exec db psql -U wallettracker -d wallettracker -c "DROP TABLE prova;"
```

### Comandi di riferimento

| Comando | Cosa fa |
|---|---|
| `docker compose up -d` | crea e avvia i container (se esistono già, li lascia così) |
| `docker compose ps` | stato dei container |
| `docker compose logs -f db` | segue i log del database (`Ctrl+C` per uscire) |
| `docker compose stop` | ferma i container senza cancellarli |
| `docker compose down` | ferma e cancella i container; **il volume resta** |
| `docker compose down -v` | ⚠️ cancella anche i volumi, cioè **tutti i dati**. Utile solo per ripartire da zero |
| `docker volume ls` | elenca i volumi (vedrai `wallettracker_db-data`) |

---

## Parte D: collegare IntelliJ al database

IntelliJ ha un client per database integrato, gratuito dalla versione 2025.3.

1. Apri la finestra **Database**: menu *View → Tool Windows → Database*, oppure l'icona a forma di
   cilindro sulla destra.
2. **+** → *Data Source* → **PostgreSQL**.
3. Compila:
   - Host: `localhost`
   - Port: `5432`
   - User / Password: quelli del tuo `.env`
   - Database: `wallettracker`
4. Se in basso compare "Download missing driver files", cliccalo.
5. **Test Connection** → deve dire "Succeeded". Poi **OK**.

Ora puoi esplorare schemi e tabelle e scrivere query nella *console*. Per ora il database è vuoto:
le tabelle le creerà Spring Boot nel passo 2.

---

## Problemi comuni

| Sintomo | Causa | Soluzione |
|---|---|---|
| `error during connect ... dockerDesktopLinuxEngine` | Docker Desktop non è avviato | avvialo e aspetta "Engine running" |
| `port is already allocated` / `bind: address already in use` | la porta 5432 è già usata: dal container `pg-prova` della Parte B o da un PostgreSQL installato su Windows | `docker rm -f pg-prova`; se hai PostgreSQL installato, fermalo oppure cambia la porta in `"127.0.0.1:5433:5432"` |
| `password authentication failed` | hai cambiato la password nel `.env` dopo il primo avvio | `docker compose down -v` e poi `up -d` (⚠️ cancella i dati) |
| lo stato resta `starting` o diventa `unhealthy` | il database non parte | `docker compose logs db` e leggi l'errore |
| `variable is not set. Defaulting to a blank string` | mancano le variabili nel `.env` | aggiungi le tre variabili `POSTGRES_*` |

---

## Cosa hai imparato

- **Immagine, container, volume, porta pubblicata, registry.**
- La differenza tra un container "usa e getta" e i dati **persistenti** nei volumi.
- `docker run` con le sue opzioni, e lo stesso descritto in **Docker Compose**.
- Le **variabili d'ambiente** come modo standard per configurare un container, e il file `.env`.
- L'**healthcheck** per sapere quando un servizio è davvero pronto.

## Prossimo passo

**Passo 2:** colleghiamo Spring Boot al database:
- configurazione del datasource;
- **Flyway** per creare le tabelle con script SQL versionati;
- le prime entità JPA (connessioni bancarie e conti).
