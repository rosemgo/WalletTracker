#!/usr/bin/env bash
# Installa e avvia WalletTracker su macOS e Linux (Fase 4, passo 2: docs/45).
# Su Windows c'è lo stesso script in PowerShell: installa.ps1.
#
#   ./installa.sh                        primo avvio, e tutte le volte dopo: prepara ciò che manca e avvia
#   ./installa.sh --ia                   come sopra, con anche Ollama (l'IA locale)
#   ./installa.sh --cambia-password-db   genera una password nuova per il database e la mette dove serve
#   ./installa.sh --reimposta-pin        PIN e codice di recupero persi: li cancella, e dal PC si sceglie di nuovo
#   ./installa.sh --tailscale            apre l'app ai tuoi dispositivi con Tailscale, con https (docs/47)
#   ./installa.sh --backup               un backup adesso (la prima volta chiede la password dei backup: docs/48)
#   ./installa.sh --ripristina FILE      SOSTITUISCE i dati di adesso con quelli del backup FILE (chiede conferma)
#   ./installa.sh --senza-browser        non apre il browser alla fine (per la CI)
#
# Si può rilanciare quante volte si vuole: quello che c'è già (il .env, la chiave, i dati) non viene toccato.
#
# Che cosa fa, in ordine:
#   1. controlla che Docker sia avviato;
#   2. se manca il .env, lo crea da .env.example con una password del database generata a caso;
#   3. crea la cartella .data (la chiave di cifratura) come tuo utente;
#   4. se richiesto, cambia la password del database, azzera il PIN, prepara Tailscale o la password dei backup;
#   5. docker compose up -d --build, e aspetta che il backend sia pronto;
#   6. se richiesto, fa un backup o un ripristino;
#   7. apre l'app nel browser.

# -e: se un comando fallisce, lo script si ferma (invece di andare avanti facendo danni).
# -u: usare una variabile mai assegnata è un errore (di solito è un nome scritto male).
set -eu

# Tutti i percorsi partono dalla cartella dello script, da qualunque cartella lo si lanci.
cd "$(dirname "$0")"

CON_IA=false
CAMBIA_PASSWORD=false
REIMPOSTA_PIN=false
CON_TAILSCALE=false
FARE_BACKUP=false
FILE_RIPRISTINO=""
APRI_BROWSER=true
# Un'opzione alla volta: "shift" toglie quella appena letta. --ripristina è l'unica con un valore (il file), che
# si prende con un "shift" in più.
while [ $# -gt 0 ]; do
  case "$1" in
    --ia) CON_IA=true ;;
    --cambia-password-db) CAMBIA_PASSWORD=true ;;
    --reimposta-pin) REIMPOSTA_PIN=true ;;
    --tailscale) CON_TAILSCALE=true ;;
    --backup) FARE_BACKUP=true ;;
    --ripristina)
      [ $# -ge 2 ] || {
        echo "--ripristina vuole il file del backup, per esempio:  ./installa.sh --ripristina backup/wallettracker-manuale-20261031-101500.backup" >&2
        exit 1
      }
      FILE_RIPRISTINO=$2
      shift
      ;;
    --senza-browser) APRI_BROWSER=false ;;
    -h | --aiuto | --help)
      sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "Opzione sconosciuta: $1 (le opzioni sono in ./installa.sh --aiuto)" >&2
      exit 1
      ;;
  esac
  shift
done

passo() { printf '\n==> %s\n' "$1"; }
errore() {
  printf '\nERRORE: %s\n' "$1" >&2
  exit 1
}

# Una password di 32 lettere e cifre, dal generatore casuale del sistema operativo (/dev/urandom). Solo lettere e
# cifre: niente caratteri che nel .env, in un URL o in SQL vanno protetti con le virgolette. 32 caratteri da 62
# possibili sono circa 190 bit: impossibile da indovinare.
# LC_ALL=C: tr lavora sui byte, senza farsi confondere dalla lingua del sistema (su macOS servirebbe).
genera_password() {
  LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32
}

