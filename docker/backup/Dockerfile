# Il container "backup" di WalletTracker (Fase 4, passo 5: docs/48).
#
# Parte dall'immagine di PostgreSQL 18, la stessa del container "db": così pg_dump e pg_restore sono della stessa
# versione del database (un pg_dump più vecchio del server si rifiuterebbe di lavorare). Il server PostgreSQL che
# l'immagine contiene qui non si avvia mai: ci servono solo i suoi programmi.
FROM postgres:18-alpine

# OpenSSL, per cifrare il backup con la password (AES-256). È l'unica cosa che manca all'immagine di PostgreSQL.
RUN apk add --no-cache openssl

COPY wallettracker-backup.sh /usr/local/bin/wallettracker-backup
RUN chmod 755 /usr/local/bin/wallettracker-backup

# Lo stesso utente del backend (id 1000, docs/44 parte B2): così legge e scrive la chiave di cifratura in .data,
# che il backend ha creato con i permessi "solo il proprietario". Mai root.
USER 1000:1000

# Il comando fisso è lo script; quello di serie, "automatico", resta acceso e fa il backup ogni notte. Lo script di
# installazione lo usa anche con altri comandi: docker compose run --rm backup crea manuale (o ripristina <file>).
ENTRYPOINT ["wallettracker-backup"]
CMD ["automatico"]
