#!/usr/bin/env bash
# Prepara lo ZIP di una versione (Fase 4, passo 6: docs/50, parte D). Lo usa .github/workflows/rilascio.yml; si può
# provare anche a mano:   .github/ci/prepara-zip.sh 1.0.0 /cartella/di/uscita
#
# Lo ZIP contiene solo quello che serve a chi installa: gli script, docker-compose.yml (con le immagini di QUESTA
# versione), .env.example, le novità e il file VERSION. Niente codice: le immagini sono già pronte su GitHub Container
# Registry. Accanto allo ZIP, SHA256SUMS con la sua impronta, che "Aggiorna" controlla prima di usarlo.
set -euo pipefail

versione=${1:?manca la versione, es. 1.0.0}
uscita=${2:-.}
[[ "$versione" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Versione non valida: $versione (es. 1.0.0)" >&2; exit 1; }
# La cartella di uscita: creata se manca, e resa "assoluta" prima di spostarsi nella radice del progetto (così un
# percorso relativo, come "prova", resta quello di chi ha lanciato lo script).
mkdir -p "$uscita"
uscita=$(cd "$uscita" && pwd)
cd "$(dirname "$0")/../.."

# Le novità di questa versione: la sezione "## 1.0.0" di NOVITA.md, fino alla sezione dopo.
awk -v v="$versione" '$0 == "## " v {dentro = 1; next} /^## / {dentro = 0} dentro' NOVITA.md >"$uscita/novita-$versione.md"
grep -q '[^[:space:]]' "$uscita/novita-$versione.md" ||
  { echo "In NOVITA.md manca la sezione \"## $versione\"" >&2; exit 1; }

cartella="$uscita/WalletTracker-$versione"
rm -rf "$cartella" "$cartella.zip"
mkdir -p "$cartella"
cp installa.sh installa.ps1 .env.example NOVITA.md "$cartella/"
echo "$versione" >"$cartella/VERSION"
# Il nome delle immagini, di serie, diventa quello di questa versione: così anche un "docker compose up -d" scritto a
# mano, senza lo script, prende le immagini giuste.
sed "s/\${WALLETTRACKER_VERSION:-sviluppo}/\${WALLETTRACKER_VERSION:-$versione}/" docker-compose.yml >"$cartella/docker-compose.yml"
[ "$(grep -c "WALLETTRACKER_VERSION:-$versione}" "$cartella/docker-compose.yml")" = 3 ] ||
  { echo "docker-compose.yml: non trovo le tre immagini da cambiare" >&2; exit 1; }

(cd "$uscita" && zip -qr "WalletTracker-$versione.zip" "WalletTracker-$versione" && sha256sum "WalletTracker-$versione.zip" >SHA256SUMS)
echo "Pronto: $uscita/WalletTracker-$versione.zip (e SHA256SUMS)"