# Il valore di una variabile del .env (l'ultima riga NOME=..., come fa Docker Compose); vuoto se manca.
# tr -d '\r': se il .env è stato salvato su Windows, le righe finiscono con un \r da togliere.
valore_env() {
  grep -E "^$1=" .env 2>/dev/null | tail -n 1 | cut -d= -f2- | tr -d '\r' || true
}

# Scrive NOME=valore nel .env: sostituisce la riga se c'è, la aggiunge in fondo se manca. Scrive prima un file
# temporaneo e poi lo mette al posto del .env: se qualcosa si interrompe a metà, il .env vecchio resta intero.
scrivi_env() {
  awk -v nome="$1" -v valore="$2" '
    BEGIN { fatto = 0 }
    index($0, nome "=") == 1 { print nome "=" valore; fatto = 1; next }
    { print }
    END { if (!fatto) print nome "=" valore }
  ' .env >.env.nuovo
  chmod 600 .env.nuovo
  mv .env.nuovo .env
}

# Aspetta che il controllo di salute di un servizio (es. backend, il container wallettracker-backend) dica "healthy" (al massimo $2 secondi).
aspetta_salute() {
  local servizio="$1" massimo="$2" container="wallettracker-$1" stato="" secondi=0
  while [ "$secondi" -lt "$massimo" ]; do
    stato=$(docker inspect --format '{{.State.Health.Status}}' "$container" 2>/dev/null || true)
    [ "$stato" = "healthy" ] && return 0
    sleep 3
    secondi=$((secondi + 3))
  done
  errore "$servizio non è pronto dopo $massimo secondi (stato: ${stato:-sconosciuto}). Guarda i log con:  docker compose logs $servizio"
}

# --- 1. Docker ---------------------------------------------------------------------------------------------
passo "Controllo Docker"
command -v docker >/dev/null 2>&1 || errore "Docker non è installato: installa Docker Desktop (docs/45, parte B)."
docker info >/dev/null 2>&1 || errore "Docker non risponde: avvia Docker Desktop, aspetta che dica \"Engine running\" e riprova."
docker compose version >/dev/null 2>&1 || errore "Manca Docker Compose v2 (il comando \"docker compose\"): aggiorna Docker Desktop."
echo "Docker è avviato."

# --- 2. Il file .env ---------------------------------------------------------------------------------------
passo "Configurazione (.env)"
if [ ! -f .env ]; then
  [ -f .env.example ] || errore "Manca .env.example: lo script va lanciato dalla cartella del progetto scaricato."
  # Il .env manca, ma il database di un'installazione precedente c'è ancora (il volume resta anche se si cancella la
  # cartella del progetto). Ha la SUA password, scritta nel vecchio .env: con una nuova il backend non entrerebbe.
  if docker volume inspect wallettracker_db-data >/dev/null 2>&1; then
    errore "Manca il file .env, ma c'è già il database di un'installazione precedente, con la sua password. Rimetti qui il vecchio .env (dal backup) e rilancia lo script. Se invece vuoi ripartire da zero, CANCELLANDO tutti i dati di prima, i comandi sono in docs/45, parte M."
  fi
  cp .env.example .env
  # 600 = leggibile e scrivibile solo dal tuo utente: dentro c'è la password del database.
  chmod 600 .env
  scrivi_env POSTGRES_PASSWORD "$(genera_password)"
  echo "Creato il file .env, con una password del database generata a caso."
else
  echo "Il file .env c'è già: lo uso così com'è."
  password_attuale=$(valore_env POSTGRES_PASSWORD)
  if [ -z "$password_attuale" ]; then
    # Un .env copiato a mano da .env.example, con la password vuota: la generiamo. Il database non può esistere
    # già con una password vuota, perché senza password Compose non lo avrebbe mai avviato.
    scrivi_env POSTGRES_PASSWORD "$(genera_password)"
    echo "Mancava la password del database: ne ho generata una a caso."
  elif [ "$password_attuale" = "cambia-questa-password" ] || [ "${#password_attuale}" -lt 16 ]; then
    if [ "$CAMBIA_PASSWORD" = false ]; then
      echo "ATTENZIONE: la password del database è quella d'esempio, o è più corta di 16 caratteri."
      echo "            Per sostituirla con una generata a caso:  ./installa.sh --cambia-password-db"
    fi
  fi
