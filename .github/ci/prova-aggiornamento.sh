#!/usr/bin/env bash
# La prova di "Aggiorna" (Fase 4, passo 6b: docs/50, parte I), con un FINTO GitHub sul computer stesso. La usa la CI
# (job "Aggiornamento"); si può lanciare anche a mano, dalla radice del progetto, con Docker acceso:
#   .github/ci/prova-aggiornamento.sh [cartella-di-lavoro]
#
# Tre versioni finte, costruite dal codice di adesso:
#   0.9.0  quella installata all'inizio, da uno ZIP come lo scaricherebbe un utente;
#   0.9.1  la versione nuova, buona;
#   0.9.2  una versione ROTTA apposta: il backend non parte (una porta che non è un numero).
# I casi provati, in ordine:
#   1. con l'ultima versione già installata: "Hai già l'ultima versione", niente cambia;
#   2. uno ZIP che non corrisponde alla sua impronta (SHA256SUMS): rifiutato, niente cambia;
#   2b. lo stesso, con il servizio API di GitHub che risponde 503: lo script trova la versione dalla pagina normale
#      (che rinvia a .../releases/tag/v0.9.1), scarica dagli indirizzi ricavati, e rifiuta lo ZIP per l'impronta;
#   3. l'aggiornamento 0.9.0 → 0.9.1: backup, file nuovi, backend nuovo acceso, dati al loro posto;
#   4. l'aggiornamento 0.9.1 → 0.9.2 che fallisce: lo script torna da solo alla 0.9.1, con i dati di prima;
#   5. Enable Banking: nessun indirizzo di ritorno di serie, e --tailscale che scrive solo quello di Tailscale;
#   6. le lingue (docs/lingue-3-installazione.md): la domanda "Lingua / Language" della prima installazione, e lo
#      script e il container dei backup che passano all'inglese e tornano all'italiano.
# Le versioni 0.9.x non esistono su GitHub Container Registry: "docker compose pull" fallisce, e lo script usa le
# immagini costruite qui (lo stesso percorso di chi non ha Internet).
set -euo pipefail

radice=$(cd "$(dirname "$0")/../.." && pwd)
lavoro=${1:-$(mktemp -d)}
mkdir -p "$lavoro"
lavoro=$(cd "$lavoro" && pwd)
github="$lavoro/github"
server="$lavoro/WalletTracker"
porta_github=8765
export WALLETTRACKER_RELEASES_URL="http://127.0.0.1:$porta_github/latest.json"
export WALLETTRACKER_RELEASES_PAGE="http://127.0.0.1:$porta_github/releases/latest"
export WALLETTRACKER_ATTESA_BACKEND=90
password_backup='prova-aggiornamento-1234'

passo() { printf '\n######## %s\n' "$1"; }
fallito() {
  printf '\nPROVA FALLITA: %s\n' "$1" >&2
  exit 1
}
etichetta() { docker inspect --format '{{index .Config.Labels "org.opencontainers.image.version"}}' wallettracker-backend 2>/dev/null || true; }
salute() { docker inspect --format '{{.State.Health.Status}}' wallettracker-backend 2>/dev/null || true; }
# La cartella dei backup è dell'utente 1000 (quello dei container): un altro utente la legge con sudo.
come_1000() { if [ "$(id -u)" = 0 ] || [ "$(id -u)" = 1000 ]; then "$@"; else sudo "$@"; fi; }
sql() { (cd "$server" && docker compose exec -T db sh -c 'psql -tA -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$0"' "$1"); }

# --- Le immagini delle tre versioni, dal codice ---
passo "Costruisco le immagini 0.9.0 e 0.9.1 (e la 0.9.2 è la 0.9.1 con un altro nome)"
for v in 0.9.0 0.9.1; do
  for coppia in backend:backend web:frontend backup:docker/backup; do
    docker build -q --build-arg "WALLETTRACKER_VERSION=$v" -t "ghcr.io/rosemgo/wallettracker-${coppia%%:*}:$v" \
      "$radice/${coppia#*:}" >/dev/null
  done
