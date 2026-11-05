# Sviluppare: quali comandi lanciare

Fuori dalla numerazione: un promemoria da tenere a portata di mano mentre modifichi l'app. Le spiegazioni complete
sono in `docs/44` (container e Compose), `docs/45` (lo script) e `docs/50` (immagini costruite dal codice).

## L'unica regola

**Mai due backend accesi insieme.** Il backend nel container (`backend`, nel gruppo `wallettracker` di Docker
Desktop) e quello avviato dal PC (`mvnw spring-boot:run` o IntelliJ) non devono girare nello stesso momento, perché
lavorerebbero sullo **stesso database**:

1. **le letture dalle banche raddoppiano**: ognuno ha la sua sincronizzazione automatica, e per la PSD2 le letture
   automatiche sono circa 4 al giorno per conto. Con due backend il limite finisce in metà tempo;
2. **il database diventa "più nuovo" dell'app nel container**: se il codice del PC ha una migrazione Flyway nuova, la
   applica; al riavvio successivo il backend del container, con il codice vecchio, trova una migrazione che non conosce
   e **non parte** finché non lo ricostruisci;
3. **i lavori programmati vanno doppi** (gli avvisi, per esempio).

Il volume dei dati invece non è un problema: è uno solo, e lo usa un solo database (il container `db`).

## Perché i container partono da soli

In `docker-compose.yml` ogni servizio ha `restart: unless-stopped`: "riavvialo sempre, a meno che non l'abbia fermato
tu". Quando apri Docker Desktop, i container che erano accesi ripartono. Se li fermi tu (il quadratino ■ sulla riga
`wallettracker`, o `docker compose down`) restano fermi anche dopo un riavvio.

---

## Modo A: solo lo script (il più semplice)

Dopo ogni modifica, dalla radice del progetto:

```
powershell -ExecutionPolicy Bypass -File installa.ps1      # Windows
./installa.sh                                             # Linux e Mac
```

poi apri **http://localhost:8000**.

- Lo script trova il codice (`backend/pom.xml`), quindi **ricostruisce le immagini dal codice** e sostituisce solo i
  container cambiati. Il database e i dati restano. Il backend resta **uno solo**: quello nel container.
- Tocca solo i container del gruppo `wallettracker`. Un container `ollama` avviato a parte, fuori dal gruppo, non lo
  tocca.
- **Pro**: provi l'app esattamente come la userà chi la installa (nginx, PIN, backup).
- **Contro**: ogni volta ricompila tutto, quindi qualche minuto per ogni modifica.

## Modo B: backend e dashboard dal PC (il più veloce per tante piccole modifiche)

**1. Una volta, all'inizio**: spegni l'app nel container e lascia acceso solo il database, con la porta aperta per il
PC (`docker-compose.dev.yml`):

```
docker compose stop backend web backup
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d db
```

**2. Il backend**, dalla cartella `backend/` (su http://localhost:8080):

```
.\mvnw.cmd spring-boot:run          # Windows
./mvnw spring-boot:run              # Linux e Mac
```

**3. La dashboard**, in un altro terminale, dalla cartella `frontend/`:

```
npm start
```

poi apri **https://localhost:8443**. Il server di Angular passa le chiamate `/api` al backend sulla 8080
(`frontend/proxy.conf.json`).

- **Pro**: le modifiche alla dashboard si vedono appena salvi il file. Per il backend basta fermare (Ctrl+C) e
  rilanciare `mvnw`, senza ricostruire le immagini.
- **Contro**: non c'è nginx, quindi è un po' diverso dall'app installata.

**4. Alla fine**: ferma `mvnw` e `npm start` con **Ctrl+C**, poi rilancia lo script (modo A). L'app nel container
torna accesa, con le tue modifiche.

---

## Tabella veloce

| Vuoi… | Comando |
|---|---|
| usare l'app, o provare una modifica nell'app completa | `powershell -ExecutionPolicy Bypass -File installa.ps1` |
| sviluppare veloce: spegnere l'app, tenere il database | `docker compose stop backend web backup` e poi `docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d db` |
| avviare il backend dal PC | `.\mvnw.cmd spring-boot:run` (in `backend/`) |
| avviare la dashboard dal PC | `npm start` (in `frontend/`), poi https://localhost:8443 |
| fermare tutto (i dati restano) | `docker compose down` |
| vedere che cosa è acceso | `docker compose ps` |
| lanciare i test del backend (serve Docker Desktop acceso) | `.\mvnw.cmd test` (in `backend/`) |
| lanciare i test della dashboard | `npx ng test --watch=false` (in `frontend/`) |

## Le immagini in Docker Desktop

Costruite dal codice, dal passo 6a in poi, si chiamano `ghcr.io/rosemgo/wallettracker-backend:sviluppo` (e così per
`web` e `backup`): costruite sul tuo PC, mai pubblicate. Se vedi ancora `wallettracker-backend` senza `ghcr.io`, sono
le immagini di prima: dopo il primo avvio con il codice nuovo si possono cancellare dalla scheda **Images**.
