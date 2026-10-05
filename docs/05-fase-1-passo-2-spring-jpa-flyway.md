# 5. Fase 1, passo 2: Spring Boot + PostgreSQL + Flyway

**Obiettivo:** collegare l'applicazione al database e salvare i collegamenti bancari e i conti.
In pratica: **autorizzi una banca una volta e il programma se la ricorda** finché il consenso è
valido, senza rifare il login a ogni avvio.

> Prerequisiti: passo 1 completato (il container `wallettracker-db` è `healthy`) e `git pull` fatto.

---

## Parte A: i concetti

### Come Java parla con un database: dal basso verso l'alto

```
 Il tuo codice            ConnectionService.saveSession(...)
        │                           │ usa
 Spring Data JPA          AccountRepository.findByExternalKey(...)   ← tu scrivi solo l'interfaccia
        │                           │ genera
 JPA / Hibernate          SELECT * FROM account WHERE external_key = ?  ← traduce oggetti ↔ righe
        │                           │ tramite
 JDBC + driver            org.postgresql.Driver                       ← la "spina" verso PostgreSQL
        │                           │ rete (porta 5432)
 PostgreSQL               container wallettracker-db
```

| Livello | Cos'è | Dove lo vedi nel progetto |
|---|---|---|
| **JDBC** | l'API standard di Java per eseguire SQL | non lo usiamo direttamente |
| **Driver** | la libreria che implementa JDBC per un database specifico | `org.postgresql:postgresql` nel `pom.xml` |
| **DataSource** | la configurazione della connessione: URL, utente, password | `spring.datasource` in `application.yml` |
| **JPA / Hibernate** | **ORM** (Object-Relational Mapping): ogni classe `@Entity` corrisponde a una tabella, ogni oggetto a una riga | `BankConnection`, `Account` |
| **Spring Data JPA** | genera da solo le query a partire dal nome dei metodi | `AccountRepository`, `BankConnectionRepository` |
| **Flyway** | crea e aggiorna le tabelle con script SQL numerati | `src/main/resources/db/migration/V1__...sql` |

### Chi crea le tabelle? Flyway, non Hibernate

Hibernate potrebbe creare le tabelle da solo leggendo le entità, ma in un progetto vero non si fa.
Non si controlla esattamente cosa crea, e non si sa come modificare le tabelle quando ci sono già
dei dati. Per questo usiamo **Flyway**:

- ogni modifica allo schema è un file SQL con un numero di versione: `V1__...`, `V2__...`;
- all'avvio Flyway guarda la tabella `flyway_schema_history`, vede quali file ha già eseguito ed
  esegue solo quelli nuovi, in ordine;
- **un file già eseguito non si modifica più**: se lo cambi, Flyway se ne accorge (il *checksum*
  non torna) e si ferma. Per cambiare lo schema si aggiunge un nuovo file.

È come Git per lo schema del database: ogni modifica è versionata e riproducibile, sul tuo PC come
sul server.

E Hibernate? Con `ddl-auto: validate` **controlla** soltanto, all'avvio, che le entità Java
corrispondano alle tabelle. Mentre scrivevo questo passo l'ha fatto davvero:

```
Schema validation: wrong column type encountered in column [currency] in table [account];
found [bpchar (Types#CHAR)], but expecting [char(3) (Types#VARCHAR)]
```

La tabella aveva una colonna `CHAR(3)` e l'entità si aspettava `VARCHAR`. L'applicazione non è
partita, e l'errore è emerso subito invece che come dato sbagliato settimane dopo. Ho corretto
lo script usando `VARCHAR(3)`.

---

## Parte B: il codice, file per file

### 1. `pom.xml`: le nuove dipendenze

