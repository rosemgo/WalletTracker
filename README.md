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

## Il modello: una installazione per persona

Come Firefly III e Actual Budget, WalletTracker è *self-hosted*: chi vuole usarlo scarica il progetto
da GitHub, crea la propria applicazione (gratuita) su Enable Banking e lo avvia sul proprio computer o
server. Ognuno ha i propri conti, le proprie regole e il proprio database. I principi sono descritti in
[`docs/03-modello-e-regole.md`](docs/03-modello-e-regole.md).

## Struttura del repository

```
backend/    applicazione Spring Boot
docs/       guide passo passo
secrets/    chiave privata di Enable Banking (ignorata da Git)
.env        configurazione personale (ignorato da Git; parti da .env.example)
```

`docker-compose.yml` (nella radice) avvia il database PostgreSQL. La cartella `frontend/` arriverà nelle fasi successive.

## Guide

1. [Installazione e setup dell'ambiente](docs/01-installazione-e-setup.md)
2. [Fase 0: collegamento alla banca](docs/02-fase-0-collegamento-banca.md)
3. [Modello e regole: le decisioni prese](docs/03-modello-e-regole.md)
4. [Fase 1, passo 1: PostgreSQL con Docker](docs/04-fase-1-passo-1-postgres-docker.md)
5. [Fase 1, passo 2: Spring Boot + PostgreSQL + Flyway](docs/05-fase-1-passo-2-spring-jpa-flyway.md)
6. [Fase 1, passo 3: i movimenti, senza doppioni](docs/06-fase-1-passo-3-movimenti.md)
7. [Fase 1, passo 4: la classificazione automatica](docs/07-fase-1-passo-4-classificazione.md)

## Piano di lavoro

- [x] **Fase 0**: prova di collegamento con ING, Revolut, Fineco e Trade Republic (programma a riga di comando)
- [ ] **Fase 1**: modello dati, PostgreSQL in Docker, sincronizzazione automatica
  - [x] passo 1: PostgreSQL con Docker Compose
  - [x] passo 2: Spring Boot + Flyway + collegamenti e conti salvati nel database
  - [x] passo 3: movimenti e importazione senza doppioni
  - [x] passo 4: classificazione automatica (motore generico + regole configurabili)
  - [ ] passo 5: sincronizzazione automatica programmata
- [ ] **Fase 2**: server web, API REST e dashboard React
  - configurazione guidata dalla dashboard: credenziali Enable Banking, collegamento delle banche, ruoli dei conti
  - elenco movimenti con correzione manuale del tipo, gestione delle regole
- [ ] **Fase 3**: grafici, budget, abbonamenti ricorrenti, avvisi di scadenza del consenso, import CSV (es. esportazione di Trade Republic)
- [ ] **Fase 4**: installazione con un solo comando (Docker Compose), guida per chi non è informatico, accesso via Tailscale

## Comandi utili

Dalla radice del progetto:

```bash
docker compose up -d      # avvia il database (serve anche ai test: Docker Desktop deve essere avviato)
```

Dalla cartella `backend/` (su Windows usa `mvnw.cmd` al posto di `./mvnw`):

```bash
./mvnw test                                           # esegue i test
./mvnw spring-boot:run -Dspring-boot.run.profiles=poc # collega una banca o riusa un collegamento salvato
```

## Sicurezza

Non fare mai commit di `.env`, della cartella `secrets/` o di file `.pem`: il `.gitignore` li esclude,
ma controlla sempre con `git status` prima di un commit.
