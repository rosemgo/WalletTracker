# 9. Fase 2, passo 1: il server web e le API REST

**Obiettivo:** il backend diventa un **server web**. Risponde a richieste HTTP con i tuoi conti e
movimenti in formato JSON, e permette di cambiare il ruolo di un conto e correggere un movimento. È la
base su cui, nel passo successivo, costruiremo la dashboard React.

> Prerequisiti: Fase 1 completata e `git pull` fatto.

## Il piano della Fase 2

| Passo | Cosa |
|---|---|
| **1 (questo)** | server web e API REST: conti, movimenti, ruoli, correzioni manuali |
| 2 | la dashboard React: elenco conti e movimenti, correzione del tipo, scelta dei ruoli |
| 3 | collegare le banche dalla dashboard (niente più indirizzo da copiare e incollare) e pulsante "Aggiorna ora" |
| 4 | regole dalla dashboard e configurazione guidata delle credenziali Enable Banking |

Perché le API prima della grafica? Perché la dashboard non farà altro che **chiamare queste API** e
mostrare il risultato. Se le API funzionano e sono testate, la parte React sarà "solo" grafica.

---

## Parte A: i concetti

### 1. Client e server

```
  browser / dashboard React  ── GET /api/accounts ──►  backend Spring Boot  ──►  PostgreSQL
           (client)          ◄──── JSON ────────────        (server)
```

Il **server** resta in ascolto su una porta (8080) e risponde alle **richieste HTTP**. Il **client**
(il browser, la dashboard, PowerShell) fa le richieste. È lo stesso schema che usiamo già con Enable
Banking, a parti invertite: lì il client era il nostro backend.

### 2. REST in breve

Un'API REST organizza i dati in **risorse**, ognuna con un indirizzo, e usa i **metodi HTTP** per dire
cosa fare:

| Metodo | Significato | Le nostre API |
|---|---|---|
| `GET` | leggi (non modifica nulla) | `GET /api/accounts`, `GET /api/transactions` |
| `PUT` | sostituisci un valore | `PUT /api/accounts/3/role`, `PUT /api/transactions/42/manual-type` |
| `POST` | crea / esegui un'azione | (passo 3: "Aggiorna ora") |
| `DELETE` | cancella | (passo 4: regole) |

La risposta ha un **codice di stato**:

| Codice | Significato | Esempio |
|---|---|---|
| `200 OK` | tutto bene | |
| `400 Bad Request` | la richiesta è sbagliata | ruolo `"BOH"`, che non esiste |
| `404 Not Found` | la risorsa non esiste | conto 999999 |
| `500 Internal Server Error` | errore del server (un bug) | |

### 3. Le API di questo passo

| Richiesta | Cosa fa |
|---|---|
| `GET /api/accounts` | tutti i conti, con banca, ruolo, scadenza del consenso e stato della sincronizzazione |
| `PUT /api/accounts/{id}/role` con `{"role": "INVESTMENT"}` | cambia il ruolo e riclassifica tutto |
| `GET /api/transactions?accountId=3&type=TO_REVIEW&page=0&size=50` | una pagina di movimenti, dal più recente; tutti i parametri sono facoltativi |
| `PUT /api/transactions/{id}/manual-type` con `{"type": "EXPENSE"}` | correzione manuale; con `{"type": null}` la toglie |

### 4. Sicurezza: solo da questo PC

Le API **non hanno ancora un login**. Per questo il server ascolta solo sull'indirizzo `127.0.0.1`
("questo computer"):

```yaml
server:
  address: 127.0.0.1
```

Senza questa riga Spring ascolterebbe su tutte le interfacce di rete. Visto che il portatile lo usi
anche fuori casa, chiunque sulla stessa rete Wi-Fi (un bar, un treno) potrebbe aprire
`http://<il-tuo-ip>:8080/api/transactions` e leggere i tuoi movimenti. L'accesso da altri dispositivi
(il telefono) arriverà nella Fase 4, con una VPN (Tailscale) e un login.

---

## Parte B: il codice

### 1. `pom.xml`: due dipendenze