fi

# --- 3. Le cartelle ----------------------------------------------------------------------------------------
# Il backend e il backup, nei container, sono l'utente con id 1000 (backend/Dockerfile, docker/backup/Dockerfile):
# in .data c'è la chiave di cifratura, nella cartella dei backup vanno i backup. Su macOS Docker Desktop traduce i
# permessi da solo; su Linux conta il numero del proprietario.
cartella_backup=$(valore_env BACKUP_DIR)
cartella_backup=${cartella_backup:-./backup}
mkdir -p .data secrets "$cartella_backup"

# La cartella $1 deve appartenere all'utente 1000 (solo su Linux).
cartella_per_i_container() {
  [ "$(uname -s)" = "Linux" ] || return 0
  local proprietario
  proprietario=$(stat -c '%u' "$1")
  [ "$proprietario" = "1000" ] && return 0
  if [ "$(id -u)" = "0" ]; then
    # Lanciato come root (o con sudo): la cartella è di root, e l'utente 1000 non potrebbe scriverci.
    chown 1000:1000 "$1"
    echo "La cartella $1 ora appartiene all'utente 1000, quello dei container."
  else
    errore "La cartella $1 è dell'utente $proprietario, ma nei container il programma è l'utente 1000 e non potrebbe scriverci. Sistemala con:  sudo chown 1000:1000 $1   e rilancia lo script (docs/45, parte F)."
  fi
}
cartella_per_i_container .data
cartella_per_i_container "$cartella_backup"

# --- 4. Cambiare la password del database (solo con --cambia-password-db) --------------------------------
if [ "$CAMBIA_PASSWORD" = true ]; then
  passo "Cambio della password del database"
  utente=$(valore_env POSTGRES_USER)
  database=$(valore_env POSTGRES_DB)
  [ -n "$utente" ] && [ -n "$database" ] || errore "Nel .env mancano POSTGRES_USER o POSTGRES_DB."
  # La password vive in DUE posti (docs/44, parte E2): dentro il database (la "serratura") e nel .env (la
  # "chiave" che Compose dà al backend). Cambiare solo il .env non basta: il database, già creato, terrebbe la
  # vecchia. Quindi: prima la si cambia nel database, poi nel .env. Se il primo passo fallisce, il .env resta
  # com'era e tutto continua a funzionare.
  docker compose up -d db >/dev/null
  aspetta_salute db 60
  nuova=$(genera_password)
  # psql dentro il container del database. Lì si entra senza password: i collegamenti "locali" (dall'interno del
  # container) sono fidati, solo quelli dalla rete chiedono la password.
  # La password è fatta solo di lettere e cifre, quindi tra apici in SQL è sicura.
  docker compose exec -T db psql -q -v ON_ERROR_STOP=1 -U "$utente" -d "$database" \
    -c "ALTER USER \"$utente\" PASSWORD '$nuova'" ||
    errore "Il database non ha accettato la password nuova: niente è cambiato."
  scrivi_env POSTGRES_PASSWORD "$nuova"
  echo "Password cambiata nel database e salvata nel .env. Il backend la riceve al riavvio, qui sotto."
fi

