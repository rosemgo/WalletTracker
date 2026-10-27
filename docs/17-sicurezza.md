# 17. La sicurezza di WalletTracker

WalletTracker gestisce **dati bancari**. L'obiettivo è che chi lo installa, o chi ne legge il codice, non
possa criticarlo né attaccarlo facilmente. Da qui in avanti vale il principio **"shift left"** (*sposta a
sinistra*): la sicurezza si pensa **all'inizio** di ogni passo, insieme al codice, e non alla fine. Correggere
un problema di sicurezza dopo, quando il progetto è già usato da altri, costa molto di più.

Questo documento è il **modello di sicurezza** del progetto:
- cosa proteggiamo, e da chi;
- cosa è già attivo;
- cosa è pianificato, e in quale fase.

Va aggiornato a ogni passo. Le vulnerabilità si segnalano come descritto in [`SECURITY.md`](../SECURITY.md).

---

## 1. Cosa proteggiamo

| Bene | Perché è prezioso |
|---|---|
| **chiave privata di Enable Banking** (`.pem`) | con la chiave e una sessione valida si leggono i tuoi conti |
| **sessioni** (`session_id`, nel database) | da sole non bastano: servono anche la chiave privata e il JWT |
| **movimenti, saldi, IBAN, nomi** | dati personali e finanziari (GDPR) |
| **password del database** (`.env`) | dà accesso a tutto il database |
| **chiave di cifratura** (`.data/encryption.key`) | decifra i segreti salvati nel database |

## 2. Da chi (le minacce)

| Chi | Cosa potrebbe fare |
|---|---|
| **un sito che visiti**, nello stesso browser | far partire richieste verso `localhost` (CSRF) o leggere le API con il DNS rebinding |
| **un altro dispositivo** sulla stessa rete (Wi-Fi di casa, bar, treno) | raggiungere le porte aperte del tuo PC |
| **chi ottiene una copia del database** (backup perso, disco rubato) | leggere dati e segreti salvati |
| **una libreria compromessa o vulnerabile** (*supply chain*) | eseguire codice dentro WalletTracker |
| **un errore di chi sviluppa** | pubblicare per sbaglio un segreto su GitHub |

---

## 3. Le protezioni attive