| Dipendenza | A cosa serve |
|---|---|
| `spring-boot-starter-data-jpa` | Hibernate + Spring Data JPA |
| `spring-boot-starter-flyway` + `flyway-database-postgresql` | Flyway, con il supporto specifico per PostgreSQL |
| `postgresql` (scope `runtime`) | il driver JDBC. `runtime` = serve solo per eseguire, il codice non lo usa direttamente |
| `spring-boot-testcontainers` + `testcontainers-postgresql` (scope `test`) | nei test avvia un PostgreSQL in Docker (vedi Parte D) |

### 2. `application.yml`: la connessione

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:${POSTGRES_PORT:5432}/${POSTGRES_DB:wallettracker}
    username: ${POSTGRES_USER:wallettracker}
    password: ${POSTGRES_PASSWORD:}
  jpa:
    hibernate:
      ddl-auto: validate
```

L'URL JDBC si legge così: `jdbc:` + tipo di database (`postgresql`) + `//host:porta/nome-database`.
I valori arrivano dallo **stesso `.env`** usato da Docker Compose, quindi container e applicazione
usano per forza le stesse credenziali.

### 3. `db/migration/V1__connessioni_e_conti.sql`: le tabelle

Due tabelle:

- **`bank_connection`**: un collegamento, cioè la sessione di Enable Banking con la sua scadenza;
- **`account`**: un conto, con una **chiave esterna** `connection_id` che punta al collegamento a
  cui appartiene (relazione *molti a uno*: tanti conti, un collegamento).

Il file è commentato riga per riga. Il nome conta: `V1` è la versione, `__` (due trattini bassi)
la separa dalla descrizione.

**`V2__identificativi_senza_limite.sql`** è arrivato subito dopo, ed è un buon esempio di come si
lavora con Flyway. Con i dati veri di ING l'impronta del conto superava i 200 caratteri previsti in
V1 e l'inserimento falliva. V1 era già stato eseguito sul tuo database, quindi non si modifica: si
aggiunge V2, che cambia il tipo delle colonne in `TEXT` (stringa senza limite). Al prossimo avvio
Flyway vede che V1 è già fatto ed esegue solo V2.

Nota anche che l'errore non ha lasciato dati a metà: `saveSession` è `@Transactional`, quindi
anche il collegamento inserito prima del conto è stato annullato (*rollback*).

### 4. Le entità: `BankConnection` e `Account`

Sono classi annotate con `@Entity`. Le annotazioni principali:

| Annotazione | Significato |
|---|---|
| `@Entity`, `@Table(name = "account")` | questa classe corrisponde alla tabella `account` |
| `@Id` + `@GeneratedValue(strategy = IDENTITY)` | chiave primaria, numerata dal database |
| `@Column(name = "...")` | colonna corrispondente al campo |
| `@ManyToOne` + `@JoinColumn(name = "connection_id")` | la relazione verso `BankConnection` |
| `@Enumerated(EnumType.STRING)` | l'enum `AccountRole` viene salvato come testo (`"MAIN"`), non come numero |

Perché le entità non sono `record` come i DTO? Hibernate ha bisogno di un costruttore senza
argomenti (lo vedi `protected`) e di poter riempire i campi dopo aver creato l'oggetto. I record
sono immutabili e non lo permettono.

**I due identificativi di un conto** sono il punto più importante del passo:

| Campo | Stabile? | A cosa serve |
|---|---|---|
| `externalKey` | ✅ sì, per sempre | riconoscere un conto già salvato quando rinnovi il consenso |
| `providerUid` | ❌ cambia a ogni consenso | chiamare le API di Enable Banking nella sessione corrente |

Come si calcola `externalKey` (metodo `ConnectionService.externalKeyOf`):
1. se Enable Banking fornisce l'impronta del conto (`identification_hash`), usiamo quella;
2. altrimenti banca + IBAN + valuta. La valuta serve perché Revolut ha lo stesso IBAN per EUR e USD;
3. per i conti senza IBAN, come le carte, banca + uid: è l'ultima risorsa.

### 5. I repository

```java
public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByExternalKey(String externalKey);
}
```

