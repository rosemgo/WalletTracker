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
  │    ├─ client Enable Banking                                      │
  │    ├─ sincronizzazione ogni 6 ore: scarica, normalizza,          │
  │    │  elimina i doppioni, categorizza, riconosce i giroconti     │
  │    └─ API REST per la dashboard                                  │
  │          │                       ▲                               │
  │          ▼                       │                               │
  │     PostgreSQL              frontend (Angular)                   │
  └──────────────────────────────────▲───────────────────────────────┘
                                     │ accesso solo via VPN (Tailscale)
                                tu (PC / telefono)
```

## Tecnologie

| Livello | Scelta |
|---|---|
| Backend | Java 21, Spring Boot 4, Maven |
| Database | PostgreSQL (dalla Fase 1), migrazioni con Flyway |
| Frontend | Angular + TypeScript (dalla Fase 2) |
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
.github/    pipeline di integrazione continua (GitHub Actions): build e test a ogni push
backend/    applicazione Spring Boot
frontend/   dashboard Angular
docs/       guide passo passo
secrets/    chiave privata di Enable Banking (ignorata da Git)
.env        configurazione personale (ignorato da Git; parti da .env.example)
```

`docker-compose.yml` (nella radice) avvia il database PostgreSQL.

## Guide

1. [Installazione e setup dell'ambiente](docs/01-installazione-e-setup.md)
2. [Fase 0: collegamento alla banca](docs/02-fase-0-collegamento-banca.md)
3. [Modello e regole: le decisioni prese](docs/03-modello-e-regole.md)
4. [Fase 1, passo 1: PostgreSQL con Docker](docs/04-fase-1-passo-1-postgres-docker.md)
5. [Fase 1, passo 2: Spring Boot + PostgreSQL + Flyway](docs/05-fase-1-passo-2-spring-jpa-flyway.md)
6. [Fase 1, passo 3: i movimenti, senza doppioni](docs/06-fase-1-passo-3-movimenti.md)
7. [Fase 1, passo 4: la classificazione automatica](docs/07-fase-1-passo-4-classificazione.md)
8. [Fase 1, passo 5: la sincronizzazione automatica](docs/08-fase-1-passo-5-sincronizzazione.md)
9. [Fase 2, passo 1: il server web e le API REST](docs/09-fase-2-passo-1-api-rest.md)
10. [La carta di credito: il saldo non è una spesa](docs/10-carta-di-credito.md)
11. [Integrazione continua con GitHub Actions](docs/11-ci-github-actions.md)
12. [Fase 2, passo 2: la dashboard Angular](docs/12-fase-2-passo-2-dashboard-angular.md)
13. [Fase 2, passo 3: collegare le banche dalla dashboard e "Aggiorna ora"](docs/13-fase-2-passo-3-collega-banca.md)
14. [Angular da zero: capire il frontend di WalletTracker](docs/14-guida-angular-da-zero.md)
15. [Fase 2, passo 4: le regole dalla dashboard](docs/15-fase-2-passo-4-regole.md)
16. [Fase 2, passo 5: la configurazione guidata di Enable Banking](docs/16-fase-2-passo-5-configurazione-guidata.md)
17. [La sicurezza di WalletTracker: modello, protezioni, piano](docs/17-sicurezza.md)
18. [Fase 3, passo 1: il riepilogo mensile e il primo grafico](docs/18-fase-3-passo-1-riepilogo.md)
19. [Fase 3, passo 2: le categorie, le regole dalle correzioni e i rimborsi](docs/19-fase-3-passo-2-categorie.md)
20. [Fase 3, passo 3: i budget mensili](docs/20-fase-3-passo-3-budget.md)
21. [Fase 3, passo 4: gli avvisi (consenso in scadenza, sincronizzazioni fallite, budget superati)](docs/21-fase-3-passo-4-avvisi.md)
22. [Fase 3, passo 5: gli abbonamenti ricorrenti](docs/22-fase-3-passo-5-abbonamenti.md)
23. [Fase 3, passo 5b: l'elenco delle categorie (menu a discesa, rinomina a cascata)](docs/23-categorie-standard.md)
24. [Le categorie collegate per id: normalizzazione, `@ManyToOne`, `JOIN FETCH`](docs/24-categorie-per-id.md)
25. [Fase 3, passo 6: l'import CSV e i conti manuali (es. Trade Republic)](docs/25-fase-3-passo-6-import-csv.md)
26. [Fase 3, passo 7a: i suggerimenti di categoria dalla tua storia](docs/26-fase-3-passo-7a-suggerimenti.md)
27. [Fase 3, passo 7b: l'intelligenza artificiale con Spring AI e Ollama](docs/27-fase-3-passo-7b-ia-spring-ai.md)
28. [Fase 3, passo 7b.2: il modello che sceglie la categoria con Spring AI, e la misura](docs/28-fase-3-passo-7b2-modello-e-misura.md)
29. [Fase 3, passo 7b.3: "Chiedi all'IA", i suggerimenti dell'intelligenza artificiale nei Movimenti](docs/29-fase-3-passo-7b3-chiedi-all-ia.md)
30. [Fase 3, passo 7c: l'IA nel cloud, con Google Gemini (chiave cifrata, consenso)](docs/30-fase-3-passo-7c-ia-nel-cloud.md)
31. [Fase 3: completare i movimenti di Trade Republic con il suo file](docs/31-fase-3-trade-republic-completa-movimenti.md)
32. [La ricerca nei movimenti: testo, categoria, importo](docs/32-ricerca-movimenti.md)
33. [Fase grafica, passo 1: il progetto (funzionalità, schermate, grafici, stile)](docs/33-fase-grafica-progetto.md)
34. [La nota di un movimento (scriverla, vederla, ritrovarla con la ricerca)](docs/34-note-movimenti.md)
35. [Fase grafica, passo 2: le basi (carattere, temi giorno e notte, navigazione per telefono e PC)](docs/35-fase-grafica-le-basi.md)

## Piano di lavoro

- [x] **Fase 0**: prova di collegamento con ING, Revolut, Fineco e Trade Republic (programma a riga di comando)
- [x] **Fase 1**: modello dati, PostgreSQL in Docker, sincronizzazione automatica
  - [x] passo 1: PostgreSQL con Docker Compose
  - [x] passo 2: Spring Boot + Flyway + collegamenti e conti salvati nel database
  - [x] passo 3: movimenti e importazione senza doppioni
  - [x] passo 4: classificazione automatica (motore generico + regole configurabili)
  - [x] passo 5: sincronizzazione automatica programmata
- [x] **Fase 2**: server web, API REST e dashboard Angular
  - [x] passo 1: server web e API REST (conti, movimenti, ruoli, correzioni manuali)
  - [x] passo 2: dashboard Angular: conti, ruoli, elenco movimenti con correzione del tipo
  - [x] passo 3: collegamento delle banche dalla dashboard e pulsante "Aggiorna ora"
  - [x] passo 4: gestione delle regole dalla dashboard (con prova prima di salvare)
  - [x] passo 5: configurazione guidata delle credenziali Enable Banking (pagina Impostazioni)
- [x] **Fase 3**: grafici, budget, abbonamenti ricorrenti, avvisi di scadenza del consenso, import CSV (es. esportazione di Trade Republic); il piano a passi è in `docs/18`
  - [x] riepilogo mensile con grafico entrate/spese
  - [x] categorie: scelta a mano, "crea una regola da questa correzione", rimborsi, spese per categoria
  - [x] budget mensili per categoria, con stato e ritmo di spesa
  - [x] avvisi: consenso in scadenza o scaduto (con "Ricollega"), sincronizzazioni fallite, budget superati
  - [x] abbonamenti ricorrenti: suggeriti dal programma, confermati dall'utente, con rincari e disdette
  - [x] elenco delle categorie: di partenza e modificabile, menu a discesa ordinati per tipo, integrità garantita dal database
  - [x] categorie collegate per id (soluzione normalizzata), senza query N+1
  - [x] import CSV nei conti manuali (es. Trade Republic): mappatura proposta e anteprima, tutto o niente, senza doppioni
  - [x] file di Trade Republic anche per il conto collegato: completa causale e controparte dei movimenti già arrivati e aggiunge i mesi prima dello storico della banca, con anteprima e regole di partenza per le sue causali (`docs/31`)
  - [x] suggerimenti di categoria dalla tua storia: stesso esercente, stessa categoria, accetti o rifiuti (passo 7a)
  - [x] suggerimenti di categoria con l'intelligenza artificiale per gli esercenti mai visti, con Spring AI: modello locale con Ollama (passo 7b: 7b.1 fatto, impostazioni e scaricamento del modello; 7b.2 fatto, il modello che sceglie la categoria e la misura della precisione; 7b.3 fatto, "Chiedi all'IA" nei Movimenti) e servizio cloud (passo 7c fatto: Google Gemini)
- [ ] **Fase grafica**: app pensata per il telefono, a colpo d'occhio; il progetto è in `docs/33`, le basi in `docs/35`
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
./mvnw spring-boot:run                                # server web (API su http://localhost:8080) e sincronizzazione automatica; Ctrl+C per fermarlo
```

Dalla cartella `frontend/` (serve Node.js 24 LTS):

```bash
npm install                    # la prima volta, e dopo ogni modifica a package.json
npm start                      # la dashboard su https://localhost:8443 (il backend deve essere avviato)
npm test -- --watch=false      # esegue i test una volta
```

## Sicurezza

La sicurezza ha la precedenza su ogni funzione: modello, protezioni attive e piano sono in
[`docs/17-sicurezza.md`](docs/17-sicurezza.md); per segnalare una vulnerabilità vedi [`SECURITY.md`](SECURITY.md).

Non fare mai commit di `.env`, della cartella `secrets/` o di file `.pem`: il `.gitignore` li esclude,
ma controlla sempre con `git status` prima di un commit.
