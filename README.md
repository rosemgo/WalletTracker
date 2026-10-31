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

`docker-compose.yml` (nella radice) avvia l'app completa: database, backend, nginx con la dashboard e, se vuoi,
Ollama (`docs/44`). `docker-compose.dev.yml` aggiunge la porta del database per lo sviluppo.

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
36. [Fase grafica, passo 3: il Riepilogo (il mese rispetto al solito, Analisi nella barra, lazy loading)](docs/36-fase-grafica-riepilogo.md)
37. [Fase grafica, passo 4: i Movimenti per giorno e il dettaglio di un movimento (simili, Annulla)](docs/37-fase-grafica-movimenti.md)
38. [Fase grafica, passo 5: Analisi, l'esploratore e la pagina di una categoria (budget con il ritmo)](docs/38-fase-grafica-analisi.md)
39. [Fase grafica, passo 6: il Budget e le pagine di "Altro" (tabelle come schede sul telefono, errori in parole)](docs/39-fase-grafica-budget-e-altro.md)
40. [L'esercente scritto nella causale: bonifici e addebiti diretti riconosciuti dal nome (migrazione V28)](docs/40-esercente-nella-causale.md)
41. [Fase grafica: un aspetto moderno, grafici vivi (Inter, campi, scelta del file, grafici animati e da scorrere)](docs/41-fase-grafica-aspetto-moderno.md)
42. [Fase grafica, passo 7: l'app installabile (PWA) e la modalità discreta](docs/42-fase-grafica-pwa-e-modalita-discreta.md)
43. [Ritocchi: "Senza categoria" porta ai movimenti, la categoria tra spese ed entrate, i menu aperti](docs/43-analisi-senza-categoria-e-menu.md)
44. [Fase 4, passo 1: l'app in tre container (Dockerfile multi-stage, nginx, Docker Compose)](docs/44-fase-4-passo-1-docker-nginx.md)
45. [Fase 4, passo 2: lo script di installazione, la password del database generata e come cambiarla](docs/45-fase-4-passo-2-installazione-e-segreti.md)
46. [Fase 4, passo 3: il PIN per aprire l'app, facoltativo dal PC (Spring Security dalle basi: filtri, sessione, CSRF, hash, blocco, codice di recupero)](docs/46-fase-4-passo-3-pin.md)
47. [Fase 4, passo 4: Tailscale, l'app dal telefono con https vero (reti, VPN e certificati dalle basi; `tailscale serve`; più indirizzi di ritorno per Enable Banking)](docs/47-fase-4-passo-4-tailscale.md)
48. [Fase 4, passo 5: backup e ripristino (pg_dump, cifratura con password, backup automatico ogni notte, ripristino provato davvero, spostare l'app su un altro computer)](docs/48-fase-4-passo-5-backup.md)

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
- [x] **Fase grafica**: app pensata per il telefono, a colpo d'occhio; il progetto è in `docs/33`, le basi in `docs/35`, il Riepilogo in `docs/36`, i Movimenti in `docs/37`, Analisi in `docs/38`, Budget e Altro in `docs/39`, l'aspetto moderno e i grafici vivi in `docs/41`, l'app installabile e la modalità discreta in `docs/42`
- [ ] **Fase 4**: installazione con un solo comando (Docker Compose), guida per chi non è informatico, accesso via Tailscale
  - [x] passo 1: tre container (`db`, `backend`, `web` con nginx) più Ollama facoltativo, avviati con `docker compose up -d` (`docs/44`)
  - [x] passo 2: lo script di installazione (`installa.ps1` / `installa.sh`): `.env` con la password del database generata a caso, cambio della password, avvio e attesa del backend (`docs/45`)
  - [x] passo 3: il PIN per aprire l'app (Spring Security), facoltativo dal PC: due porte per distinguere il PC dagli altri dispositivi, codice di recupero, sessione di 12 ore, blocco dopo 5 errori, CSRF, copia dei dati sul telefono solo a sessione aperta (`docs/46`)
  - [x] passo 4: Tailscale, l'app dal telefono con https vero: `installa --tailscale` configura `tailscale serve` verso la porta "remota", nome del PC e indirizzo di ritorno di Enable Banking nel `.env`; cookie `Secure` dal telefono, Funnel rifiutato, `X-Forwarded-Host` non più inoltrato (`docs/47`)
  - [x] passo 5: backup e ripristino (provato davvero): `installa --backup` e `--ripristina <file>`, file cifrato con una password tua (AES-256, PBKDF2), backup automatico ogni notte in un container dedicato (tiene gli ultimi 14), ripristino con conferma e copia di sicurezza, provato nella CI cancellando tutto; nella dashboard lo stato, "Fai un backup adesso", lo scaricamento solo dal server e un avviso se qualcosa non va (`docs/48`)
  - [ ] passo 6: aggiornamenti e la guida per chi non è informatico
  - da valutare (decisione rimandata): accesso dai dispositivi remoti anche con una VPN già esistente, al posto di Tailscale. Servirebbero: la porta remota pubblicata solo sull'indirizzo della VPN (mai `0.0.0.0` né l'indirizzo della rete di casa), un certificato https per il nome scelto, quel nome in `WALLETTRACKER_ALLOWED_HOSTS` e negli indirizzi di ritorno di Enable Banking

## Comandi utili

Dalla radice del progetto:

```bash
./installa.sh                                 # primo avvio e avvii successivi: crea il .env se manca e avvia (docs/45)
                                              # su Windows: powershell -ExecutionPolicy Bypass -File installa.ps1
docker compose up -d --build                  # l'app completa su http://localhost:8000 (docs/44)
docker compose --profile ia up -d --build     # la stessa, con anche Ollama (l'IA locale)
docker compose down                           # ferma tutto (i dati restano)

# Per lo sviluppo: solo il database, raggiungibile dal PC (poi backend con ./mvnw e dashboard con npm start).
# Non insieme all'app completa: due backend leggerebbero due volte le banche.
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d db
```

I test del backend non hanno bisogno di Compose (usano Testcontainers), ma Docker Desktop deve essere avviato.

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
npm run pwa                    # la build di produzione su http://localhost:4300, installabile come app (docs/42)
npm test -- --watch=false      # esegue i test una volta
```

## Sicurezza

La sicurezza ha la precedenza su ogni funzione: modello, protezioni attive e piano sono in
[`docs/17-sicurezza.md`](docs/17-sicurezza.md); per segnalare una vulnerabilità vedi [`SECURITY.md`](SECURITY.md).

Non fare mai commit di `.env`, della cartella `secrets/` o di file `.pem`: il `.gitignore` li esclude,
ma controlla sempre con `git status` prima di un commit.
