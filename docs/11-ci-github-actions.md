# 11. Integrazione continua con GitHub Actions

**Obiettivo:** a ogni `git push`, un computer di GitHub scarica il codice, compila il backend ed esegue
**tutti i test**. Se qualcosa si rompe lo scopri subito, con una ❌ accanto al commit, invece che giorni
dopo sul tuo PC (o, peggio, sui tuoi dati).

> Prerequisiti: `git pull` fatto. Non serve installare niente sul tuo PC.

---

## Parte A: i concetti

### 1. Che cos'è la CI

**CI** sta per *Continuous Integration*, integrazione continua: ogni modifica pubblicata viene subito
"integrata" e verificata in automatico. In pratica è un programma che, a ogni push, fa quello che faresti
tu a mano:

```
git clone ...      →  scarica il codice
installa Java      →  prepara l'ambiente
./mvnw verify      →  compila ed esegue i test
                   →  ✅ tutto bene  /  ❌ qualcosa si è rotto
```

Perché farlo fare a una macchina?
- **Non te ne dimentichi mai**: i test girano a ogni push, anche quando hai fretta.
- **Ambiente pulito**: ogni volta su una macchina nuova, quindi non dipende da cose installate solo sul
  tuo PC ("da me funziona!").
- **Controlla anche il lavoro degli altri**: i tuoi commit da due PC diversi, i miei, in futuro quelli di
  chi contribuirà al progetto.

### 2. Come funzionava con Jenkins (il tuo progetto universitario)

```
 GitHub ──webhook "c'è un push!"──►  NAS QNAP
                                      └─ Tomcat
                                          └─ Jenkins (.war)
                                               1. git clone da GitHub
                                               2. gradle build (i tuoi task)
                                               3. risultato
```

1. Hai **installato tu** Jenkins: un'applicazione web Java (un `.war`) dentro il tuo Tomcat, sul NAS.
2. Su GitHub hai configurato un **webhook**: a ogni push GitHub fa una richiesta HTTP a Jenkins per
   avvisarlo.
3. Jenkins, ricevuto l'avviso, **scaricava il codice da GitHub** (`git clone` / `git pull`) in una cartella
   di lavoro sul NAS (il *workspace*).
4. Poi **eseguiva lì**, sul NAS, il build con Gradle e i tuoi task: compilazione, test...

Quindi sì: **il codice veniva eseguito sulla stessa macchina dove girava Jenkins**. In Jenkins quella
macchina si chiama **nodo "controller"** (prima "master"). Nelle installazioni più grandi Jenkins può
distribuire il lavoro su altre macchine, gli **agent** (prima "slave"). Il controller coordina e gli agent
eseguono i build. Con un solo NAS, controller e agent coincidevano.

Tutto quello che serviva al build doveva essere **già installato su quella macchina**: Java, Gradle (o il
suo wrapper), e Docker se i test lo usano. Quando prima ho scritto "su Jenkins avresti dovuto installare
Docker sull'agente" intendevo proprio questo: i nostri test avviano PostgreSQL in Docker
(Testcontainers), quindi la macchina che esegue i test deve avere Docker. Sul tuo NAS avresti dovuto
installarlo e configurarlo tu.

### 3. Come funziona GitHub Actions

Lo schema è lo stesso, ma **la macchina la mette GitHub**, e la ricrea da zero ogni volta:

```
 git push ──►  GitHub
                ├─ legge .github/workflows/ci.yml   (cosa fare)
                ├─ accende una macchina virtuale Linux NUOVA (il "runner")
                │     1. scarica il codice
                │     2. installa Java 21
                │     3. ./mvnw verify  (Docker per i test c'è già)
                ├─ salva log e risultato ✅/❌
                └─ butta via la macchina
```

| | Jenkins sul NAS | GitHub Actions |
|---|---|---|
| Chi fornisce la macchina | tu (il NAS) | GitHub (una macchina virtuale per ogni esecuzione) |
| Come sa che c'è un push | webhook configurato da te | lo sa già: il codice è su GitHub |
| Dove si scrive la pipeline | configurazione di Jenkins (o un `Jenkinsfile`) + i task di Gradle | `.github/workflows/ci.yml`, **nel repository** |
| Ambiente | quello che hai installato sul NAS, sempre lo stesso | nuovo e pulito ogni volta: lo prepari nei primi passi |
| Manutenzione | aggiornare Jenkins, i plugin, il NAS | nessuna |