| # | Protezione | Contro cosa | Dove |
|---|---|---|---|
| 1 | backend e database ascoltano **solo su 127.0.0.1**; con Docker Compose (Fase 4) **non pubblicano nessuna porta**: si raggiungono solo dalla rete interna di Docker, e l'unica porta aperta (nginx) è su 127.0.0.1 | altri dispositivi in rete | `server.address` in `application.yml`; `docker-compose.yml` (e `docker-compose.dev.yml` per lo sviluppo); `docs/44` |
| 2 | **controllo dell'header `Host`**: solo `localhost` e `127.0.0.1` | DNS rebinding | `web/LocalOnlyRequestFilter` |
| 3 | **controllo dell'header `Origin`** sulle richieste che modificano dati | CSRF (richieste da altri siti) | `web/LocalOnlyRequestFilter` |
| 4 | parametro **`state`** monouso (30 minuti) nel collegamento delle banche | CSRF sul ritorno dal login | `connection/BankLinkService`, `docs/13` |
| 5 | **chiave privata cifrata** nel database (AES-256-GCM), con la chiave di cifratura **fuori** dal database | furto del database o di un suo backup | `security/SecretCipher` |
| 6 | la chiave privata **non esce mai dalle API** | lettura dei segreti dalla dashboard | `setup/SetupService` (un test lo verifica) |
| 7 | **credenziali verificate** prima di salvarle | configurazioni sbagliate | `setup/SetupService` |
| 8 | **validazione** di ogni richiesta nel backend (Bean Validation), con limiti alle richieste pesanti (pagine da massimo 200 movimenti, riepiloghi da massimo 120 mesi) | dati malformati o malevoli, richieste che sovraccaricano il database | `RuleRequest`, `RoleChange`, `TransactionService`, `SummaryService`... |
| 9 | **segreti esclusi da Git** (`.env`, `secrets/`, `*.pem`, `.data/`) | pubblicazione di segreti | `.gitignore` |
| 10 | header **PSU** solo quando l'utente è davvero presente | violazione delle regole PSD2 | `web/PresentUser`, `docs/13` |
| 11 | **Dependabot**: aggiornamenti delle librerie ogni settimana | librerie vulnerabili | `.github/dependabot.yml` |
| 12 | **CI**: tutti i test a ogni push | regressioni, anche di sicurezza | `.github/workflows/ci.yml` |
| 13 | nessuna statistica d'uso inviata da Angular CLI | privacy di chi sviluppa | `"analytics": false` in `frontend/angular.json` |
| 14 | **file caricati limitati**: al massimo 5 MB (413 oltre) e 50.000 righe; si importano solo nei conti manuali; il file non viene conservato sul server; importazione "tutto o niente" in una transazione | file enormi che occupano memoria e disco, dati a metà | `spring.servlet.multipart` in `application.yml`, `csvimport/CsvImportService`, `UploadLimitTest`, `docs/25` |
| 15 | **IA spenta di serie**; Ollama ascolta solo su `127.0.0.1`; l'app **non** controlla Docker (non avvia Ollama da sola); nome del modello validato | dati che escono senza consenso, un difetto dell'app che diventa controllo del computer | `ai/AiService`, `ai/AiController`, `docs/27` |
| 16 | Al modello solo il **testo dell'esercente senza cifre** (niente importi, IBAN, carte, date, nome del titolare); risponde con un **numero** imposto dallo schema e controllato dal backend; misura limitata (100 esercenti, una alla volta) | dati personali al modello, categorie inventate, richieste senza fine | `ai/EvaluationCases`, `ai/CategoryPrompt`, `docs/28` |
| 17 | "Chiedi all'IA" parte **solo con un clic**, al massimo 20 esercenti, un giro alla volta; i suggerimenti dell'IA hanno il segno "IA", non cambiano niente finché non li accetti e spariscono con l'IA spenta | lavoro pesante a sorpresa, suggerimenti sbagliati presi per buoni | `ai/AiSuggestionService`, `ai/AiQuestions`, `docs/29` |
| 18 | IA nel cloud: la **chiave** del servizio salvata **cifrata** (come quella di Enable Banking, un'etichetta per servizio), mai restituita alla pagina (solo le ultime 4 lettere), mai nei log; **consenso esplicito per servizio**, con la data, controllato prima di ogni domanda e dal database (V22) | chiave rubata dal database o vista nella pagina, dati inviati a un servizio esterno senza saperlo | `ai/AiChatClients`, `ai/AiService`, `ai/AiCloudControllerTest`, `docs/30` |
| 19 | Completare un conto collegato con il file di Trade Republic: **solo i campi vuoti** (controparte, causale), mai importo, data o impronta; si aggiungono solo i movimenti **più vecchi dello storico della banca**, con l'impronta del `transaction_id` (niente doppi ricaricando) e riconosciuti dalla sincronizzazione se la banca li riporta; solo movimenti contabilizzati; file riconosciuto dalle colonne, non conservato, stessi limiti di dimensione dell'import; tutto o niente | dati della banca sovrascritti, doppioni, file sbagliati | `csvimport/CompletionService`, `csvimport/TradeRepublicCsv`, `CompletionControllerTest`, `docs/31` |
| 20 | Ricerca nei movimenti: il testo va alla query come **parametro**, mai incollato (anche con la query costruita in Java, Criteria API); `%` e `_` protetti con l'escape di LIKE; al massimo 100 caratteri e 10 parole (400 oltre) | SQL injection, ricerche che trovano tutto per sbaglio, query con testi enormi | `transaction/TransactionSearch`, `transaction/TransactionService`, `TransactionControllerTest`, `docs/32` |
| 21 | Nota di un movimento: mostrata solo come **testo** (`{{ }}`, mai `[innerHTML]`); al massimo 300 caratteri nel backend **e** nel database (vincolo V27); spazi e a capo ridotti a uno spazio, caratteri di controllo rifiutati (400); salvata come parametro | cross-site scripting da una nota, testi enormi, caratteri che rompono la pagina o il database | `transaction/TransactionService`, `V27__nota_movimento.sql`, `TransactionControllerTest`, `transactions-page.spec.ts`, `docs/34` |
| 22 | Correzione della categoria in blocco: al massimo 500 movimenti per richiesta, ogni id una volta sola, tutto controllato **prima** di cambiare (404 o 400 e niente modificato), in una transazione; l'Annulla è la stessa richiesta con i valori di prima, il backend non tiene una storia. I dati originali della banca si mostrano solo come testo (`{{ }}` in un `<pre>`) | richieste enormi, modifiche lasciate a metà, testo della banca interpretato come HTML | `transaction/TransactionDetailService`, `TransactionDetailTest`, `transaction-detail.spec.ts`, `docs/37` |
| 23 | Errori di aggiornamento delle banche: all'utente solo una frase scelta da noi (`SyncErrors`); il codice HTTP, la risposta della banca e lo stack trace restano nel log del backend | dettagli interni (risposte del fornitore, nomi di classi) mostrati sullo schermo | `sync/SyncErrors`, `sync/SyncService`, `SyncErrorsTest`, `SyncServiceTest`, `docs/39` |
| 24 | Esercente di un addebito diretto (SDD): all'IA va solo il nome del **creditore**, mai la causale con "Debitore NOME COGNOME" (il titolare) | il nome del titolare inviato a un servizio esterno | `subscription/MerchantKey`, `ai/EvaluationCases`, `EvaluationCasesTest`, `docs/40` |
| 25 | Modalità discreta: le cifre degli importi **sostituite** ("••• €", segno compreso), non sfocate: non restano nella pagina né nel testo copiato; la scelta resta solo nel browser del dispositivo | qualcuno che guarda lo schermo, cifre lette da una sfocatura | `privacy/privacy-store`, `privacy/money.pipe`, `privacy.spec.ts`, `docs/42` |
| 26 | App installabile: il service worker tiene in memoria solo le **letture** delle pagine principali (strategia `freshness`: la rete prima), al massimo 7 giorni; mai Impostazioni, IA, collegamenti, sincronizzazione; avviso "Senza rete"; `index.html`, `ngsw.json` e il worker sempre ricontrollati | dati sensibili lasciati sul dispositivo più del necessario, dati vecchi presi per attuali, una versione vecchia che non se ne va | `ngsw-config.json`, `pwa/app-status`, `app-status.spec.ts`, `docs/42` |
| 27 | Server locale della build (`scripts/serve-dist.mjs`): ascolta solo su `127.0.0.1`; il percorso richiesto deve restare dentro la cartella della build (anche con `%2F`), altrimenti 404; `nosniff` | lettura di file fuori dalla build (path traversal), server raggiungibile dalla rete | `frontend/scripts/serve-dist.mjs`, `docs/42` (parte G) |
| 28 | Container (Fase 4): backend e nginx girano con un **utente senza privilegi**, file system in **sola lettura** (scrivibili solo `/tmp` in memoria e `.data/`), **nessuna capability** di Linux, `no-new-privileges`; immagini multi-stage senza compilatori né codice sorgente; `secrets/` montata in sola lettura | un attaccante che riesce a eseguire codice nel backend o in nginx: niente root, niente programmi modificati, pochi strumenti a disposizione | `backend/Dockerfile`, `frontend/Dockerfile`, `docker-compose.yml`, `docs/44` |
| 29 | Intestazioni di sicurezza della dashboard da nginx: `Content-Security-Policy` (solo script nostri, nessuno script dentro la pagina, nessuna chiamata verso altri siti, niente riquadri), `X-Content-Type-Options`, `Referrer-Policy: no-referrer`, `Permissions-Policy`, `X-Frame-Options`; `server_tokens off`; le API con `Cache-Control: no-store` | cross-site scripting che esegue codice o manda i dati altrove, clickjacking, indirizzi delle pagine passati ad altri siti | `frontend/nginx/security-headers.conf`, `frontend/nginx/default.conf`, `angular.json` (`inlineCritical: false`), `docs/44` |
| 30 | Controllo di salute: di Actuator è acceso **solo** `/actuator/health`, senza dettagli; nginx inoltra solo `/api/`, quindi da fuori non si raggiunge nemmeno quello. nginx limita i file caricati a 6 MB (413) prima del backend | configurazione, password o memoria del backend lette da un endpoint di Actuator, richieste enormi | `application.yml` (`management`), `HealthEndpointTest`, `frontend/nginx/default.conf`, `docs/44` |
| 31 | Password del database **generata a caso** all'installazione (32 lettere e cifre, circa 190 bit, dal generatore crittografico del sistema); Docker Compose **si rifiuta di partire** senza password (`${POSTGRES_PASSWORD:?}`); il `.env` leggibile solo dal proprio utente (permessi 600 su macOS/Linux); cambio della password in due tempi, prima nel database e poi nel `.env`, così un errore non lascia il backend chiuso fuori; nessuna password d'esempio in `.env.example` | password deboli o lasciate quelle d'esempio, database avviato senza password, `.env` letto da altri utenti del PC | `installa.sh`, `installa.ps1`, `docker-compose.yml`, `.env.example`, CI (job "Installazione con Docker"), `docs/45` |
| 32 | **PIN** per aprire l'app (Fase 4, passo 3): da 6 a 10 cifre; salvato solo come **hash bcrypt** (costo 12, circa un quarto di secondo per tentativo), mai in chiaro; dopo 5 errori di fila pause crescenti (1, 5, 15 minuti, poi un'ora), con il conteggio nel database e la riga bloccata durante il controllo (`SELECT ... FOR UPDATE`); durante la pausa il PIN non viene nemmeno controllato; si **crea solo dal PC** (`localhost`) e si **azzera solo dal PC** (script) | chi prova i PIN uno dopo l'altro, anche con richieste in parallelo; chi ruba il database e cerca il PIN (spesso riusato altrove); chi arriva per primo a creare il PIN da un altro dispositivo | `auth/AppPin`, `auth/PinService`, `auth/AuthController`, `V29__pin.sql`, `AppPinTest`, `AuthControllerTest`, `installa.sh`/`installa.ps1` (`--reimposta-pin`), `docs/46` |
| 33 | **Sessione** dopo il PIN: tutte le API (tranne `/api/auth/...` e `/actuator/health`) rispondono 401 senza; cookie `WALLETTRACKER_SESSION` `HttpOnly`, `SameSite=Strict`, `Secure` da solo quando si arriva in https (X-Forwarded-Proto creduto solo dalla rete interna); chiusa dopo 12 ore senza uso e a ogni riavvio del backend; numero di sessione cambiato al login (*session fixation*); mai nell'indirizzo (`tracking-modes: cookie`) | chi usa l'app senza PIN, un cookie letto da uno script nella pagina, un cookie che viaggia senza https, sessioni infinite su un dispositivo perso | `auth/SecurityConfig`, `application.yml` (`server.servlet.session`), `SessionCookieTest`, `AuthControllerTest`, `docs/46` |
| 34 | **CSRF con gettone** (Spring Security `csrf().spa()`): ogni richiesta che modifica qualcosa deve rimandare nell'header `X-XSRF-TOKEN` il valore del cookie `XSRF-TOKEN`, che solo le nostre pagine possono leggere; si aggiunge al controllo dell'`Origin` (n. 3) e a `SameSite=Strict` | un altro sito che fa partire dal tuo browser una modifica a tuo nome | `auth/SecurityConfig`, `app.config.ts` (protezione XSRF di Angular), `AuthControllerTest`, CI, `docs/46` |
| 35 | **Copia dei dati sul dispositivo solo a sessione aperta**: le risposte salvate dal service worker si cancellano (il cassetto si svuota) quando si esce, quando la sessione scade (401) e all'apertura senza sessione; senza rete l'app apre la copia solo se il backend ha confermato la sessione nelle ultime 12 ore | chi ha in mano il telefono sbloccato e, senza rete, aprirebbe la copia dei dati senza PIN | `auth/auth-store.ts`, `auth/auth-interceptor.ts`, `auth-store.spec.ts`, `docs/46` |