done
for immagine in backend web backup; do
  docker tag "ghcr.io/rosemgo/wallettracker-$immagine:0.9.1" "ghcr.io/rosemgo/wallettracker-$immagine:0.9.2"
done

# --- Gli ZIP, preparati come per una release vera (prepara-zip.sh), da una copia con NOVITA.md di prova ---
passo "Preparo gli ZIP delle tre versioni e il finto GitHub"
copia="$lavoro/copia"
rm -rf "$copia" "$github"
mkdir -p "$copia/.github/ci" "$github"
# Tutti i file della radice del progetto (non un elenco a mano: due volte un file nuovo dello ZIP mancava qui, e
# prepara-zip.sh si fermava). -p tiene il permesso di esecuzione dei .command.
find "$radice" -maxdepth 1 -type f -exec cp -p {} "$copia/" \;
# La cartella "English" dello ZIP: il LEGGIMI e i file da doppio clic con i nomi inglesi.
cp -rp "$radice/English" "$copia/"
cp "$radice/docker-compose.yml" "$lavoro/compose-originale.yml"
cp "$radice/.github/ci/prepara-zip.sh" "$copia/.github/ci/"
cat >"$copia/NOVITA.md" <<'FINE'
# Novità (prova)

## 0.9.2

- Una versione rotta apposta: il backend non parte.

## 0.9.1

- **Una novità** di prova, con `codice`.

## 0.9.0

- La prima versione di prova.
FINE
# Le stesse novità in inglese: prepara-zip.sh vuole la sezione della versione in tutti e due i file.
cat >"$copia/CHANGELOG.md" <<'FINE'
# What is new (test)

## 0.9.2

- A version broken on purpose: the backend does not start.

## 0.9.1

- **Something new** for the test, with `code`.

## 0.9.0

- The first test version.
FINE
for v in 0.9.0 0.9.1 0.9.2; do
  cp "$lavoro/compose-originale.yml" "$copia/docker-compose.yml"
  if [ "$v" = 0.9.2 ]; then
    # La versione rotta: il backend riceve una porta che non è un numero, e Spring Boot si ferma all'avvio.
    sed -i 's/      SERVER_PORT: 8080/      SERVER_PORT: nonunnumero/' "$copia/docker-compose.yml"
    grep -q 'SERVER_PORT: nonunnumero' "$copia/docker-compose.yml" || fallito "non sono riuscito a rompere la 0.9.2"
  fi
  mkdir -p "$github/download/v$v"
  "$copia/.github/ci/prepara-zip.sh" "$v" "$github/download/v$v" >/dev/null
done

# Il finto GitHub (finto-github.py): latest.json dice qual è l'ultima versione, come api.github.com/.../releases/latest;
# "ultima-versione" la stessa cosa per la pagina normale, che rinvia a /releases/tag/vX.Y.Z.
ultima() { # $1 = versione
  echo "$1" >"$github/ultima-versione"
  cat >"$github/latest.json" <<FINE
{
  "tag_name": "v$1",
  "html_url": "https://github.com/rosemgo/WalletTracker/releases/tag/v$1",
  "body": "- prova",
  "assets": [
    { "name": "WalletTracker-$1.zip", "browser_download_url": "http://127.0.0.1:$porta_github/download/v$1/WalletTracker-$1.zip" },
    { "name": "SHA256SUMS", "browser_download_url": "http://127.0.0.1:$porta_github/download/v$1/SHA256SUMS" }
  ]
}
FINE
}
python3 "$radice/.github/ci/finto-github.py" "$porta_github" "$github" >"$lavoro/github.log" 2>&1 &
pid_github=$!
trap 'kill $pid_github 2>/dev/null || true' EXIT
sleep 1