# --- 4b. Azzerare il PIN (solo con --reimposta-pin) -------------------------------------------------------
# Il PIN dimenticato, e perso anche il codice di recupero: il PIN non si legge (nel database c'è solo il suo hash,
# docs/46), si cancella. Alla prossima apertura dal PC l'app chiede di nuovo: con un PIN o senza. Si fa solo da qui, dal PC: chi non ha il PC non può farlo.
if [ "$REIMPOSTA_PIN" = true ]; then
  passo "Azzero il PIN"
  utente=$(valore_env POSTGRES_USER)
  database=$(valore_env POSTGRES_DB)
  docker compose up -d db >/dev/null
  aspetta_salute db 60
  docker compose exec -T db psql -q -v ON_ERROR_STOP=1 -U "$utente" -d "$database" -c "DELETE FROM app_pin" ||
    errore "Non sono riuscito ad azzerare il PIN (l'app è già stata avviata almeno una volta?)."
  echo "PIN azzerato. Apri l'app da qui, dal server (http://localhost:8000): sceglierai di nuovo, con un PIN nuovo o senza."
fi

# --- 4c. Tailscale (solo con --tailscale) ------------------------------------------------------------------
# Tailscale è installato e collegato a mano, sul PC e sul telefono (docs/47, parti C e D). Qui lo script fa le tre
# cose che restano, sempre uguali:
#   - chiede a Tailscale di ricevere i tuoi dispositivi in https e di passarli alla porta "remota" di nginx
#     (tailscale serve, docs/47 parte E): la porta del PC (8000) a Tailscale non la diamo mai;
#   - aggiunge il nome del PC nella rete Tailscale ai nomi consentiti dal backend (WALLETTRACKER_ALLOWED_HOSTS);
#   - aggiunge l'indirizzo di ritorno di Enable Banking per quel nome (docs/47, parte I).
# Si può rilanciare: se è già tutto pronto, non cambia niente.

# Aggiunge un valore a una variabile del .env fatta di valori separati da virgole, se non c'è già.
aggiungi_a_elenco() {
  local nome="$1" nuovo="$2" predefinito="$3" attuale
  attuale=$(valore_env "$nome")
  [ -z "$attuale" ] && attuale="$predefinito"
  case ",$attuale," in
    *",$nuovo,"*) return 1 ;; # c'è già
  esac
  scrivi_env "$nome" "$attuale,$nuovo"
}

