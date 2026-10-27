#!/usr/bin/env bash
# Installa e avvia WalletTracker su macOS e Linux (Fase 4, passo 2: docs/45).
# Su Windows c'è lo stesso script in PowerShell: installa.ps1.
#
#   ./installa.sh                        primo avvio, e tutte le volte dopo: prepara ciò che manca e avvia
#   ./installa.sh --ia                   come sopra, con anche Ollama (l'IA locale)
#   ./installa.sh --cambia-password-db   genera una password nuova per il database e la mette dove serve
#   ./installa.sh --senza-browser        non apre il browser alla fine (per la CI)
#
# Si può rilanciare quante volte si vuole: quello che c'è già (il .env, la chiave, i dati) non viene toccato.
#
# Che cosa fa, in ordine:
#   1. controlla che Docker sia avviato;
#   2. se manca il .env, lo crea da .env.example con una password del database generata a caso;
#   3. crea la cartella .data (la chiave di cifratura) come tuo utente;
#   4. se richiesto, cambia la password del database;
#   5. docker compose up -d --build, e aspetta che il backend sia pronto;
#   6. apre l'app nel browser.

# -e: se un comando fallisce, lo script si ferma (invece di andare avanti facendo danni).
# -u: usare una variabile mai assegnata è un errore (di solito è un nome scritto male).
set -eu

# Tutti i percorsi partono dalla cartella dello script, da qualunque cartella lo si lanci.
cd "$(dirname "$0")"

CON_IA=false
CAMBIA_PASSWORD=false
APRI_BROWSER=true
for argomento in "$@"; do
  case "$argomento" in
    --ia) CON_IA=true ;;
    --cambia-password-db) CAMBIA_PASSWORD=true ;;
    --senza-browser) APRI_BROWSER=false ;;
    -h | --aiuto | --help)
      sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
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

# --- 5. Avvio ----------------------------------------------------------------------------------------------
passo "Avvio di WalletTracker (la prima volta ci vuole qualche minuto: si scaricano e si compilano le immagini)"
profilo=""
[ "$CON_IA" = true ] && profilo="--profile ia"
# $profilo senza virgolette, di proposito: vuoto non aggiunge niente, altrimenti diventa le due parole "--profile ia".
# shellcheck disable=SC2086
docker compose $profilo up -d --build ||
  errore "docker compose non è riuscito ad avviare l'app: leggi il messaggio qui sopra."

passo "Aspetto che il backend sia pronto"
aspetta_salute backend 240
echo "Il backend è pronto."

# --- 6. Fatto ----------------------------------------------------------------------------------------------
porta=$(valore_env WEB_PORT)
indirizzo="http://localhost:${porta:-8000}"
passo "WalletTracker è avviato: $indirizzo"
echo "Per fermarlo:  docker compose down   (i dati restano)"
if [ "$APRI_BROWSER" = true ]; then
  # open su macOS, xdg-open sui Linux con un desktop; se non c'è nessuno dei due, basta l'indirizzo scritto sopra.
  if command -v open >/dev/null 2>&1; then
    open "$indirizzo"
  elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$indirizzo" >/dev/null 2>&1 || true
  fi
fi