# --- L'installazione 0.9.0, da uno ZIP, come la farebbe un utente ---
passo "Installo WalletTracker 0.9.0 da uno ZIP"
(cd "$server" 2>/dev/null && docker compose down -v >/dev/null 2>&1) || true
rm -rf "$server"
unzip -q "$github/download/v0.9.0/WalletTracker-0.9.0.zip" -d "$lavoro"
mv "$lavoro/WalletTracker-0.9.0" "$server"
cd "$server"
mkdir -p .data backup
# Il backend e il backup, nei container, sono l'utente 1000 (docs/45, parte F).
come_1000 chown 1000:1000 .data backup
cp .env.example .env
echo "WALLETTRACKER_BACKUP_PASSWORD=$password_backup" >>.env
./installa.sh --senza-browser
[ "$(etichetta)" = 0.9.0 ] || fallito "dopo l'installazione il backend non è la 0.9.0 ($(etichetta))"
# Un dato "dell'utente", per vedere che resta: una tabella di prova nel database.
sql "CREATE TABLE prova_aggiornamento (nota text); INSERT INTO prova_aggiornamento VALUES ('i miei dati');" >/dev/null

# --- 1. L'ultima versione è già quella installata ---
passo "Caso 1: hai già l'ultima versione"
ultima 0.9.0
./installa.sh --aggiorna --senza-browser </dev/null | tee "$lavoro/caso1.log"
grep -q "Hai già l'ultima versione: WalletTracker 0.9.0" "$lavoro/caso1.log" || fallito "caso 1: manca il messaggio"
[ "$(cat VERSION)" = 0.9.0 ] || fallito "caso 1: VERSION è cambiato"

# --- 2. Uno ZIP che non corrisponde alla sua impronta ---
passo "Caso 2: uno ZIP manomesso viene rifiutato"
ultima 0.9.1
cp "$github/download/v0.9.1/SHA256SUMS" "$lavoro/SHA256SUMS.buono"
echo "0000000000000000000000000000000000000000000000000000000000000000  WalletTracker-0.9.1.zip" >"$github/download/v0.9.1/SHA256SUMS"
if echo s | ./installa.sh --aggiorna --senza-browser >"$lavoro/caso2.log" 2>&1; then
  cat "$lavoro/caso2.log"
  fallito "caso 2: lo ZIP manomesso è stato accettato"
fi
cat "$lavoro/caso2.log"
grep -q "NON corrisponde alla sua impronta" "$lavoro/caso2.log" || fallito "caso 2: manca il messaggio dell'impronta"
[ "$(cat VERSION)" = 0.9.0 ] && [ "$(etichetta)" = 0.9.0 ] || fallito "caso 2: qualcosa è cambiato"
[ ! -d .aggiornamento/precedente ] || fallito "caso 2: i file sono stati toccati"
come_1000 ls backup | grep -q prima-dell-aggiornamento && fallito "caso 2: il backup non doveva nemmeno partire"

# --- 2b. Il servizio API di GitHub non risponde: la seconda strada, dalla pagina delle versioni ---
# Come il 2 (l'impronta è ancora quella sbagliata), ma latest.json risponde 503. Se lo script arriva a rifiutare lo ZIP
# per l'impronta, vuol dire che ha trovato la versione dal rinvio della pagina e scaricato i file dagli indirizzi giusti.
passo "Caso 2b: il servizio API risponde 503, la versione si trova dalla pagina"
touch "$github/api-rotta"
if echo s | ./installa.sh --aggiorna --senza-browser >"$lavoro/caso2b.log" 2>&1; then
  cat "$lavoro/caso2b.log"
  fallito "caso 2b: lo ZIP manomesso è stato accettato"
fi
cat "$lavoro/caso2b.log"
grep -q "provo dalla pagina delle versioni" "$lavoro/caso2b.log" || fallito "caso 2b: non ha provato la seconda strada"
grep -q "C'è una versione nuova: 0.9.1" "$lavoro/caso2b.log" || fallito "caso 2b: non ha trovato la versione dalla pagina"
grep -q "NON corrisponde alla sua impronta" "$lavoro/caso2b.log" || fallito "caso 2b: non è arrivato al controllo dell'impronta"
grep -q "GET /releases/download/v0.9.1/WalletTracker-0.9.1.zip" "$lavoro/github.log" ||
  fallito "caso 2b: lo ZIP non è stato scaricato dall'indirizzo ricavato dalla pagina"