if [ "$CON_TAILSCALE" = true ]; then
  passo "Tailscale"
  # Dove sta il comando tailscale: di solito è nel PATH; su macOS, con l'app dell'App Store, è dentro l'app.
  tailscale_cmd=""
  if command -v tailscale >/dev/null 2>&1; then
    tailscale_cmd=tailscale
  elif [ -x /Applications/Tailscale.app/Contents/MacOS/Tailscale ]; then
    tailscale_cmd=/Applications/Tailscale.app/Contents/MacOS/Tailscale
  else
    errore "Non trovo Tailscale: installalo e accedi con il tuo account (docs/47, parte C), poi rilancia."
  fi

  # tailscale status --json: lo stato di Tailscale su questo PC, in JSON. "BackendState" vale "Running" quando è
  # collegato; il nome del PC nella rete è "DNSName", dentro "Self" (questo PC), con un punto finale da togliere.
  stato_ts=$("$tailscale_cmd" status --json 2>/dev/null) ||
    errore "Tailscale non risponde: aprilo e accedi con il tuo account (docs/47, parte C)."
  echo "$stato_ts" | grep -q '"BackendState": *"Running"' ||
    errore "Tailscale è installato ma non collegato: aprilo, accedi con il tuo account e rilancia."
  nome_ts=$(echo "$stato_ts" | awk '/"Self":/ { dentro = 1 } dentro && /"DNSName":/ { sub(/.*"DNSName": *"/, ""); sub(/\.?".*/, ""); print; exit }')
  case "$nome_ts" in
    *.ts.net) ;;
    *) errore "Non trovo il nome del server nella rete Tailscale: accendi MagicDNS nella console di Tailscale (docs/47, parte E1) e rilancia." ;;
  esac
  echo "Il nome di questo server nella rete Tailscale è: $nome_ts"

  # Funnel pubblica su Internet: per WalletTracker mai (docs/47, parte F5). Se è acceso, ci fermiamo.
  if "$tailscale_cmd" serve status --json 2>/dev/null | tr -d ' \n' | grep -q '"AllowFunnel":{[^}]*:true'; then
    errore "Tailscale Funnel è acceso: renderebbe l'app raggiungibile da Internet. Spegnilo con:  $tailscale_cmd funnel --https=443 off   e rilancia."
  fi

  # La porta "remota" di nginx sul PC (docker-compose.yml): 8001, se non l'hai cambiata nel .env.
  porta_remota=$(valore_env REMOTE_PORT)
  porta_remota=${porta_remota:-8001}
  echo "Chiedo a Tailscale di ricevere i tuoi dispositivi su https://$nome_ts e di passarli a 127.0.0.1:$porta_remota."
  echo "(Se è la prima volta, Tailscale può chiederti di attivare HTTPS per la tua rete: apri il link che scrive,"
  echo " conferma, e lo script prosegue da solo.)"
  # --bg: resta acceso da solo, anche dopo un riavvio del PC. --https=443: la porta normale di https, così
  # l'indirizzo non ha bisogno di ":numero".
  "$tailscale_cmd" serve --bg --https=443 "http://127.0.0.1:$porta_remota" ||
    errore "tailscale serve non è riuscito (messaggio qui sopra). Su Linux può servire il permesso:  sudo tailscale set --operator=\$USER   e poi rilancia (docs/47, parte P)."

  if aggiungi_a_elenco WALLETTRACKER_ALLOWED_HOSTS "$nome_ts" "localhost,127.0.0.1"; then
    echo "Aggiunto $nome_ts ai nomi consentiti dal backend (WALLETTRACKER_ALLOWED_HOSTS nel .env)."
  fi
  if aggiungi_a_elenco ENABLE_BANKING_REDIRECT_URL "https://$nome_ts/callback" "https://localhost:8443/callback"; then
    echo "Aggiunto https://$nome_ts/callback agli indirizzi di ritorno di Enable Banking (nel .env)."
  fi
fi

# --- 4d. La password dei backup (con --backup, se manca) --------------------------------------------------
# I backup sono cifrati con una password scelta da te (docs/48, parte D). Sta nel .env, perché il backup automatico
# di ogni notte la deve usare; ma va anche scritta in un posto sicuro FUORI da questo computer: senza, un backup
# portato altrove (o il computer rotto) non si apre più.

