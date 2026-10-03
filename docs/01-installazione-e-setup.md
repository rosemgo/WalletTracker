# 1. Installazione e setup dell'ambiente

Questa guida ti porta da zero a un progetto Spring Boot che si compila e supera i test sul tuo PC.
Alla fine c'è una spiegazione di come è fatto un progetto Spring Boot: vale la pena leggerla prima
di passare alla [Fase 0](02-fase-0-collegamento-banca.md).

---

## 1. Cosa installare

| Strumento | Versione | A cosa serve |
|---|---|---|
| JDK (Eclipse Temurin) | 21 (va bene anche 25) | compilare ed eseguire Java |
| IntelliJ IDEA | ultima | l'IDE (va bene la versione gratuita) |
| Git | recente | versionare il codice |
| Docker Desktop | ultima | serve dalla Fase 1 (PostgreSQL), ma installalo già ora |
| Maven | **non serve** | il progetto include il *Maven Wrapper* (`mvnw`), che scarica Maven da solo |

### 1.1 JDK 21

Scegli il comando per il tuo sistema:

- **Windows** (PowerShell): `winget install EclipseAdoptium.Temurin.21.JDK`
  oppure scarica l'installer da [adoptium.net](https://adoptium.net) e spunta "Set JAVA_HOME".
- **macOS** (con [Homebrew](https://brew.sh)): `brew install --cask temurin@21`
- **Linux**: `sudo apt install openjdk-21-jdk` (Debian/Ubuntu), oppure [SDKMAN](https://sdkman.io).

Verifica in un terminale nuovo:

```bash
java -version
# deve mostrare: openjdk version "21.x.x"
```

### 1.2 IntelliJ IDEA

Scaricalo da [jetbrains.com/idea](https://www.jetbrains.com/idea/). La versione gratuita è sufficiente:
il backend è un normale progetto Maven. In alternativa va bene anche VS Code con le estensioni
"Extension Pack for Java" e "Spring Boot Extension Pack".

### 1.3 Git

Scaricalo da [git-scm.com](https://git-scm.com) (su macOS è già presente con gli strumenti di Xcode).
Poi configura nome ed email, che compariranno nei tuoi commit:

```bash
git config --global user.name "Nome Cognome"
git config --global user.email "tua@email.it"
```

### 1.4 Docker Desktop

Scaricalo da [docker.com](https://www.docker.com/products/docker-desktop/) (su Linux: Docker Engine
più il plugin Compose). Verifica che funzioni:

```bash
docker run hello-world
```

Se vedi "Hello from Docker!" è tutto a posto. Lo useremo dalla Fase 1.

---

## 2. Scaricare il progetto

```bash
git clone https://github.com/rosemgo/WalletTracker.git
cd WalletTracker
git checkout claude/quirky-brahmagupta-c4kk7j
```

> Se il repository è privato, Git ti chiederà di autenticarti: al posto della password GitHub
> vuole un *Personal Access Token*. Più semplice: installa [GitHub CLI](https://cli.github.com)
> ed esegui una volta `gh auth login`. In alternativa, da IntelliJ:
> *File → New → Project from Version Control* e accedi a GitHub dalla finestra che si apre.
>
> Se avevi già clonato il repository con il vecchio nome `test`, aggiorna l'indirizzo remoto:
> `git remote set-url origin https://github.com/rosemgo/WalletTracker.git`

## 3. Aprire il progetto in IntelliJ

1. *File → Open* e scegli la cartella `WalletTracker` (la radice del repository).
2. IntelliJ trova il file `backend/pom.xml`: quando compare l'avviso "Maven build scripts found"
   clicca **Load**. Scaricherà le dipendenze (la prima volta ci vuole qualche minuto).
3. *File → Project Structure → Project → SDK*: seleziona il JDK 21.

## 4. Primo build e primi test

Da terminale (anche quello integrato in IntelliJ):

```bash
cd backend
./mvnw test          # macOS / Linux
mvnw.cmd test        # Windows
```

La prima volta il wrapper scarica Maven e tutte le librerie. Alla fine devi vedere:

```
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

I test non contattano davvero Enable Banking: usano chiavi generate al volo e un server finto.
Per questo funzionano anche prima di registrarti.

---

## 5. Come è fatto un progetto Spring Boot

### 5.1 Come nasce un progetto

Di solito un progetto Spring Boot si genera su [start.spring.io](https://start.spring.io)
(Spring Initializr): scegli Maven, Java, la versione di Spring Boot e le dipendenze, e scarichi uno zip
con la struttura pronta. Questo progetto ha la stessa struttura (Maven, Java 21, Spring Boot 4.1).
Prova a generarne uno lì e a confrontarlo: è un buon esercizio.

### 5.2 Le cartelle

```
backend/
├── pom.xml                         ← dipendenze e configurazione di build (Maven)
├── mvnw, mvnw.cmd, .mvn/           ← Maven Wrapper: scarica la versione giusta di Maven
└── src/
    ├── main/
    │   ├── java/it/wallettracker/
    │   │   ├── WalletTrackerApplication.java    ← il main: avvia Spring
    │   │   ├── bank/enablebanking/              ← tutto ciò che parla con Enable Banking
    │   │   │   ├── EnableBankingProperties.java ← configurazione (letta da application.yml)
    │   │   │   ├── EnableBankingJwtFactory.java ← crea il token di autenticazione (JWT)
    │   │   │   ├── EnableBankingClient.java     ← le chiamate HTTP alle API
    │   │   │   └── EnableBankingApi.java        ← i DTO: il formato del JSON
    │   │   └── poc/PocRunner.java               ← il programma da console della Fase 0
    │   └── resources/
    │       ├── application.yml                  ← configurazione principale
    │       └── application-poc.yml              ← configurazione del profilo "poc"
    └── test/java/...                            ← i test (JUnit 5)
```

Convenzione Maven: il codice sta in `src/main/java`, le risorse (configurazione) in
`src/main/resources`, i test in `src/test/java`. Il risultato della build finisce in `target/`.

### 5.3 Il `pom.xml`

- **`<parent>spring-boot-starter-parent</parent>`**: eredita da Spring Boot le versioni di tutte le
  librerie, già testate insieme. Per questo le dipendenze non hanno `<version>`.
- **Gli "starter"** sono pacchetti di dipendenze pronti all'uso:
  - `spring-boot-starter-restclient`: client HTTP + Jackson per il JSON;
  - `spring-boot-starter-validation`: annotazioni come `@NotBlank`;
  - `spring-boot-starter-test`: JUnit 5, AssertJ, Mockito e gli strumenti di test di Spring.
- **`spring-boot-maven-plugin`**: crea un unico jar eseguibile con `java -jar` (e permette
  `./mvnw spring-boot:run`).

### 5.4 Cosa succede quando l'applicazione parte

1. `main()` chiama `SpringApplication.run(...)`.
2. Spring legge la configurazione: `application.yml`, il file `.env`, le variabili d'ambiente.
3. Spring cerca le classi annotate con `@Component` (e simili) nel package `it.wallettracker` e
   sotto-package, e crea un oggetto per ciascuna (un *bean*).
4. Le collega tra loro: se un costruttore chiede un `EnableBankingClient`, Spring gli passa quello
   che ha creato. Questa è la **dependency injection**: nessuna classe fa `new` delle sue dipendenze.
5. Esegue i `CommandLineRunner`, come `PocRunner`.

Nel nostro caso i collegamenti sono questi:

```
.env ──► application.yml ──► EnableBankingProperties
                                 │
                                 ├──► EnableBankingJwtFactory
                                 │          │
                                 ▼          ▼
                            EnableBankingClient ◄── RestClient.Builder (fornito da Spring Boot)
                                 │
                                 ▼
                            PocRunner  (solo con il profilo "poc")
```

### 5.5 Configurazione e profili

- In `application.yml` la sintassi `${ENABLE_BANKING_APP_ID:}` vuol dire "usa la variabile
  `ENABLE_BANKING_APP_ID`; se non c'è, usa il valore dopo i due punti (qui vuoto)".
- Le variabili arrivano dal file `.env` (importato da `spring.config.import`) oppure dalle
  variabili d'ambiente del sistema, che hanno la precedenza.
- Un **profilo** è un insieme di impostazioni attivabile a richiesta. Con il profilo `poc`
  Spring carica anche `application-poc.yml` e crea `PocRunner` (annotato con `@Profile("poc")`).

### 5.6 I test

- `EnableBankingJwtFactoryTest`: test unitario puro, senza Spring. Verifica il formato del JWT
  e che la firma sia verificabile con la chiave pubblica.
- `EnableBankingClientTest`: usa `MockRestServiceServer`, che intercetta le richieste HTTP e
  risponde con JSON di esempio. Così si testa il client senza Internet.
- `WalletTrackerApplicationTests`: `@SpringBootTest` avvia l'intera applicazione per verificare
  che tutti i componenti si colleghino correttamente.

---

## 6. Comandi di riferimento

Tutti dalla cartella `backend/`. Su Windows usa `mvnw.cmd` al posto di `./mvnw`; in PowerShell
metti tra virgolette gli argomenti `-D...`, ad esempio `"-Dspring-boot.run.profiles=poc"`.

| Comando | Cosa fa |
|---|---|
| `./mvnw test` | compila ed esegue i test |
| `./mvnw spring-boot:run -Dspring-boot.run.profiles=poc` | avvia l'app con il profilo `poc` |
| `./mvnw package` | crea `target/backend-0.1.0-SNAPSHOT.jar` |
| `java -jar target/backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=poc` | avvia il jar |

Per avviare l'app da IntelliJ: apri `WalletTrackerApplication`, clicca la freccia verde accanto al
`main`, poi *Run → Edit Configurations* e aggiungi `--spring.profiles.active=poc` in
*Program arguments*. Controlla che la *Working directory* sia la cartella `backend`.

➡️ Prossimo passo: [Fase 0, collegamento alla banca](02-fase-0-collegamento-banca.md)