[ "$(cat VERSION)" = 0.9.0 ] && [ "$(etichetta)" = 0.9.0 ] || fallito "caso 2b: qualcosa è cambiato"
# E se anche la pagina non risponde: un errore chiaro, e niente cambia.
mv "$github/ultima-versione" "$github/ultima-versione.via"
if echo s | ./installa.sh --aggiorna --senza-browser >"$lavoro/caso2c.log" 2>&1; then
  cat "$lavoro/caso2c.log"
  fallito "caso 2b: senza API né pagina l'aggiornamento è andato avanti"
fi
grep -q "né dal servizio API né dalla pagina delle versioni" "$lavoro/caso2c.log" || {
  cat "$lavoro/caso2c.log"
  fallito "caso 2b: senza API né pagina manca il messaggio"
}
mv "$github/ultima-versione.via" "$github/ultima-versione"
rm "$github/api-rotta"
cp "$lavoro/SHA256SUMS.buono" "$github/download/v0.9.1/SHA256SUMS"

# --- 3. L'aggiornamento riuscito ---
passo "Caso 3: aggiornamento da 0.9.0 a 0.9.1"
echo s | ./installa.sh --aggiorna --senza-browser | tee "$lavoro/caso3.log"
grep -q "Una novità di prova, con codice." "$lavoro/caso3.log" || fallito "caso 3: le novità non sono state mostrate (o con i segni di Markdown)"
grep -q "Aggiornamento completato: WalletTracker 0.9.1 è avviato" "$lavoro/caso3.log" || fallito "caso 3: manca il messaggio finale"
[ "$(cat VERSION)" = 0.9.1 ] || fallito "caso 3: VERSION non è 0.9.1"
[ "$(etichetta)" = 0.9.1 ] && [ "$(salute)" = healthy ] || fallito "caso 3: il backend non è la 0.9.1 acceso ($(etichetta), $(salute))"
grep -q 'WALLETTRACKER_VERSION:-0.9.1}' docker-compose.yml || fallito "caso 3: docker-compose.yml non è quello nuovo"
[ -x "Avvia WalletTracker.command" ] && [ -f "Avvia WalletTracker.cmd" ] || fallito "caso 3: mancano i file da doppio clic"
[ -x "English/Start WalletTracker.command" ] && [ -f "English/Update WalletTracker.cmd" ] && [ -f English/README.txt ] && [ -f CHANGELOG.md ] ||
  fallito "caso 3: mancano i file in inglese (la cartella English, CHANGELOG.md)"
# Il .env di questa prova è stato copiato da .env.example: la lingua non c'è, quindi lo script parla italiano (come per
# chi aggiorna da una versione di prima delle lingue) e non la scrive.
if grep -q '^WALLETTRACKER_LANGUAGE=' .env; then fallito "caso 3: lo script ha scritto la lingua in un .env che non l'aveva"; fi
[ "$(cat .aggiornamento/precedente/VERSION)" = 0.9.0 ] || fallito "caso 3: la 0.9.0 non è stata messa da parte"
come_1000 ls backup | grep -q '^wallettracker-prima-dell-aggiornamento-.*\.backup$' || fallito "caso 3: manca il backup di prima dell'aggiornamento"
grep -q "^WALLETTRACKER_BACKUP_PASSWORD=$password_backup$" .env || fallito "caso 3: il .env è cambiato"
[ "$(sql 'SELECT nota FROM prova_aggiornamento')" = "i miei dati" ] || fallito "caso 3: i dati non ci sono più"

