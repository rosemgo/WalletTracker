# 2. Fase 0: collegamento alla banca

**Obiettivo:** verificare con i tuoi conti veri che il collegamento funzioni, prima di costruire
il resto. Alla fine di questa fase saprai:

- se Revolut e ING sono raggiungibili tramite Enable Banking;
- quanti giorni dura il consenso di ciascuna banca;
- come sono fatti i dati reali (descrizioni, date, movimenti in attesa).

Niente database e niente interfaccia grafica: solo un programma da console.

> Prerequisito: aver completato la [guida di installazione](01-installazione-e-setup.md)
> (`./mvnw test` deve dare BUILD SUCCESS).

---

## Come funziona il collegamento

```
 PocRunner              Enable Banking                 Banca                Tu (browser)
     │  GET /aspsps  ──────►│                             │                        │
     │◄── elenco banche ────│                             │                        │
     │  POST /auth  ───────►│                             │                        │
     │◄── link di login ────│                             │                        │
     │  stampa il link ─────────────────────────────────────────────────────────► │ apri il link
     │                      │◄──────────── login + SCA ─────────────────────────── │
     │                      │                             │  redirect a localhost  │
     │                      │                             │  ?code=...&state=...   │
     │◄──────────────────────────────── incolli l'indirizzo ────────────────────── │
     │  POST /sessions(code)►│                            │                        │
     │◄── sessione + conti ─│                             │                        │
     │  GET saldi/movimenti►│─── API PSD2 della banca ───►│                        │
```

Due tipi di autenticazione diversi:

1. **L'applicazione** si autentica con un JWT firmato dalla tua chiave privata
   (`EnableBankingJwtFactory`). Vale per ogni chiamata.
2. **Tu** autorizzi l'accesso ai tuoi conti facendo login sulla banca. Da quel momento la
   *sessione* resta valida fino alla scadenza del consenso (al massimo 180 giorni).

Il parametro `state` è un valore casuale che generiamo noi e che la banca ci restituisce
invariato: se coincide, siamo sicuri che la risposta corrisponde alla nostra richiesta.
È lo stesso meccanismo di OAuth 2, che si usa per i login "Accedi con Google".

---

## Passo 1: crea l'account su Enable Banking