# Una password va bene se è lunga almeno 12 caratteri e non ha i caratteri che nel .env darebbero problemi.
password_backup_valida() {
  [ ${#1} -ge 12 ] || return 1
  case "$1" in
    *[\'\"\$\\\`\#]* | " "* | *" ") return 1 ;;
  esac
}

chiedi_password_backup() {
  local prima seconda
  [ -t 0 ] || errore "Manca la password dei backup: lancia ./installa.sh --backup da un terminale (la chiede), o scrivila nel .env come WALLETTRACKER_BACKUP_PASSWORD (docs/48)."
  echo "Scegli la password dei backup. Ogni backup sarà cifrato con lei: chi trova il file senza la password non"
  echo "legge niente. Almeno 12 caratteri; niente virgolette, apostrofi, \$, \\, \` e #, né spazi all'inizio o alla fine."
  echo "SCRIVILA ANCHE IN UN POSTO SICURO, FUORI DA QUESTO COMPUTER (un gestore di password): senza, i backup non si aprono."
  while true; do
    # -s: quello che scrivi non si vede.
    read -rs -p "Password dei backup: " prima
    echo
    read -rs -p "Ripetila: " seconda
    echo
    if ! password_backup_valida "$prima"; then
      echo "Non va bene: almeno 12 caratteri, e senza i caratteri qui sopra. Riprova."
    elif [ "$prima" != "$seconda" ]; then
      echo "Le due password non sono uguali. Riprova."
    else
      break
    fi
  done
  scrivi_env WALLETTRACKER_BACKUP_PASSWORD "$prima"
  echo "Password salvata nel .env: da stanotte parte anche il backup automatico (alle ${BACKUP_TIME:-03:15})."
}

password_backup=$(valore_env WALLETTRACKER_BACKUP_PASSWORD)
if [ "$FARE_BACKUP" = true ] && [ -z "$password_backup" ]; then
  passo "La password dei backup"
  chiedi_password_backup
  password_backup=$(valore_env WALLETTRACKER_BACKUP_PASSWORD)
fi

# --- 5. Avvio ----------------------------------------------------------------------------------------------
passo "Avvio di WalletTracker (la prima volta ci vuole qualche minuto: si scaricano e si compilano le immagini)"
profilo=""
[ "$CON_IA" = true ] && profilo="--profile ia"
# $profilo senza virgolette, di proposito: vuoto non aggiunge niente, altrimenti diventa le due parole "--profile ia".
# shellcheck disable=SC2086
docker compose $profilo up -d --build ||
  errore "docker compose non è riuscito ad avviare l'app: leggi il messaggio qui sopra."

# Dopo aver azzerato il PIN, il backend si riavvia: le sessioni aperte (anche su dispositivi persi) si chiudono.
if [ "$REIMPOSTA_PIN" = true ]; then
  docker compose restart backend >/dev/null
fi

passo "Aspetto che il backend sia pronto"
aspetta_salute backend 240
echo "Il backend è pronto."

# --- 6. Backup e ripristino (solo con --backup o --ripristina) ---------------------------------------------
# Il lavoro vero lo fa il container "backup" (docker/backup/wallettracker-backup.sh), uguale su Windows, macOS e
# Linux. "docker compose run --rm" crea un container usa-e-getta dal servizio backup, con il comando scritto dopo.
# -T e </dev/null: il container non deve leggere niente dalla tastiera (e non deve "mangiarsi" quello che arriva
# allo script, per esempio la conferma del ripristino).
if [ "$FARE_BACKUP" = true ]; then
  passo "Backup"
  docker compose run --rm -T backup crea manuale </dev/null ||
    errore "Il backup non è riuscito: leggi il messaggio qui sopra."
  echo "Il backup è nella cartella $cartella_backup. Copialo anche FUORI da questo computer (docs/48, parte F)."
fi

if [ -n "$FILE_RIPRISTINO" ]; then
  passo "Ripristino da $FILE_RIPRISTINO"
  [ -f "$FILE_RIPRISTINO" ] || errore "Non trovo il file $FILE_RIPRISTINO."
  # Il container vede solo la cartella dei backup: se il file è altrove, lo copio lì (con lo stesso nome).
  nome_backup=$(basename "$FILE_RIPRISTINO")
  if [ ! "$FILE_RIPRISTINO" -ef "$cartella_backup/$nome_backup" ]; then
    if [ -e "$cartella_backup/$nome_backup" ] && ! cmp -s "$FILE_RIPRISTINO" "$cartella_backup/$nome_backup"; then
      errore "Nella cartella dei backup c'è già un file diverso di nome $nome_backup: rinomina quello da ripristinare."
    fi
    cp "$FILE_RIPRISTINO" "$cartella_backup/$nome_backup"
    echo "Copiato $nome_backup nella cartella dei backup ($cartella_backup)."
  fi

  echo
  echo "ATTENZIONE: il ripristino SOSTITUISCE tutti i dati di adesso (conti, movimenti, regole, impostazioni, PIN)"
  echo "con quelli del backup. Prima ne faccio una copia di sicurezza, se nel .env c'è la password dei backup."
  read -r -p "Per continuare scrivi RIPRISTINA: " risposta || risposta=""
  [ "$risposta" = "RIPRISTINA" ] || errore "Ripristino annullato: non è cambiato niente."

  # 1. La copia di sicurezza di adesso, con la password del .env (non con un'altra che arrivasse da fuori).
  if password_backup_valida "$password_backup"; then
    WALLETTRACKER_BACKUP_PASSWORD="$password_backup" docker compose run --rm -T backup crea prima-del-ripristino </dev/null ||
      errore "Non riesco a fare la copia di sicurezza dei dati di adesso: ripristino annullato, non è cambiato niente."
  else
    echo "Nel .env non c'è la password dei backup: niente copia di sicurezza dei dati di adesso."
  fi

  # 2. Fermi il backend (nessuno deve scrivere nel database mentre lo si sostituisce) e il backup automatico.
  docker compose stop backend backup >/dev/null
  # Se ci si ferma prima della fine, si riparte com'era, aspettando il backend (prima, nginx risponderebbe 502).
  riparti() {
    docker compose start backend backup >/dev/null
    aspetta_salute backend 240
  }

  # 3. Il ripristino. Con la password del .env, se c'è; se il container risponde "password sbagliata o mancante"
  #    (codici 3 e 4: per esempio un backup fatto su un altro computer), la chiedo, fino a tre volte.
  tentativi=0
  while true; do
    codice=0
    docker compose run --rm -T backup ripristina "$nome_backup" </dev/null || codice=$?
    [ "$codice" = 0 ] && break
    if [ "$codice" != 3 ] && [ "$codice" != 4 ] || [ "$tentativi" -ge 3 ]; then
      riparti
      errore "Il ripristino non è riuscito (leggi il messaggio qui sopra). Se si è fermato prima di \"Svuoto il database\", non è cambiato niente."
    fi
    tentativi=$((tentativi + 1))
    password_inserita=""
    read -rs -p "Password di QUESTO backup (Invio vuoto per annullare): " password_inserita || password_inserita=""
    echo
    if [ -z "$password_inserita" ]; then
      riparti
      errore "Ripristino annullato: non è cambiato niente."
    fi
    # Docker Compose preferisce le variabili dell'ambiente a quelle del .env: così il container riceve questa.
    export WALLETTRACKER_BACKUP_PASSWORD="$password_inserita"
  done

  # Un computer nuovo, senza password dei backup nel .env: tengo quella appena usata, così da stanotte parte il
  # backup automatico (con la stessa password dei backup di prima).
  if [ -z "$password_backup" ] && [ -n "${password_inserita:-}" ] && password_backup_valida "$password_inserita"; then
    scrivi_env WALLETTRACKER_BACKUP_PASSWORD "$password_inserita"
    echo "Ho salvato nel .env la password del backup: da stanotte parte il backup automatico."
  fi

  # 4. Si riparte: il backend legge la chiave rimessa a posto, e Flyway porta il database alla versione dell'app.
  riparti
  echo "Ripristino completato: l'app ha i dati del backup. (Il PIN è quello di allora, come tutto il resto.)"
fi

# --- 7. Fatto ----------------------------------------------------------------------------------------------
porta=$(valore_env WEB_PORT)
indirizzo="http://localhost:${porta:-8000}"
passo "WalletTracker è avviato: $indirizzo"
if [ "$CON_TAILSCALE" = true ]; then
  echo "Dai dispositivi remoti, con Tailscale:  https://$nome_ts   (lì serve il PIN: crealo prima da qui, dal server)"
  echo "Per collegare una banca da un dispositivo remoto, registra su Enable Banking anche:  https://$nome_ts/callback"
fi
if [ -z "$(valore_env WALLETTRACKER_BACKUP_PASSWORD)" ]; then
  echo "Backup automatico SPENTO: per accenderlo, e fare il primo backup:  ./installa.sh --backup   (docs/48)"
fi
echo "Per fermarlo:  docker compose down   (i dati restano)"
if [ "$APRI_BROWSER" = true ]; then
  # open su macOS, xdg-open sui Linux con un desktop; se non c'è nessuno dei due, basta l'indirizzo scritto sopra.
  if command -v open >/dev/null 2>&1; then
    open "$indirizzo"
  elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$indirizzo" >/dev/null 2>&1 || true
  fi
fi