### Approfondimento: la cifratura dei segreti (n. 5)

Nel database la chiave privata **non** è salvata in chiaro:

```
enable_banking_settings.private_key_encrypted = "v1:q8Zf3...Kp0="   ← illeggibile senza encryption.key
```

- **AES-256-GCM**: AES con chiave da 256 bit; la modalità GCM aggiunge un "sigillo", per cui un valore
  alterato non viene decifrato. Un **IV** casuale (12 byte) fa sì che lo stesso segreto cifrato due volte
  dia risultati diversi.
- **Cifratura a busta**: la chiave di cifratura sta in un **file separato** (`.data/encryption.key`), creato
  al primo avvio e leggibile solo dal tuo utente (su Linux/macOS: permessi `600`). Chi ruba **solo** il
  database, o un suo backup (`pg_dump`), vede testo illeggibile. Per leggerlo servono **entrambi**.
- **Dati associati (AAD)**: ogni segreto è cifrato con un'etichetta (`enable_banking.private_key`). Un valore
  cifrato non può essere spostato in un'altra colonna e usato per un altro scopo.

**Cosa comporta per te:**
- **backup**: salva il file `.data/encryption.key` **insieme** al backup del database ma **in un posto
  diverso**. Se lo perdi, le credenziali salvate dalla dashboard non si possono più leggere: basta
  ricaricare il file `.pem` dalla pagina Impostazioni;
