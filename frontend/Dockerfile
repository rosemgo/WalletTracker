# L'immagine Docker del servizio "web" (Fase 4, docs/44 parte D): nginx con dentro la build di produzione della
# dashboard. Come per il backend, è "multi-stage":
#   1. "build": Node 24 e npm compilano Angular (ng build) in una cartella di file statici;
#   2. quella finale: solo nginx e quei file. Node, node_modules e il codice sorgente restano nella prima.
#
# Si costruisce con docker compose (docker compose build web), non a mano.

# --- 1. Compilazione ---------------------------------------------------------------------------------------
FROM node:24-alpine AS build
WORKDIR /src

# Prima solo package.json e package-lock.json, poi npm ci, poi il resto. Docker tiene in memoria ("cache") ogni
# passo: se cambia solo il codice e non le dipendenze, il passo npm ci si riusa così com'è e la build è veloce.
COPY package.json package-lock.json ./
# npm ci installa esattamente le versioni di package-lock.json. La cache di npm resta fuori dall'immagine.
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund

COPY . .
# NG_CLI_ANALYTICS=false: Angular non chiede se mandare statistiche d'uso (non c'è nessuno a rispondere).
RUN NG_CLI_ANALYTICS=false npx ng build

# --- 2. L'immagine che gira ---------------------------------------------------------------------------------
# nginx-unprivileged è nginx ufficiale, già pronto per girare senza root: ascolta sulla porta 8080 (le porte sotto
# la 1024 le può aprire solo root) e scrive i suoi file temporanei in /tmp.
FROM nginxinc/nginx-unprivileged:1.29-alpine

COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /src/dist/frontend/browser /usr/share/nginx/html

EXPOSE 8080