Non c'è nessuna implementazione da scrivere. All'avvio Spring Data legge il **nome del metodo**
(`findBy` + `ExternalKey`) e genera la query `SELECT ... FROM account WHERE external_key = ?`.
`JpaRepository` aggiunge già `save`, `findById`, `findAll`, `count`, `delete`...

### 6. `ConnectionService`: la logica

`saveSession(...)` riceve la sessione appena creata su Enable Banking (un DTO) e:

1. salva un nuovo `BankConnection`;
2. per ogni conto: se l'`externalKey` esiste già, **sposta** il conto sul nuovo collegamento e
   aggiorna l'uid; altrimenti lo crea con ruolo `UNASSIGNED`;
3. cancella i vecchi collegamenti della stessa banca rimasti senza conti.

È annotato con **`@Transactional`**: tutte queste scritture avvengono in un'unica
**transazione**. O vanno a buon fine tutte, o nessuna: se a metà qualcosa fallisce, il database
torna com'era. Dentro una transazione Hibernate si accorge da solo delle modifiche agli oggetti
caricati (*dirty checking*): per questo `existing.moveTo(...)` non ha bisogno di un `save()`.

### 7. `PocRunner`: il programma usa il database

All'avvio mostra i collegamenti salvati con il consenso ancora valido:

```
Collegamenti salvati:
  1) ING (IT), valido fino al 2027-04-03
  2) Revolut (IT), valido fino al 2027-04-04
  0) Collega una nuova banca
Scelta:
```

- **Scegli un numero:** niente login, il programma usa la sessione salvata.
- **Scegli `0`:** colleghi una nuova banca, come prima, e il programma la salva.

