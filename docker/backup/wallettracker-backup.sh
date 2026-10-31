#!/usr/bin/env bash
# Backup e ripristino di WalletTracker (Fase 4, passo 5: docs/48). Gira nel container "backup".
#
#   wallettracker-backup automatico          resta acceso: un backup al giorno, dall'ora BACKUP_TIME (di serie 03:15)
#   wallettracker-backup crea [tipo]         un backup adesso; tipo: manuale (di serie), automatico, prima-del-ripristino
#   wallettracker-backup verifica <file>     apre il backup e controlla che sia integro, senza toccare niente
#   wallettracker-backup ripristina <file>   SOSTITUISCE il database e la chiave di cifratura con quelli del backup
#
# Un backup è UN file, cifrato con la password WALLETTRACKER_BACKUP_PASSWORD (AES-256, docs/48 parte D). Dentro:
#   database.dump    la "fotografia" del database, fatta da pg_dump (formato "custom": compresso)
#   encryption.key   la chiave di cifratura: senza, i segreti salvati nel database non si leggono (docs/17)
#   manifest.txt     quando, che tipo, che versione di PostgreSQL, l'ultima migrazione di Flyway
#   SHA256SUMS       l'impronta di ogni file: al ripristino si controlla che nessun byte sia cambiato
#
# Come parla con il database: pg_dump, pg_restore e psql leggono PGHOST, PGDATABASE, PGUSER e PGPASSWORD
# (docker-compose.yml). I file: la cartella dei backup è montata su /backup, quella della chiave (.data) su /data.
#
# Codici d'uscita, per lo script di installazione: 0 tutto bene; 2 errore; 3 password sbagliata (o il file non è un
# backup, o è rovinato prima ancora di aprirlo); 4 manca la password; 5 il backup si apre ma il contenuto non torna.

# -e: un comando che fallisce ferma lo script; -u: una variabile mai assegnata è un errore; -o pipefail: in
# "a | b" conta anche il fallimento di "a" (senza, conterebbe solo "b").
set -euo pipefail

# Ogni file creato da qui in poi è leggibile solo dal proprietario (permessi 600): i backup e le cartelle di lavoro.
umask 077

CARTELLA=/backup
CHIAVE=/data/encryption.key
# PBKDF2 con 600 000 giri (docs/48, parte D3): trasforma la password in una chiave AES. Cambiarlo renderebbe
# illeggibili i backup già fatti.
ITERAZIONI=600000
: "${WALLETTRACKER_BACKUP_PASSWORD:=}"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

# errore CODICE MESSAGGIO: scrive il messaggio ed esce con quel codice.
errore() {
  local codice=$1
  shift
  echo "ERRORE: $*" >&2
  exit "$codice"
}

