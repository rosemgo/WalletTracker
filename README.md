# WalletTracker

Progetto personale (e didattico) per tracciare le spese in automatico: i movimenti vengono letti
direttamente dalle banche (Revolut, ING, e altre in futuro) tramite l'open banking europeo (PSD2)
e mostrati in una dashboard con elenco movimenti, categorie e grafici.

## Architettura (obiettivo finale)

```
  Revolut ──┐
            ├──► Enable Banking (fornitore autorizzato PSD2)
  ING ──────┘            │ HTTPS + JWT
                         ▼
  ┌────────────── Docker Compose (server di casa o VPS) ─────────────┐
  │                                                                  │
  │   backend (Spring Boot)                                          │
  │    ├─ client Enable Banking  ◄── Fase 0 (sei qui)                │
  │    ├─ sincronizzazione ogni 6 ore: scarica, normalizza,          │
  │    │  elimina i doppioni, categorizza, riconosce i giroconti     │
  │    └─ API REST per la dashboard                                  │
  │          │                       ▲                               │
  │          ▼                       │                               │
  │     PostgreSQL              frontend (React)                     │
  └──────────────────────────────────▲───────────────────────────────┘
                                     │ accesso solo via VPN (Tailscale)
                                tu (PC / telefono)
```

## Tecnologie

| Livello | Scelta |
|---|---|
| Backend | Java 21, Spring Boot 4, Maven |
| Database | PostgreSQL (dalla Fase 1), migrazioni con Flyway |
| Frontend | React + TypeScript (dalla Fase 2) |
| Accesso alle banche | [Enable Banking](https://enablebanking.com), modalità gratuita per uso personale |
| Esecuzione | Docker Compose |

Perché queste scelte:
- **Java + Spring Boot**: è il linguaggio che conosci meglio e Spring Boot è lo standard per i backend
  Java. Così puoi concentrarti su architettura, Docker e frontend invece che su un linguaggio nuovo.
- **Enable Banking**: è un fornitore autorizzato PSD2, gratuito se colleghi solo i tuoi conti.
  Senza un fornitore del genere un privato non può collegarsi alle API delle banche.

## Struttura del repository

```
backend/    applicazione Spring Boot
docs/       guide passo passo
secrets/    chiave privata di Enable Banking (ignorata da Git)
.env        configurazione personale (ignorato da Git; parti da .env.example)
```

Le cartelle `frontend/` e `docker-compose.yml` arriveranno nelle fasi successive.

## Guide

1. [Installazione e setup dell'ambiente](docs/01-installazione-e-setup.md)
2. [Fase 0: collegamento alla banca](docs/02-fase-0-collegamento-banca.md)

## Piano di lavoro

- [ ] **Fase 0**: prova di collegamento con Revolut e ING (programma a riga di comando)
- [ ] **Fase 1**: modello dati, PostgreSQL in Docker, sincronizzazione automatica
- [ ] **Fase 2**: API REST e frontend React con l'elenco dei movimenti, categorie a regole, giroconti
- [ ] **Fase 3**: grafici, budget, abbonamenti ricorrenti, avvisi di scadenza del consenso, import CSV
- [ ] **Fase 4**: tutto in Docker Compose, installazione su un server, accesso via Tailscale

## Comandi utili

Dalla cartella `backend/` (su Windows usa `mvnw.cmd` al posto di `./mvnw`):

```bash
./mvnw test                                           # esegue i test
./mvnw spring-boot:run -Dspring-boot.run.profiles=poc # avvia la prova della Fase 0
```

## Sicurezza

Non fare mai commit di `.env`, della cartella `secrets/` o di file `.pem`: il `.gitignore` li esclude,
ma controlla sempre con `git status` prima di un commit.