- `spring-boot-starter-webmvc`: **Tomcat** (il server web, incluso nell'applicazione) e **Spring MVC**
  (controller, conversione JSON);
- `spring-boot-starter-webmvc-test`: gli strumenti per testare le API.

In `application-poc.yml` c'è `spring.main.web-application-type: none`: il programma della Fase 0 resta a
riga di comando, senza server web, e alla fine termina.

### 2. I DTO: `AccountDto` e `TransactionDto`

Le API **non restituiscono le entità** (`Account`, `BankTransaction`) ma dei `record` creati apposta:

- **scegliamo noi cosa esce:** `providerUid`, `externalKey` e il JSON originale della banca servono solo
  al backend;
- **il database può cambiare** senza cambiare le API, e viceversa;
- **niente sorprese con Hibernate:** le relazioni LAZY lette da Jackson fuori dalla transazione danno
  errori o query inattese.

Ogni DTO ha un metodo `from(entità)` che fa la traduzione. È il ruolo dei DTO di Enable Banking, nella
direzione opposta: quelli traducono ciò che **riceviamo**, questi ciò che **mandiamo**.

### 3. I controller: `AccountController` e `TransactionController`

```java
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    @GetMapping
    public List<AccountDto> list() { ... }

    @PutMapping("/{id}/role")
    public AccountDto changeRole(@PathVariable Long id, @Valid @RequestBody RoleChange change) { ... }

    public record RoleChange(@NotNull AccountRole role) { }
}
```

| Annotazione | Significato |
|---|---|
| `@RestController` | i metodi rispondono a richieste HTTP; il valore restituito diventa JSON |
| `@RequestMapping("/api/accounts")` | prefisso comune degli indirizzi della classe |
| `@GetMapping`, `@PutMapping("/{id}/role")` | metodo HTTP e resto dell'indirizzo |
| `@PathVariable` | il valore di `{id}` nell'indirizzo |
| `@RequestParam` | un parametro dopo il `?` (es. `?type=TO_REVIEW`) |
| `@RequestBody` | il corpo JSON della richiesta, convertito in un record |
| `@Valid` + `@NotNull` | se il corpo non rispetta i vincoli, risposta 400 senza chiamare il metodo |

Il controller resta **sottile**: legge la richiesta, chiama il servizio, restituisce il risultato. La
logica e le transazioni stanno nei servizi (`AccountService`, `TransactionService`). È la separazione in
livelli che trovi in quasi tutti i progetti Spring aziendali: **controller → service → repository**.

### 4. "Open in view" spento, `JOIN FETCH` e il problema N+1

Con il server web, Spring Boot di default tiene aperta la sessione del database per tutta la richiesta
HTTP (*open in view*). È comodo, perché le relazioni LAZY si caricano ovunque, anche nel controller.
Però nasconde le query: una lista di 100 conti potrebbe fare 101 query senza che tu te ne accorga.
L'abbiamo spenta:

```yaml
spring:
  jpa:
    open-in-view: false
```

Quindi i dati che servono vanno caricati **nei servizi, dentro la transazione**. Per l'elenco dei conti
usiamo una query con `JOIN FETCH`:

```java
@Query("SELECT a FROM Account a JOIN FETCH a.connection c ORDER BY c.aspspName, a.name")
List<Account> findAllWithConnection();
```

`JOIN FETCH` carica conti **e** collegamenti con **una sola** query. Senza, Hibernate farebbe una query
per i conti e poi una per ogni collegamento letto: il famoso problema **"N+1 query"**, uno dei più
frequenti nei progetti con JPA.

### 5. Filtri facoltativi e pagine

```java
@Query("""
        SELECT t FROM BankTransaction t
        WHERE (:accountId IS NULL OR t.account.id = :accountId)
          AND (:type IS NULL OR t.type = :type)
        ORDER BY t.bookingDate DESC, t.id DESC
        """)
Page<BankTransaction> search(Long accountId, TransactionType type, Pageable pageable);
```

- **`(:accountId IS NULL OR ...)`**: se il parametro è `null`, la condizione è sempre vera e il filtro è
  "spento". Una sola query copre tutte le combinazioni.
- **`Pageable` / `Page`**: Spring Data aggiunge `LIMIT` e `OFFSET` e fa una seconda query (`COUNT`) per il
  totale. Con migliaia di movimenti la dashboard chiederà una pagina alla volta.
- **`"""..."""`**: un *text block* di Java, cioè una stringa su più righe.

### 6. Gli errori in JSON: `NotFoundException` e Problem Details

```yaml
spring:
  mvc:
    problemdetails:
      enabled: true
```

Con questa impostazione gli errori hanno un corpo JSON standard (RFC 9457):

```json
{ "status": 404, "title": "Not Found", "detail": "Conto 999999 non trovato", "instance": "/api/accounts/999999/role" }
```

`NotFoundException` estende `ResponseStatusException`, l'eccezione di Spring che porta con sé uno stato
HTTP: il servizio la lancia, e Spring la trasforma in una risposta 404.

### 7. Ruoli e correzioni riclassificano tutto

`AccountService.changeRole` e `TransactionService.correctType` modificano il dato e chiamano
`classifyAll()` nella **stessa transazione**. Un ruolo o una correzione possono cambiare anche **altri**
movimenti: ad esempio, se segni come `IGNORED` metà di un trasferimento, l'altra metà resta senza
coppia.

### 8. I test: `MockMvcTester`

```java
@SpringBootTest
@AutoConfigureMockMvc
class AccountControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void anUnknownAccountIs404WithTheReason() {
        assertThat(mvc.put().uri("/api/accounts/999999/role")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"MAIN\"}"))
                .hasStatus(404)
                .bodyJson().extractingPath("$.detail").isEqualTo("Conto 999999 non trovato");
    }
}
```

`MockMvcTester` **simula** le richieste HTTP: passano per tutto Spring MVC (controller, JSON, errori) ma
senza aprire una porta vera. `extractingPath("$.detail")` usa **JsonPath**: `$` è la radice del JSON,
`$.items[0].type` il tipo del primo movimento.

| Test | Cosa verifica |
|---|---|
| `listsTheAccountsWithBankRoleAndSyncStatus` | elenco conti; i dati interni (`providerUid`) non escono |
| `changingTheRoleReclassifiesTheTransactions` | un conto che diventa INVESTMENT trasforma il trasferimento in versamento |
| `anUnknownAccountIs404WithTheReason` | 404 con il motivo in `detail` |
| `aMissingOrUnknownRoleIs400` | ruolo mancante o inesistente: 400 |
| `transactionsCanBeFilteredByAccountAndTypeAndArePaged` | filtri per conto e tipo, ordine, pagine |
| `aManualCorrectionChangesTheTypeAndCanBeRemoved` | correzione manuale e sua rimozione |
| `wrongRequestsAreRejected` | tipo inesistente (400), movimento inesistente (404) |

---

## Parte C: cosa fare

1. **Aggiorna e lancia i test:**
   ```powershell
   git status
   git pull
   docker compose up -d
   cd backend
   .\mvnw.cmd test          # 50 test
   ```
2. **Avvia l'applicazione** (senza profilo): ora è un server web e, insieme, fa la sincronizzazione
   automatica.
   ```powershell
   .\mvnw.cmd spring-boot:run
   ```
   Nel log cerca `Tomcat started on port 8080`.
3. **Prova le richieste GET nel browser** (Firefox mostra il JSON in modo leggibile; Chrome ed Edge hanno
   la casella "Stampa leggibile"):
   - <http://localhost:8080/api/accounts>
   - <http://localhost:8080/api/transactions>
   - <http://localhost:8080/api/transactions?type=TO_REVIEW>
   - <http://localhost:8080/api/transactions?type=INVESTMENT_INCOME&size=10>
   - <http://localhost:8080/api/accounts/999999/role> → nel browser è una GET, quindi vedrai un
     errore **405 Method Not Allowed**: quell'indirizzo accetta solo PUT.
4. **Prova le richieste PUT da PowerShell** (in un'altra finestra, l'applicazione deve restare accesa).
   `Invoke-RestMethod` fa la richiesta e trasforma il JSON in oggetti di PowerShell:
   ```powershell
   # I conti in una tabella
   Invoke-RestMethod http://localhost:8080/api/accounts | Format-Table id, bank, name, role, lastSyncError

   # I movimenti da verificare
   (Invoke-RestMethod "http://localhost:8080/api/transactions?type=TO_REVIEW").items |
       Format-Table id, accountId, bookingDate, amount, type

   # Correggi un movimento di Trade Republic (metti l'id giusto): era un acquisto di titoli
   Invoke-RestMethod -Method Put -Uri http://localhost:8080/api/transactions/123/manual-type `
       -ContentType "application/json" -Body '{"type": "SECURITIES_BUY"}'

   # Togli la correzione
   Invoke-RestMethod -Method Put -Uri http://localhost:8080/api/transactions/123/manual-type `
       -ContentType "application/json" -Body '{"type": null}'
   ```
   Il carattere `` ` `` alla fine della riga dice a PowerShell che il comando continua sulla riga dopo.
5. **Prova un errore:**
   ```powershell
   Invoke-RestMethod -Method Put -Uri http://localhost:8080/api/accounts/999999/role `
       -ContentType "application/json" -Body '{"role": "MAIN"}'
   ```
   PowerShell mostrerà l'errore 404 con il JSON del motivo.

> **Esercizio.** Aggiungi `GET /api/transactions/{id}`, che restituisce un movimento con anche il JSON
> originale della banca (`rawJson`). Ti serviranno: un nuovo record DTO, un metodo nel servizio che lancia
> `NotFoundException` e un `@GetMapping("/{id}")` nel controller. Poi scrivi un test con `MockMvcTester`.

---

## Cosa hai imparato

- Il modello **client-server** e le basi di **REST**: risorse, metodi HTTP, codici di stato.
- **Spring MVC**: `@RestController`, `@GetMapping`, `@PutMapping`, `@PathVariable`, `@RequestParam`,
  `@RequestBody`, `@Valid`.
- I **DTO** e la separazione **controller → service → repository**.
- **Open in view**, `JOIN FETCH` e il problema **N+1**.
- **Paginazione** con `Pageable` e filtri facoltativi in JPQL.
- Errori standard con **Problem Details** (RFC 9457).
- Test delle API con **MockMvcTester** e **JsonPath**.
- Perché un server senza login deve ascoltare solo su **127.0.0.1**.

## Prossimo passo

**Fase 2, passo 2:** il progetto React (con Vite e TypeScript) nella cartella `frontend/`, con le prime
pagine che usano queste API: i conti con il loro ruolo e l'elenco dei movimenti con la correzione del tipo.