# La password deve esserci, ed essere lunga almeno 12 caratteri (lo stesso controllo dello script di installazione).
controlla_password() {
  [ ${#WALLETTRACKER_BACKUP_PASSWORD} -ge 12 ] ||
    errore 4 "Manca la password dei backup (WALLETTRACKER_BACKUP_PASSWORD nel .env): ./installa.sh --backup la chiede (docs/48)."
}

# "nome.backup" vuol dire un file della cartella dei backup; un percorso con "/" resta com'è.
percorso() {
  case "$1" in
    */*) echo "$1" ;;
    *) echo "$CARTELLA/$1" ;;
  esac
}

# --- crea -------------------------------------------------------------------------------------------------------
crea() {
  local tipo=${1:-manuale}
  case "$tipo" in
    manuale | automatico | prima-del-ripristino) ;;
    *) errore 2 "Tipo di backup sconosciuto: $tipo" ;;
  esac
  controlla_password
  [ -r "$CHIAVE" ] || errore 2 "Non trovo la chiave di cifratura ($CHIAVE): l'app è mai stata avviata?"

  local nome lavoro
  nome="wallettracker-$tipo-$(date +%Y%m%d-%H%M%S).backup"
  # Una cartella di lavoro in /tmp (in memoria: docker-compose.yml, tmpfs), cancellata alla fine.
  lavoro=$(mktemp -d)

  # 1. La fotografia del database. pg_dump legge tutto dentro una sola transazione: la copia è coerente anche se
  #    nel frattempo il backend scrive (docs/48, parte C2).
  pg_dump --format=custom --file="$lavoro/database.dump"
  # 2. La chiave di cifratura.
  cp "$CHIAVE" "$lavoro/encryption.key"
  # 3. Il manifesto: serve a te (e al ripristino) per sapere che cosa c'è dentro.
  {
    echo "creato=$(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "tipo=$tipo"
    echo "postgres=$(pg_dump --version)"
    echo "migrazione=$(psql -tAc 'SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1' 2>/dev/null || true)"
  } >"$lavoro/manifest.txt"
  # 4. Le impronte SHA-256 dei tre file.
  (cd "$lavoro" && sha256sum database.dump encryption.key manifest.txt >SHA256SUMS)

  # 5. Tutto in un archivio (tar), cifrato mentre si scrive (openssl enc). Prima con un nome provvisorio, poi
  #    rinominato: un backup interrotto a metà (corrente che salta) non sembra mai un backup buono.
  tar -C "$lavoro" -cf - database.dump encryption.key manifest.txt SHA256SUMS |
    openssl enc -aes-256-cbc -pbkdf2 -iter "$ITERAZIONI" -md sha256 -salt \
      -pass env:WALLETTRACKER_BACKUP_PASSWORD -out "$CARTELLA/.$nome.in-corso"
  mv "$CARTELLA/.$nome.in-corso" "$CARTELLA/$nome"
  rm -rf "$lavoro"

  log "Backup creato: $nome ($(du -h "$CARTELLA/$nome" | cut -f1))"
  if [ "$tipo" = automatico ]; then
    sfoltisci
  fi
}

# Tiene solo gli ultimi BACKUP_KEEP backup automatici (di serie 14). Quelli fatti a mano non li tocca mai.
sfoltisci() {
  local tieni=${BACKUP_KEEP:-14} vecchio
  # I nomi contengono data e ora (20261031-031500): in ordine alfabetico inverso, i più nuovi vengono prima.
  find "$CARTELLA" -maxdepth 1 -name 'wallettracker-automatico-*.backup' | sort -r | tail -n +"$((tieni + 1))" |
    while read -r vecchio; do
      rm -f "$vecchio"
      log "Tolto il backup automatico vecchio: $(basename "$vecchio")"
    done
}

# --- apri: decifra e controlla (usato da verifica e da ripristina) ---------------------------------------------
apri() {
  local file=$1 dove=$2
  [ -f "$file" ] || errore 2 "Non trovo il file $file (deve stare nella cartella dei backup)."
  [ -n "$WALLETTRACKER_BACKUP_PASSWORD" ] || errore 4 "Manca la password dei backup."
  # Con la password sbagliata, di solito openssl se ne accorge subito ("bad decrypt"); in rari casi produce byte
  # senza senso, e allora se ne accorge tar. In tutti e due i casi: codice 3.
  if ! openssl enc -d -aes-256-cbc -pbkdf2 -iter "$ITERAZIONI" -md sha256 \
    -pass env:WALLETTRACKER_BACKUP_PASSWORD -in "$file" -out "$dove/archivio.tar" 2>/dev/null ||
    ! tar -C "$dove" -xf "$dove/archivio.tar" 2>/dev/null; then
    errore 3 "Password sbagliata, oppure il file non è un backup di WalletTracker (o è rovinato)."
  fi
  rm -f "$dove/archivio.tar"
  # Le impronte: se anche un solo byte del database o della chiave fosse cambiato, qui ce ne accorgiamo.
  (cd "$dove" && sha256sum -c SHA256SUMS >/dev/null 2>&1) ||
    errore 5 "Il backup si apre, ma il suo contenuto non corrisponde alle impronte: è rovinato. Usane un altro."
}

verifica() {
  local file lavoro
  file=$(percorso "${1:?manca il nome del file}")
  lavoro=$(mktemp -d)
  apri "$file" "$lavoro"
  log "Il backup $(basename "$file") è integro:"
  sed 's/^/    /' "$lavoro/manifest.txt"
  rm -rf "$lavoro"
}

# --- ripristina ------------------------------------------------------------------------------------------------
# Chi lo chiama (lo script di installazione) ha già fermato il backend e il backup automatico, e fatto una copia di
# sicurezza: qui il database deve essere fermo.
ripristina() {
  local file lavoro
  file=$(percorso "${1:?manca il nome del file}")
  lavoro=$(mktemp -d)

  # 1. Prima si apre e si controlla tutto: se il backup non va, non si è ancora toccato niente.
  apri "$file" "$lavoro"
  log "Backup integro. Dentro:"
  sed 's/^/    /' "$lavoro/manifest.txt"

  # 2. Il database: via tutto quello che c'è (lo schema "public", con tabelle, dati e la storia di Flyway), poi
  #    pg_restore ricarica il backup. --no-owner: le tabelle appartengono all'utente che ripristina, anche se il
  #    backup arriva da un'installazione con un nome utente diverso.
  log "Svuoto il database e ci carico il backup..."
  psql -q -v ON_ERROR_STOP=1 -c 'DROP SCHEMA public CASCADE' -c 'CREATE SCHEMA public'
  if ! pg_restore --no-owner --exit-on-error --dbname="$PGDATABASE" "$lavoro/database.dump"; then
    errore 2 "Il caricamento si è fermato a metà: il database è incompleto. Rilancia il ripristino; i dati di prima sono nel backup \"prima-del-ripristino\"."
  fi

  # 3. La chiave di cifratura: quella del backup, perché è quella con cui sono cifrati i segreti del database
  #    appena caricato. Se era diversa, la vecchia resta accanto, con un altro nome (non si butta mai una chiave).
  if ! cmp -s "$lavoro/encryption.key" "$CHIAVE" 2>/dev/null; then
    if [ -f "$CHIAVE" ]; then
      cp "$CHIAVE" "$CHIAVE.prima-del-ripristino-$(date +%Y%m%d-%H%M%S)"
    fi
    cat "$lavoro/encryption.key" >"$CHIAVE"
    log "Chiave di cifratura rimessa com'era nel backup (quella di prima è accanto, in .data)."
  fi
  rm -rf "$lavoro"
  log "Ripristino completato."
}

# --- automatico ------------------------------------------------------------------------------------------------
# Resta acceso. Ogni minuto controlla: è passata l'ora del backup, e oggi non ce n'è ancora uno automatico? Allora
# lo fa. Così, se alle 03:15 il computer era spento, il backup del giorno si fa appena si riaccende.
automatico() {
  local ora=${BACKUP_TIME:-03:15} avvisato=false soglia adesso oggi
  [[ "$ora" =~ ^([01][0-9]|2[0-3]):[0-5][0-9]$ ]] || errore 2 "BACKUP_TIME deve essere un'ora come 03:15 (è \"$ora\")."
  # "03:15" diventa il numero 315 (10# vuol dire "in base 10": senza, "0315" sarebbe letto come un numero ottale).
  soglia=$((10#${ora/:/}))
  # docker stop manda il segnale TERM: usciamo subito, invece di farci aspettare 10 secondi e poi chiudere a forza.
  trap 'exit 0' TERM INT
  log "Backup automatico: uno al giorno, dalle $ora; tengo gli ultimi ${BACKUP_KEEP:-14} (cartella del .env: BACKUP_DIR)."
  while true; do
    if [ ${#WALLETTRACKER_BACKUP_PASSWORD} -lt 12 ]; then
      if [ "$avvisato" = false ]; then
        log "Backup automatico SPENTO: manca la password. Per accenderlo: ./installa.sh --backup (docs/48)."
        avvisato=true
      fi
    else
      adesso=$((10#$(date +%H%M)))
      oggi=$(date +%Y%m%d)
      if [ "$adesso" -ge "$soglia" ] &&
        [ -z "$(find "$CARTELLA" -maxdepth 1 -name "wallettracker-automatico-$oggi-*.backup" | head -n 1)" ]; then
        # Tra parentesi: in un sotto-processo, così un errore (che fa "exit") non spegne il ciclo.
        if ! (crea automatico); then
          log "Backup automatico non riuscito: riprovo tra mezz'ora."
          sleep 1800 &
          wait $!
          continue
        fi
      fi
    fi
    # "sleep &" e "wait": durante l'attesa il segnale TERM arriva subito (con un sleep normale aspetterebbe la fine).
    sleep 60 &
    wait $!
  done
}

case "${1:-automatico}" in
  automatico) automatico ;;
  crea) crea "${2:-manuale}" ;;
  verifica) verifica "${2:-}" ;;
  ripristina) ripristina "${2:-}" ;;
  *) errore 2 "Comando sconosciuto: $1 (automatico, crea, verifica, ripristina)" ;;
esac