- se usi il **file `.env`**, la chiave privata resta nel file `secrets/enable-banking.pem` sul disco. È
  protetta dai permessi del tuo utente, non dalla cifratura.

### Approfondimento: il "buttafuori" delle API (n. 2 e 3)

Le API non hanno ancora un login, perché oggi sono raggiungibili solo dal tuo PC. Restano però due attacchi
che passano **attraverso il tuo browser**:

- **DNS rebinding.** Un sito fa puntare un suo dominio a `127.0.0.1`. Per il browser, la pagina del sito e
  le nostre API diventano "lo stesso sito", e la pagina potrebbe leggere i tuoi movimenti. Il filtro rifiuta
  ogni richiesta il cui header `Host` non sia `localhost` o `127.0.0.1`.
- **CSRF.** Una pagina qualsiasi può far partire dal tuo browser un `POST` verso le nostre API, ad esempio
  "Aggiorna ora". Non può leggerne la risposta, ma l'azione avverrebbe. Il filtro rifiuta le richieste che
  modificano dati se il browser dichiara (header `Origin`) che partono da un altro sito.

Prove fatte con la dashboard e il proxy di Angular:

| Richiesta | Risultato |
|---|---|
| la dashboard legge e salva | ✅ passa |
| `POST /api/sync` da `https://evil.example` | ⛔ 403 "proviene da un altro sito" |
| richiesta con `Host: evil.example` (DNS rebinding), diretta o attraverso il proxy | ⛔ 403 "indirizzo non consentito" |