Vai su [enablebanking.com](https://enablebanking.com), registrati ed entra nel *Control Panel*.

> I nomi esatti delle voci nel pannello potrebbero essere leggermente diversi da quelli qui sotto.

## Passo 2: registra l'applicazione

Nel Control Panel apri la sezione delle applicazioni API e registrane una nuova:

| Campo | Cosa mettere |
|---|---|
| Ambiente | **Production**: sono le banche vere. La modalità gratuita per uso personale funziona qui. *Sandbox* ha solo banche finte. |
| Nome | `WalletTracker` |
| Redirect URL | `https://localhost:8443/callback`, uguale carattere per carattere a `ENABLE_BANKING_REDIRECT_URL` nel `.env` |
| Descrizione, email, URL privacy/termini | per uso personale: la tua email e, come URL, ad esempio quello del tuo repository |
| Chiave | scegli di **generarla nel browser** |

Alla fine il browser scarica un file `<application-id>.pem`, per esempio
`cf589be3-3755-465b-a8df-a90a16a31403.pem`.

⚠️ **È la tua chiave privata.** Non condividerla e non committarla. Se la perdi dovrai
registrare una nuova applicazione.

## Passo 3: attiva l'applicazione collegando i tuoi conti

Un'applicazione Production appena creata non è ancora attiva. Nella sua pagina usa la funzione
per **collegare i conti** (*link accounts*):

1. scegli Italia, poi ING, e fai login e SCA come sull'app della banca;
2. ripeti con Revolut.

L'applicazione diventa attiva in **modalità ristretta**: gratuita, ma può leggere solo i conti che
hai collegato tu. È esattamente quello che ci serve.

> Se Revolut non compare tra le banche italiane, prova con la Lituania (LT): Revolut Bank UAB
> è una banca lituana.

## Passo 4: configura il progetto

Dalla radice del repository:

```bash
# macOS / Linux
cp .env.example .env
mv ~/Downloads/<application-id>.pem secrets/enable-banking.pem

# Windows
copy .env.example .env
move %USERPROFILE%\Downloads\<application-id>.pem secrets\enable-banking.pem
```

Apri `.env` e inserisci l'ID (il nome del file `.pem`, senza estensione):

```properties
ENABLE_BANKING_APP_ID=cf589be3-3755-465b-a8df-a90a16a31403
```

Ora controlla che Git **non** veda i segreti:

```bash
git status
```

Non devono comparire né `.env` né `secrets/enable-banking.pem`. Se compaiono, fermati e
controlla il `.gitignore`.

## Passo 5: esegui la prova

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=poc
```

(Su Windows, in PowerShell: `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=poc"`.)

Esempio di sessione (con dati inventati):

```
=== WalletTracker - Fase 0: prova di collegamento a Enable Banking ===
Paese della banca (premi Invio per IT):
Enable Banking supporta 120 banche in IT.
Cerca la banca per nome (es. ING, Revolut): ing
  1) ING  [personal]
Numero della banca: 1
Il consenso durerà 180 giorni.

1) Apri questo link nel browser e accedi alla tua banca:
   https://...
2) Alla fine verrai mandato su https://localhost:8443/callback
   La pagina darà errore: è normale, per ora non c'è un server in ascolto.
3) Copia l'indirizzo completo dalla barra del browser e incollalo qui.
Indirizzo: https://localhost:8443/callback?state=489910ab-...&code=7c1e...
Collegamento riuscito! Conti autorizzati: 1

=== Conto: Conto Arancio | IBAN: IT60X0542811101000000123456 | EUR ===
Saldo CLBD: 1234.56 EUR
2 movimenti dal 2026-09-03 al 2026-10-03:
  2026-10-01      -23.40 EUR  BOOK  ESSELUNGA PAGAMENTO POS
  2026-10-02       -5.00 EUR  PDNG  BAR
```

Ripeti con l'altra banca.

## Passo 6: prendi appunti (serviranno nella Fase 1)

- [ ] Con che nome compare ING? E Revolut, in quale paese?
- [ ] Quanti giorni dura il consenso per ciascuna banca?
- [ ] Revolut mostra un conto per ogni valuta o uno solo?
- [ ] Il nome dell'esercente sta nella controparte (`creditor`) o solo nella causale?
- [ ] Ci sono movimenti `PDNG` (in attesa)?
- [ ] Quanto storico c'è? Prova a cambiare `DAYS_OF_HISTORY` in `PocRunner` da 30 a 365.
- [ ] Una ricarica di Revolut da ING: come appare nei due conti? (Ci servirà per riconoscere i giroconti.)

---

## Problemi comuni

| Messaggio o sintomo | Causa probabile | Soluzione |
|---|---|---|
| `Reason: imposta ENABLE_BANKING_APP_ID nel file .env` all'avvio | manca il `.env` o il valore è vuoto | rifai il Passo 4 |
| `Non riesco a leggere la chiave privata` | percorso sbagliato | i percorsi relativi partono da `backend/`; prova con un percorso assoluto |
| `non è una chiave in formato PKCS#8` | la chiave è in un altro formato | convertila con il comando `openssl` indicato nel messaggio |
| HTTP 401 | il JWT viene rifiutato | l'ID nel `.env` deve essere quello della stessa applicazione della chiave; controlla anche che l'orologio del PC sia corretto |
| Errore su `POST /auth` che parla di `redirect_url` | redirect URL non registrato | deve essere identico a quello del Passo 2 |
| Errore che dice che l'applicazione non è attiva | conti non collegati | rifai il Passo 3 |
| `il parametro 'state' non corrisponde` | hai incollato l'indirizzo di un tentativo precedente | rilancia il programma e usa il link nuovo |
| Il browser dice che `localhost` non è raggiungibile | è normale | copia comunque l'indirizzo dalla barra |

Quando Enable Banking risponde con un errore, il programma stampa il corpo della risposta:
di solito il messaggio spiega già il problema.

---

## Cosa hai imparato

- **PSD2 e consenso:** un fornitore autorizzato legge i conti solo dopo che hai fatto login
  sulla banca, e solo fino alla scadenza del consenso.
- **JWT con firma RS256:** costruito a mano in `EnableBankingJwtFactory`, senza librerie.
- **Spring Boot:**
  - configurazione tipizzata con `@ConfigurationProperties` e validazione;
  - profili e `CommandLineRunner`;
  - `RestClient`;
  - dependency injection tramite costruttore.
- **Test:** `MockRestServiceServer` per testare un client HTTP senza Internet.

## Esercizi (facoltativi)

1. Alla fine di ogni conto stampa il totale delle uscite e delle entrate del periodo.
2. Stampa i movimenti dal più recente al più vecchio.
3. Aggiungi al client il metodo `getAccountDetails` (`GET /accounts/{uid}/details`) con un test,
   prendendo esempio da `EnableBankingClientTest`.

## Prossimo passo: Fase 1

Con gli appunti del Passo 6 progetteremo:

- il **modello dati** (conti, movimenti, sessioni con la loro scadenza);
- un database **PostgreSQL in Docker** (il primo `docker-compose.yml`) con le migrazioni Flyway;
- la **sincronizzazione automatica** ogni 6 ore, che elimina i doppioni.