Una cosa resta uguale: **il build vero lo fa sempre lo strumento di build**. Nel tuo progetto erano i
task di Gradle (`gradle build`), qui è Maven (`./mvnw verify`). Jenkins e GitHub Actions sono solo i
"direttori d'orchestra": preparano la macchina, lanciano il comando, raccolgono il risultato.

### 4. Il vocabolario di GitHub Actions

| Parola | Significato | Equivalente in Jenkins |
|---|---|---|
| **workflow** | la pipeline intera, descritta in un file YAML in `.github/workflows/` | la pipeline / il job di Jenkins |
| **trigger** (`on:`) | l'evento che la fa partire: push, pull request, un orario... | il webhook, o "Build periodically" |
| **job** | un gruppo di passi eseguiti **in ordine sulla stessa macchina** | uno *stage* (più o meno) |
| **runner** (`runs-on:`) | la macchina che esegue un job | l'agent |
| **step** | un singolo passo: un comando (`run:`) o un'azione già pronta (`uses:`) | uno *step* |
| **action** | un "pezzo di pipeline" riutilizzabile scritto da altri, es. `actions/checkout` | un plugin |
| **artifact** | un file prodotto dall'esecuzione e conservato per scaricarlo | gli artefatti archiviati |

Più job in un workflow girano **in parallelo**, ognuno sulla **sua** macchina. Quando arriverà la
dashboard React aggiungeremo un job `frontend` accanto al job `backend`: i due verranno verificati
insieme, su due macchine diverse.

---

## Parte B: il file `.github/workflows/ci.yml`, riga per riga

```yaml
name: CI
```
Il nome del workflow, come compare su GitHub.

```yaml
on:
  push:
  pull_request:
```
Il **trigger**: parte a ogni push, su qualsiasi branch, e a ogni pull request. È il posto del webhook
di Jenkins, ma non devi configurare niente.

```yaml
permissions:
  contents: read
```
Durante l'esecuzione GitHub dà alla pipeline un "lasciapassare" (token) per accedere al repository. Qui
diciamo che basta **leggere** il codice. È il principio del **minimo privilegio**: se un passo della
pipeline fosse compromesso, non potrebbe modificare il repository.

```yaml
jobs:
  backend:
    name: Backend (build e test)
    runs-on: ubuntu-latest
    timeout-minutes: 20
```
- `jobs:` l'elenco dei job; ne abbiamo uno, chiamato `backend`.
- `runs-on: ubuntu-latest` sceglie il **runner**: una macchina virtuale Linux (Ubuntu), accesa da GitHub
  per noi. Arriva con molti strumenti già installati: Git, Docker, vari linguaggi. Esistono anche runner
  Windows e macOS.
- `timeout-minutes: 20`: se qualcosa si blocca, dopo 20 minuti GitHub ferma tutto (di solito ne bastano
  3–4).

```yaml
    steps:
      - name: Scarica il codice
        uses: actions/checkout@v5
```
Il primo passo. La macchina è **vuota**: non ha il nostro codice. `uses:` richiama un'**action** già
pronta, scritta da GitHub: `actions/checkout` fa il `git clone` del commit che ha fatto partire la
pipeline. `@v5` è la versione dell'action.

```yaml
      - name: Installa Java 21
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
```
Sì, **stiamo preparando l'ambiente**, proprio come avevi intuito. L'action `actions/setup-java`
scarica e installa **sulla macchina del runner** il JDK richiesto. I parametri dopo `with:` sono gli
"argomenti" che passiamo all'action:
- `distribution: temurin`: quale distribuzione di Java (Temurin di Eclipse Adoptium, la stessa che hai
  installato sul PC);
- `java-version: '21'`: la versione;
- `cache: maven`: salva la cartella delle dipendenze Maven (`~/.m2`) tra un'esecuzione e l'altra. La
  prima volta Maven scarica tutte le librerie (Spring, Hibernate...), le volte successive le ritrova e
  la pipeline è più veloce.