# --- 4. L'aggiornamento che fallisce, e il ritorno da solo alla versione di prima ---
passo "Caso 4: la 0.9.2 non parte, si torna da soli alla 0.9.1"
ultima 0.9.2
sql "INSERT INTO prova_aggiornamento VALUES ('aggiunto prima del caso 4');" >/dev/null
if echo s | ./installa.sh --aggiorna --senza-browser >"$lavoro/caso4.log" 2>&1; then
  cat "$lavoro/caso4.log"
  fallito "caso 4: lo script dice che è andato tutto bene"
fi
cat "$lavoro/caso4.log"
grep -q "Sono tornato a WalletTracker 0.9.1, con i dati di prima dell'aggiornamento" "$lavoro/caso4.log" ||
  fallito "caso 4: manca il messaggio del ritorno"
[ "$(cat VERSION)" = 0.9.1 ] || fallito "caso 4: VERSION non è tornato 0.9.1"
grep -q 'SERVER_PORT: 8080' docker-compose.yml || fallito "caso 4: docker-compose.yml non è tornato quello di prima"
[ "$(etichetta)" = 0.9.1 ] && [ "$(salute)" = healthy ] || fallito "caso 4: il backend 0.9.1 non è acceso ($(etichetta), $(salute))"
[ "$(sql 'SELECT count(*) FROM prova_aggiornamento')" = 2 ] || fallito "caso 4: i dati non sono quelli di prima"
[ -f .env ] && grep -q "^WALLETTRACKER_BACKUP_PASSWORD=$password_backup$" .env || fallito "caso 4: il .env è cambiato"

# --- 5. Enable Banking e Tailscale, in una versione scaricata (docs/47, parte Q) ---
passo "Caso 5: da uno ZIP nessun indirizzo di ritorno, poi --tailscale scrive solo quello di Tailscale"
# Dal .env.example della versione nessun indirizzo di ritorno (la riga è un commento): l'app è partita lo stesso, in
# tutti i casi qui sopra.
if grep -q '^ENABLE_BANKING_REDIRECT_URL=' .env; then fallito "caso 5: il .env ha già un indirizzo di ritorno"; fi
[ "$(salute)" = healthy ] || fallito "caso 5: il backend senza indirizzi di ritorno non è acceso"
PATH="$radice/.github/ci:$PATH" ./installa.sh --tailscale --senza-browser >"$lavoro/caso5.log" 2>&1 ||
  { cat "$lavoro/caso5.log"; fallito "caso 5: --tailscale non è riuscito"; }
grep -q '^ENABLE_BANKING_REDIRECT_URL=https://pc-casa.tail0000.ts.net/callback$' .env ||
  { grep '^ENABLE_BANKING_REDIRECT_URL' .env; fallito "caso 5: l'indirizzo di ritorno non è solo quello di Tailscale"; }

# --- 6. Le lingue (docs/lingue-3-installazione.md) ---
passo "Caso 6: la domanda sulla lingua, e lo script in inglese"
# 6a. La domanda arriva solo alla prima installazione (senza .env) e solo da un terminale vero ("script" ne simula uno).
#     Si prova in una cartella a parte, con un finto docker che non risponde: la domanda viene prima di Docker, quindi
#     si vede la domanda, poi l'errore di Docker nella lingua scelta. Niente viene avviato.
domanda="$lavoro/domanda"
rm -rf "$domanda" "$lavoro/finto-docker"
mkdir -p "$domanda" "$lavoro/finto-docker"
unzip -q "$github/download/v0.9.1/WalletTracker-0.9.1.zip" -d "$domanda"
printf '#!/bin/sh\nexit 1\n' >"$lavoro/finto-docker/docker"
chmod +x "$lavoro/finto-docker/docker"
(
  cd "$domanda/WalletTracker-0.9.1"
  printf '2\n' | PATH="$lavoro/finto-docker:$PATH" script -qec '"./English/Start WalletTracker.command"' /dev/null >"$lavoro/caso6-en.log" 2>&1 || true
  printf '1\n' | PATH="$lavoro/finto-docker:$PATH" script -qec './installa.sh' /dev/null >"$lavoro/caso6-it.log" 2>&1 || true
  PATH="$lavoro/finto-docker:$PATH" ./installa.sh </dev/null >"$lavoro/caso6-senza.log" 2>&1 || true
)
cat "$lavoro/caso6-en.log"
grep -q "Lingua / Language" "$lavoro/caso6-en.log" || fallito "caso 6: la domanda sulla lingua non è arrivata"
grep -q "==> Checking Docker" "$lavoro/caso6-en.log" && grep -q "ERROR: Docker does not answer" "$lavoro/caso6-en.log" ||
  fallito "caso 6: dopo aver risposto 2 lo script non parla inglese"
