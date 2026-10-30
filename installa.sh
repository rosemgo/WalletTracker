#!/usr/bin/env bash
# Installa e avvia WalletTracker su macOS e Linux (Fase 4, passo 2: docs/45).
# Su Windows c'è lo stesso script in PowerShell: installa.ps1.
#
#   ./installa.sh                        primo avvio, e tutte le volte dopo: prepara ciò che manca e avvia
#   ./installa.sh --ia                   come sopra, con anche Ollama (l'IA locale)
#   ./installa.sh --cambia-password-db   genera una password nuova per il database e la mette dove serve
#   ./installa.sh --reimposta-pin        PIN e codice di recupero persi: li cancella, e dal PC si sceglie di nuovo
#   ./installa.sh --tailscale            apre l'app ai tuoi dispositivi con Tailscale, con https (docs/47)
#   ./installa.sh --senza-browser        non apre il browser alla fine (per la CI)
#
# Si può rilanciare quante volte si vuole: quello che c'è già (il .env, la chiave, i dati) non viene toccato.
#
# Che cosa fa, in ordine:
#   1. controlla che Docker sia avviato;
#   2. se manca il .env, lo crea da .env.example con una password del database generata a caso;
#   3. crea la cartella .data (la chiave di cifratura) come tuo utente;
#   4. se richiesto, cambia la password del database, azzera il PIN o prepara Tailscale;
#   5. docker compose up -d --build, e aspetta che il backend sia pronto;
#   6. apre l'app nel browser.

# -e: se un comando fallisce, lo script si ferma (invece di andare avanti facendo danni).
# -u: usare una variabile mai assegnata è un errore (di solito è un nome scritto male).
set -eu

# Tutti i percorsi partono dalla cartella dello script, da qualunque cartella lo si lanci.
cd "$(dirname "$0")"

CON_IA=false
CAMBIA_PASSWORD=false
REIMPOSTA_PIN=false
CON_TAILSCALE=false
APRI_BROWSER=true
for argomento in "$@"; do
  case "$argomento" in
    --ia) CON_IA=true ;;
    --cambia-password-db) CAMBIA_PASSWORD=true ;;
    --reimposta-pin) REIMPOSTA_PIN=true ;;
    --tailscale) CON_TAILSCALE=true ;;
    --senza-browser) APRI_BROWSER=false ;;
    -h | --aiuto | --help)
      sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "Opzione sconosciuta: $argomento (le opzioni sono in ./installa.sh --aiuto)" >&2
      exit 1
      ;;
  esac
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
# Il backend, nel container, è l'utente con id 1000 (backend/Dockerfile) e in .data deve scrivere la chiave di
# cifratura. Su macOS Docker Desktop traduce i permessi da solo; su Linux conta il numero del proprietario.
mkdir -p .data secrets
if [ "$(uname -s)" = "Linux" ]; then
  proprietario=$(stat -c '%u' .data)
  if [ "$proprietario" != "1000" ]; then
    if [ "$(id -u)" = "0" ]; then
      # Lanciato come root (o con sudo): la cartella è di root, e l'utente 1000 non potrebbe scriverci.
      chown 1000:1000 .data
      echo "La cartella .data ora appartiene all'utente 1000, quello del backend nel container."
    else
      errore "La cartella .data è dell'utente $proprietario, ma il backend nel container è l'utente 1000 e non potrebbe scriverci. Sistemala con:  sudo chown 1000:1000 .data   e rilancia lo script (docs/45, parte F)."
    fi
  fi
fi

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

# --- 6. Fatto ----------------------------------------------------------------------------------------------
porta=$(valore_env WEB_PORT)
indirizzo="http://localhost:${porta:-8000}"
passo "WalletTracker è avviato: $indirizzo"
if [ "$CON_TAILSCALE" = true ]; then
  echo "Dai dispositivi remoti, con Tailscale:  https://$nome_ts   (lì serve il PIN: crealo prima da qui, dal server)"
  echo "Per collegare una banca da un dispositivo remoto, registra su Enable Banking anche:  https://$nome_ts/callback"
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