---

## 4. Le protezioni pianificate

Il principio guida: **nessun accesso da fuori dal PC finché non c'è il login.** Il login (il PIN) c'è dalla Fase 4, passo 3 (n. 32-35): il prossimo è l'accesso dal telefono con Tailscale.

| Quando | Protezione |
|---|---|
| Fase 4 | **HTTPS** con un certificato vero (Tailscale), invece del certificato autofirmato di sviluppo |
| Fase 3–4 | **backup cifrati** del database, con una procedura guidata |
| Fase 3–4 | **rotazione** della chiave di cifratura (il prefisso `v1:` serve proprio a questo) |
| Fase 3–4 | **minimizzazione dei dati**: oggi salviamo il JSON originale di ogni movimento (`raw_json`, anche la riga dei file CSV), che contiene nomi e IBAN delle controparti. Valutare cosa tenere e per quanto |
| CI | **analisi statica** del codice (CodeQL) a ogni push |

---

## 5. Cosa puoi attivare tu su GitHub (5 minuti)

Sono impostazioni del repository, non codice: le puoi attivare solo tu, da **Settings → Code security**
(i nomi delle voci potrebbero variare leggermente).

- **Dependabot alerts** e **Dependabot security updates**: avvisi e pull request per le librerie vulnerabili.
  Il file `dependabot.yml` gestisce gli aggiornamenti periodici; gli avvisi si attivano da qui;
- **Secret scanning** e **Push protection**: GitHub blocca un push che contiene un segreto riconoscibile
  (chiavi API, chiavi private...). È una rete di sicurezza in più oltre al `.gitignore`;
- **Private vulnerability reporting**: permette a chiunque di segnalarti una vulnerabilità in privato,
  come descritto in `SECURITY.md`;
- in **Settings → Branches**: una regola che **richiede la CI verde** prima di unire una pull request nel
  branch principale.

---

## 6. Le regole per chi sviluppa (anche per noi)

1. **Mai un segreto nel codice o su Git**: né nei file, né nei messaggi di commit, né negli screenshot.
2. **Mai un segreto nei log**: i messaggi di errore riportano il motivo, mai chiavi o token.
3. **Validare tutto nel backend**: il frontend aiuta l'utente, ma non è una protezione.
4. **Il minimo indispensabile**: ogni API restituisce solo i dati che servono (i DTO), ogni componente ha
   solo i permessi che gli servono (`permissions: contents: read` nella CI).
5. **Un test per ogni protezione**: se una protezione non è testata, prima o poi qualcuno la rompe senza
   accorgersene. Le protezioni attive di questo documento hanno tutte un test.
6. **Ogni passo aggiorna questo documento.**