grep -q "Press Enter to close this window." "$lavoro/caso6-en.log" || fallito "caso 6: il file da doppio clic inglese non ha aspettato Invio"
if grep -q "Controllo Docker" "$lavoro/caso6-en.log"; then fallito "caso 6: dopo aver risposto 2 c'è ancora dell'italiano"; fi
grep -q "Lingua / Language" "$lavoro/caso6-it.log" && grep -q "ERRORE: Docker non risponde" "$lavoro/caso6-it.log" ||
  fallito "caso 6: dopo aver risposto 1 lo script non parla italiano"
# Senza un terminale non può chiedere: niente domanda, e italiano, come è sempre stato.
if grep -q "Lingua / Language" "$lavoro/caso6-senza.log"; then fallito "caso 6: la domanda è arrivata senza un terminale"; fi
grep -q "ERRORE: Docker non risponde" "$lavoro/caso6-senza.log" || fallito "caso 6: senza terminale lo script non parla italiano"

# 6b. L'installazione vera passa all'inglese con --language: lo script, il backup (le righe del container) e la lingua
#     che il backend propone alla dashboard. Poi torna all'italiano.
./installa.sh --language en --no-browser | tee "$lavoro/caso6b.log"
grep -q "From now on this script speaks English" "$lavoro/caso6b.log" || fallito "caso 6: --language en non lo dice"
grep -q "WalletTracker 0.9.1 is running" "$lavoro/caso6b.log" || fallito "caso 6: lo script non parla inglese"
if grep -q "è avviato" "$lavoro/caso6b.log"; then fallito "caso 6: in inglese c'è ancora dell'italiano"; fi
grep -qx 'WALLETTRACKER_LANGUAGE=en' .env || fallito "caso 6: la lingua non è nel .env"
ultima 0.9.1
./installa.sh --update --no-browser </dev/null | tee "$lavoro/caso6c.log"
grep -q "You already have the latest version: WalletTracker 0.9.1" "$lavoro/caso6c.log" || fallito "caso 6: --update non parla inglese"
./installa.sh --backup --no-browser | tee "$lavoro/caso6d.log"
grep -q "Backup created: wallettracker-manuale-" "$lavoro/caso6d.log" || fallito "caso 6: il container dei backup non parla inglese"
grep -q "The backup is in the folder" "$lavoro/caso6d.log" || fallito "caso 6: il backup in inglese non è finito"
# La lingua scelta installando è una proposta per la dashboard, finché nessuno sceglie dall'app (qui nessuno è entrato).
curl -fsS http://localhost:8000/api/language | tee "$lavoro/caso6-lingua.json"
grep -q '"language":null' "$lavoro/caso6-lingua.json" && grep -q '"suggested":"en"' "$lavoro/caso6-lingua.json" ||
  fallito "caso 6: il backend non propone l'inglese alla dashboard"
./installa.sh --lingua it --senza-browser | tee "$lavoro/caso6e.log"
grep -q "Da adesso questo script parla italiano" "$lavoro/caso6e.log" && grep -q "WalletTracker 0.9.1 è avviato" "$lavoro/caso6e.log" ||
  fallito "caso 6: --lingua it non riporta all'italiano"

passo "Tutte le prove dell'aggiornamento sono passate"