Perché installare Java ogni volta? Perché la macchina è nuova a ogni esecuzione: niente resta da quella
precedente, se non quello che salviamo esplicitamente in cache. È lo svantaggio (un po' di tempo) e
insieme il vantaggio (ambiente pulito e riproducibile) di questo modello.

```yaml
      - name: Compila ed esegui i test
        working-directory: backend
        run: ./mvnw -B verify
```
Il cuore della pipeline. `run:` esegue un comando nel terminale della macchina, dalla cartella `backend`.
- `./mvnw`: il Maven wrapper, lo stesso che usi tu (`mvnw.cmd` su Windows). Scarica da solo Maven.
- `verify`: compila, esegue i test e verifica il pacchetto. Corrisponde ai task di Gradle del tuo
  progetto universitario: Maven ha delle **fasi** in sequenza (`compile` → `test` → `package` →
  `verify`), e chiederne una esegue anche tutte quelle prima.
- `-B` (batch mode): niente colori e barre di avanzamento, che nei log sarebbero illeggibili.

I test di integrazione avviano PostgreSQL con **Testcontainers**, che ha bisogno di Docker. Sul runner
Ubuntu Docker è **già installato e avviato**: non serve fare nulla.

```yaml
      - name: Salva i rapporti dei test
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: rapporti-test
          path: backend/target/surefire-reports/
```
Questo passo parte **solo se qualcosa è fallito** (`if: failure()`). Salva i rapporti dei test (i file
che Maven scrive in `target/surefire-reports/`) come **artefatto**: un file zip scaricabile dalla pagina
dell'esecuzione. Visto che la macchina viene buttata via, è il modo per conservare quello che serve a
capire l'errore.

### E i segreti?

La pipeline **non usa** il tuo `.env` né la chiave `.pem`, e non deve. I test usano un Enable Banking
finto (Mockito) e una chiave RSA generata al volo. Se un giorno servisse una password, la si salverebbe
in **Settings → Secrets and variables → Actions** del repository, mai nel file YAML.

---

## Parte C: cosa fare

1. **Aggiorna:** `git pull`. Vedrai la nuova cartella `.github/workflows/`.
2. **Apri il repository su GitHub** e vai nella scheda **Actions**. Troverai il workflow **CI** e le sue
   esecuzioni, una per ogni push dal momento in cui il file esiste.
3. **Apri un'esecuzione**, poi il job **Backend (build e test)**: vedrai ogni step con il suo log. Apri
   "Compila ed esegui i test" e cerca le righe `Tests run:`.
4. **Guarda i commit:** nella lista dei commit del branch, accanto a ognuno, compare ✅ o ❌. Cliccandoci
   arrivi all'esecuzione.
5. **Esperimento: rompi un test apposta.** In `ClassificationServiceTest`, nel test
   `theCreditCardSettlementIsRecognisedByTheStarterRule`, cambia `CARD_SETTLEMENT` in `EXPENSE`, poi:
   ```powershell
   git add .
   git commit -m "Prova: test rotto apposta"
   git push
   ```
   Dopo qualche minuto la pipeline diventa ❌ e GitHub ti manda un'email. Apri l'esecuzione: vedrai quale
   test è fallito, e in fondo alla pagina l'artefatto `rapporti-test`. Poi rimetti a posto:
   ```powershell
   git revert HEAD
   git push
   ```
   `git revert` crea un nuovo commit che annulla il precedente: la storia resta pulita e onesta.

---

## Cosa hai imparato

- Che cos'è la **Continuous Integration** e perché si usa.
- Le differenze tra **Jenkins** (un server tuo, avvisato da un webhook) e **GitHub Actions** (macchine
  fornite da GitHub, nuove a ogni esecuzione).
- Il vocabolario: **workflow, trigger, job, runner, step, action, artifact**.
- Perché la pipeline **prepara l'ambiente** a ogni esecuzione, e come la **cache** la rende più veloce.
- Il **minimo privilegio** (`permissions: contents: read`) e dove si mettono i segreti.

## Prossimo passo

**Fase 2, passo 2:** la dashboard React. Nella pipeline aggiungeremo il job `frontend`.