Ogni conto viene stampato con il suo **ruolo** (`UNASSIGNED` all'inizio).

---

## Parte C: cosa fare

### 1. Aggiorna e controlla il `.env`

```powershell
git status          # nessun file modificato?
git pull
```

Nel `.env` devono esserci le variabili `POSTGRES_*`: le usa anche Spring Boot.

### 2. Avvia il database e i test

```powershell
docker compose up -d          # dalla cartella WalletTracker
cd backend
.\mvnw.cmd test
```

Devono passare **11 test**. Alcuni avviano un PostgreSQL temporaneo in Docker (Parte D), quindi
**Docker Desktop deve essere avviato** anche per i test.

### 3. Ricollega le banche, un'ultima volta

Le sessioni create finora non erano salvate, quindi va fatto un login per banca. Poi basta.

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=poc"
```

La prima volta non ci sono collegamenti salvati e parte subito la scelta della banca. Dalla volta
successiva compare l'elenco: prova a scegliere un collegamento salvato e verifica che **non** ti
venga chiesto il login.

Con il profilo `poc` i log di Spring sono nascosti (solo gli avvisi), quindi non vedi Flyway al
lavoro. Al primo avvio però ha creato le tabelle: lo verifichi al punto successivo.

### 4. Guarda i dati in IntelliJ

Nella finestra **Database** (collegata nel passo 1), fai *Refresh* (icona con le frecce). Sotto
`wallettracker → public → tables` trovi:

- `bank_connection` e `account`: i tuoi dati;
- `flyway_schema_history`: il "registro" di Flyway, con la riga della versione 1.

Doppio clic su una tabella per vederne il contenuto.

### 5. Esercizio: assegna i ruoli ai conti

Nell'applicazione non c'è ancora un'interfaccia per farlo (arriverà nella dashboard), ma il
database è tuo. Apri una *console* in IntelliJ (tasto destro sul database → *New → Query Console*):

```sql
-- Guarda i conti
SELECT id, name, iban, currency, role FROM account ORDER BY id;

-- Assegna i ruoli (cambia gli id con quelli che vedi)
UPDATE account SET role = 'MAIN'       WHERE id = 1;   -- ING conto corrente
UPDATE account SET role = 'SPENDING'   WHERE id = 2;   -- ING carta di credito
UPDATE account SET role = 'EXCLUDED'   WHERE id = 3;   -- conti di prova, vuoti
UPDATE account SET role = 'INVESTMENT' WHERE id = 4;   -- Fineco, Trade Republic
```

I valori possibili sono quelli dell'enum `AccountRole`: `UNASSIGNED`, `MAIN`, `SPENDING`,
`INVESTMENT`, `EXCLUDED`. Rilancia il programma: accanto a ogni conto vedrai il ruolo che hai
assegnato.

---

## Parte D: i test con Testcontainers

I test che usano il database (`WalletTrackerApplicationTests`, `ConnectionServiceTest`) non
usano il tuo database di sviluppo. Per ogni esecuzione **Testcontainers** avvia un PostgreSQL
nuovo in un container Docker e lo cancella alla fine. Così:

- i test non sporcano né leggono i tuoi dati veri;
- il database dei test è **identico** a quello vero (stessa versione, PostgreSQL 18), non una
  simulazione;
- chiunque cloni il progetto può eseguire i test con il solo Docker.

La configurazione è in `IntegrationTestConfiguration`:

```java
@Bean
@ServiceConnection
PostgreSQLContainer postgres() {
    return new PostgreSQLContainer("postgres:18-alpine");
}
```

`@ServiceConnection` dice a Spring Boot di collegarsi a quel container: URL, utente e password
vengono presi dal container, non da `application.yml`.

`ConnectionServiceTest` verifica le regole importanti:
- un nuovo collegamento salva i conti con ruolo `UNASSIGNED`;
- **rinnovare il consenso non duplica i conti**: aggiorna gli uid e cancella il vecchio collegamento;
- i conti Revolut EUR e USD, che hanno lo stesso IBAN, hanno chiavi diverse.

---

## Problemi comuni

| Sintomo | Causa | Soluzione |
|---|---|---|
| test: `Could not find a valid Docker environment` | Docker Desktop non è avviato | avvialo e rilancia i test |
| avvio: `Connection to localhost:5432 refused` | il container del database non è avviato | `docker compose up -d` |
| avvio: `password authentication failed for user "wallettracker"` | la password nel `.env` è diversa da quella con cui è stato creato il database | rimetti quella originale, oppure `docker compose down -v` + `up -d` (⚠️ cancella i dati) |
| avvio: `Validate failed: Migrations have failed validation` / `checksum mismatch` | è stato modificato un file di migrazione già eseguito | ripristinalo con `git restore`; le modifiche vanno in un nuovo file `V2__...` |
| `value too long for type character varying(200)` | un identificativo della banca era più lungo del previsto (è successo con ING) | risolto dalla migrazione `V2__identificativi_senza_limite.sql`: fai `git pull` e rilancia |
| avvio: `Schema validation: missing table [...]` | le tabelle non esistono e Flyway non è partito | controlla che il file sia in `src/main/resources/db/migration` e si chiami `V1__qualcosa.sql` |

---

## Cosa hai imparato

- La catena **JDBC → driver → DataSource → JPA/Hibernate → Spring Data**.
- **Flyway** e le migrazioni versionate; perché non si modifica una migrazione già eseguita.
- **Entità JPA**: `@Entity`, `@Id`, `@ManyToOne`, `@Enumerated`, e perché non sono record.
- **Repository** con query ricavate dal nome dei metodi.
- **Transazioni** con `@Transactional` e il *dirty checking* di Hibernate.
- **Testcontainers** per testare con un database vero.

## Prossimo passo

**Passo 3: i movimenti.**
- una nuova migrazione `V2__movimenti.sql` (vedrai Flyway eseguire solo quella nuova);
- l'entità `Transaction`, con il JSON originale della banca;
- l'importatore, che scarica i movimenti e li salva **senza doppioni**, compresi quelli ripetuti di Trade Republic.
