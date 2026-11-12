# 49. Il server in cloud: WalletTracker su Oracle Cloud "Always Free"

**Obiettivo di questa guida:** far girare WalletTracker **sempre acceso**, senza tenere acceso il PC di casa e senza
comprare un server, su una **macchina virtuale gratuita** di Oracle Cloud. È lo stesso "server" delle guide `docs/44`-`48`,
solo che invece di stare sotto la scrivania sta in un centro dati di Oracle.
- Si crea una **VM ARM gratuita** (Ampere A1) con Ubuntu.
- Ci si installano **Docker** e **Tailscale**, si scarica il progetto e si lancia `./installa.sh`, come su qualsiasi
  Linux.
- I dati arrivano dal PC di casa con un **backup** e un **ripristino** (`docs/48`, parte J).
- **Nessuna porta aperta su Internet** per l'app: la si raggiunge solo da Tailscale, come oggi.

> **Verificato il 3 novembre 2026.** Le offerte gratuite di Oracle cambiano, anche **senza annuncio**: nel 2026 è
> successo (parte A2). Prima di registrarti rileggi la pagina ufficiale *Always Free Resources* (fonti in fondo). Dove
> le fonti non sono d'accordo, questa guida lo dice. Nota onesta: la pagina ufficiale di Oracle non era raggiungibile
> direttamente da dove è stata scritta la guida; i numeri vengono dalle FAQ di Oracle e da più fonti del 2026 che li
> riportano, confrontate tra loro.

**Le parole di questa guida:**

| Parola | Che cosa vuol dire |
|---|---|
| **VM** (macchina virtuale) | un computer "finto" ricavato da un server vero di Oracle: ha il suo sistema operativo, il suo disco, il suo indirizzo. Per te è un PC Linux senza schermo |
| **istanza** | il nome che Oracle dà a una VM |
| **OCPU** | l'unità di CPU di Oracle. Su ARM, 1 OCPU = 1 core |
| **tenancy** | il tuo account Oracle, con tutto quello che ci crei dentro |
| **home region** | la regione (la città del centro dati) scelta alla registrazione. **Non si cambia più**, e le risorse gratuite esistono **solo lì** |
| **shape** | il "modello" della VM: CPU, memoria, tipo di processore. Quello gratuito ARM si chiama `VM.Standard.A1.Flex` |
| **security list** | il firewall di Oracle, **fuori** dalla VM: decide che cosa può arrivare dall'esterno |
| **SSH** | il modo di aprire un terminale sulla VM da lontano, protetto da una chiave |

**Indice**
- Parte A: che cosa dà gratis Oracle, oggi (e che cosa è cambiato nel 2026)
- Parte B: i rischi, detti chiaramente
- Parte C: la registrazione
- Parte D: un "salvadanaio" a 1 €: l'avviso di spesa
- Parte E: la chiave SSH
- Parte F: creare la VM
- Parte G: il primo accesso e la preparazione di Ubuntu
- Parte H: Docker
- Parte I: Tailscale sulla VM, e chiudere la porta SSH a Internet
- Parte J: WalletTracker sulla VM
- Parte K: portare i dati dal PC di casa
- Parte L: Enable Banking e lo spegnimento del PC di casa
- Parte M: i backup **fuori** da Oracle
- Parte N: se Oracle ferma la VM per inattività
- Parte O: aggiornamenti e manutenzione
- Parte P: prova
- Parte Q: problemi comuni
- Esercizi guidati
- Fonti

---

## Parte A: che cosa dà gratis Oracle, oggi

### A1. Le risorse "Always Free" che ci servono

"Always Free" vuol dire **senza scadenza**: non è la prova di 30 giorni (quella c'è anche, con un credito iniziale, ma
finisce). Le risorse che usa WalletTracker:

| Risorsa | Quanto, gratis | Che cosa ci facciamo |
|---|---|---|
| **VM ARM Ampere A1** | **2 OCPU e 12 GB di RAM in totale** (1.500 ore-OCPU e 9.000 GB-ore al mese) | **una** VM da 2 OCPU e 12 GB: tutta l'app, anche con Ollama e un modello piccolo |
| VM AMD "micro" | 2 VM da 1/8 di OCPU e 1 GB | non le usiamo: 1 GB è troppo poco per Java + PostgreSQL |
| **disco (block volume)** | **200 GB in totale**, tra dischi di avvio e dischi aggiuntivi | un disco di avvio da 100 GB |
| traffico in uscita | 10 TB al mese | WalletTracker ne usa pochi MB |
| Object Storage | circa 20 GB (dato da fonti non ufficiali) | facoltativo, per i backup (parte M) |

Tutto **solo nella home region**.

**Quanto consuma WalletTracker?** Senza Ollama, tra PostgreSQL, Spring Boot, nginx e il container dei backup, circa
1,5-2 GB di RAM e quasi niente CPU (lavora davvero solo durante la sincronizzazione ogni 6 ore e il backup di notte).
12 GB sono più che sufficienti; la compilazione delle immagini (Maven, npm) al primo avvio è la parte più pesante, e
con 2 core dura qualche minuto.

### A2. Che cosa è cambiato nel 2026 (importante)

Fino a giugno 2026 il limite ARM gratuito era **4 OCPU e 24 GB**: lo trovi ancora in moltissime guide e video. Oracle
lo ha **dimezzato** a **2 OCPU e 12 GB**:
- in vigore da **metà giugno 2026** (le fonti dicono il 15 giugno; qualcuna indica 5-12 giugno);
- comunicato tardi e in modo poco visibile (aggiornando la documentazione, poi una mail agli utenti);
- **applicato dal 18 agosto 2026**: secondo più fonti, le istanze gratuite che superavano il nuovo limite sono state
  **terminate** (cancellate), non solo fermate.

Due conseguenze pratiche:
1. **Non superare mai 2 OCPU e 12 GB in totale** tra tutte le tue VM ARM. Una sola VM da 2 e 12, oppure due da 1 e 6,
   ma non di più.
2. **Ignora le guide che dicono "4 core e 24 GB gratis"**: sono precedenti al cambio.

Sugli account **Pay As You Go** (parte B3) le fonti si contraddicono: alcuni utenti riferiscono che il supporto Oracle
ha detto loro che per loro valgono ancora 4 e 24 GB, altri no, e la documentazione parla di "tutte le tenancy". Per
WalletTracker non importa: **2 e 12 bastano**. Restando dentro 2 e 12 sei a posto in ogni caso.

---

## Parte B: i rischi, detti chiaramente

Una VM gratuita non è un server tuo. Prima di metterci i tuoi movimenti bancari, ecco che cosa può andare storto e
come lo copre questa guida.

### B1. La registrazione può essere rifiutata

Nel 2026 sono tante le segnalazioni di registrazioni bloccate con errori generici ("Error Processing Transaction",
"An error occurred while creating your account"), anche con carte valide. Oracle non spiega il motivo: è il suo
controllo antifrode. Le cause più citate e i rimedi sono nella parte C.

### B2. La capacità può mancare

Le VM ARM gratuite sono richiestissime: spesso, creando la VM, Oracle risponde **"Out of host capacity"**. Le FAQ di
Oracle dicono che è una mancanza temporanea nella home region e che possono servire **giorni**; consigliano di provare
un altro *availability domain* (se la regione ne ha più di uno) o di riprovare più tardi. Parte F6.

### B3. La VM può essere fermata se "non fa niente"

Oracle si riserva di **recuperare le istanze Always Free inattive**. Una VM è considerata inattiva se, per **7 giorni**,
il 95° percentile di uso della **CPU**, della **rete** e (per le ARM) della **memoria** resta sotto una soglia. Sulla
soglia esatta le fonti non concordano (10%, 15%, 20% a seconda dell'anno): Oracle l'ha cambiata più volte.

WalletTracker è proprio il tipo di app che sta ferma quasi sempre, quindi il rischio c'è. Due cose rassicurano:
- il recupero **ferma** la VM, **non** cancella il disco: la riavvii dalla console e Docker fa ripartire l'app da solo
  (parte N);
- passando l'account a **Pay As You Go** (PAYG) molti utenti riferiscono che il recupero non avviene più, e la mail di
  avviso di Oracle lo diceva esplicitamente. Le risorse Always Free restano gratuite anche con PAYG; si pagano solo
  le risorse **non** gratuite che crei. Dopo i cambi del 2026 però nessuna fonte ufficiale lo conferma con certezza.

La scelta di questa guida: **parti con l'account gratuito**. Se ricevi un avviso di recupero, o se la VM viene fermata,
valuta il passaggio a PAYG, **solo dopo** aver impostato l'avviso di spesa (parte D). Gli script che "tengono occupata"
la CPU per non sembrare inattiva esistono, ma sprecano risorse condivise e vanno contro lo spirito dell'offerta: qui non
li usiamo.

### B4. L'offerta (o l'account) può cambiare o sparire

Il 2026 l'ha dimostrato: le condizioni cambiano con poco preavviso. Per questo la regola d'oro di questa guida è: **i
backup stanno anche fuori da Oracle** (parte M). Se domani Oracle chiude il rubinetto, ripristini su un'altra VM, su un
Raspberry Pi o sul PC di casa in mezz'ora, con `--ripristina` (`docs/48`).

### B5. I tuoi dati stanno su un computer di qualcun altro

Sì. Come lo mitighiamo:
- **regione europea** (Milano o Francoforte), quindi dati sotto il GDPR;
- **nessuna porta dell'app aperta su Internet**: nginx ascolta solo su `127.0.0.1` (`docker-compose.yml`), e si
  arriva solo da Tailscale (`docs/47`);
- dopo la parte I, **neanche SSH** è aperto su Internet;
- i segreti nel database sono cifrati (`docs/17`), i backup sono cifrati con la tua password (`docs/48`);
- il disco della VM è cifrato da Oracle (con chiavi gestite da Oracle: protegge dal furto fisico, non da Oracle stessa).

Per un progetto personale è un compromesso ragionevole; se non ti va bene, l'alternativa è un mini PC o un Raspberry Pi
in casa (parte Q, ultima riga).

---

## Parte C: la registrazione

I nomi dei pulsanti possono cambiare un po': qui c'è quello che si vede oggi.

1. Apri **oracle.com/cloud/free** e premi **Start for free**.
2. Paese **Italia**, nome, cognome, email. Conferma l'email.
3. **Account name** (il nome della tenancy, per esempio `wallettracker-tuonome`): servirà per accedere. Annotalo.
4. **Home region: la scelta più importante, e definitiva.** Per noi le due sensate sono:
   - **Italy Northwest (Milan)** — `eu-milan-1`: vicina, ma con **un solo** availability domain (se è piena, si può
     solo aspettare);
   - **Germany Central (Frankfurt)** — `eu-frankfurt-1`: più grande, con più availability domain, ma molto richiesta.

   Non ci sono dati affidabili su quale delle due abbia più VM ARM libere oggi: scegline una europea e basta. La
   distanza non conta per WalletTracker (qualche millisecondo).
5. Indirizzo: **lo stesso della carta**, scritto allo stesso modo.
6. **Carta di credito** per la verifica: Oracle fa una **pre-autorizzazione** di circa 1 dollaro (o euro) che sparisce
   da sola in qualche giorno. Non addebita niente finché resti nelle risorse gratuite.

**Se la registrazione fallisce** (le cause citate più spesso, nessuna confermata da Oracle):
- carte **prepagate o virtuali** hanno poche probabilità: usa una carta di credito o di debito "vera", abilitata ai
  pagamenti internazionali;
- **niente VPN o proxy** durante la registrazione (Tailscale va bene, ma disattiva eventuali "exit node");
- indirizzo diverso da quello della carta;
- troppi tentativi ravvicinati dallo stesso dispositivo: aspetta un giorno, prova da un altro browser o dal telefono;
- se continua, apri una richiesta sul forum *Oracle Cloud Customer Connect* o alla chat del supporto vendite.

Alla fine ricevi una mail "Your account is ready". Di solito parte anche la **prova gratuita** con un credito per 30
giorni: non serve, e alla fine l'account torna da solo "Always Free".

---

## Parte D: un "salvadanaio" a 1 €: l'avviso di spesa

Prima di creare qualsiasi cosa, un allarme che ti scrive appena Oracle sta per addebitarti qualcosa. È gratis, e ti
protegge se per errore crei una risorsa a pagamento (o se un giorno passi a PAYG).

1. Menu ☰ → **Billing & Cost Management** → **Budgets** → **Create Budget**.
2. Nome `allarme-spesa`, **Budget amount**: `1` (nella valuta dell'account).
3. **Threshold**: tipo *Percentage of budget*, valore `1` (cioè appena superi 1 centesimo), su **Actual spend**.
4. **Email recipients**: la tua email. Salva.

Se un giorno arriva quella mail, entra subito nella console e guarda che cosa sta costando.

---

## Parte E: la chiave SSH

Per entrare nella VM non si usa una password, ma una **coppia di chiavi**: la chiave **privata** resta solo sul tuo PC,
quella **pubblica** va sulla VM. Chi non ha la privata non entra (è lo stesso principio della chiave di Enable Banking,
`docs/02`).

Sul **PC di casa** (PowerShell su Windows, terminale su macOS e Linux):

```bash
ssh-keygen -t ed25519 -C "wallettracker-oracle" -f ~/.ssh/wallettracker-oracle
```

Ti chiede una **passphrase**: mettila (protegge la chiave se qualcuno ti ruba il PC). Nascono due file:
- `~/.ssh/wallettracker-oracle` — la **privata**: non mandarla a nessuno, non metterla nel cloud;
- `~/.ssh/wallettracker-oracle.pub` — la **pubblica**: è quella da dare a Oracle.

Puoi anche far generare le chiavi a Oracle durante la creazione della VM (*Generate a key pair for me*), ma poi devi
scaricare la privata e conservarla tu: tanto vale crearla in casa.

---

## Parte F: creare la VM

Menu ☰ → **Compute** → **Instances** → **Create instance**. Controlla in alto a destra che la regione sia la tua home
region.

### F1. Nome e posizione
- **Name**: `wallettracker`.
- **Placement**: lascia l'availability domain proposto (se è "AD-1", "AD-2"… tienine nota: servirà se manca capacità).

### F2. L'immagine: Ubuntu per ARM
**Image and shape** → **Edit** → **Change image** → **Canonical Ubuntu** → scegli l'ultima **LTS** (24.04 o 26.04)
nella versione **aarch64** (ARM). Deve comparire l'etichetta **Always Free-eligible**.

Perché Ubuntu e non Oracle Linux (la proposta di serie)? Funzionerebbe anche Oracle Linux, ma Ubuntu è la
distribuzione più usata nelle guide di Docker e Tailscale, e l'utente di serie (`ubuntu`) ha l'**id 1000**: lo stesso
dei container di WalletTracker (`docs/45`, parte F), quindi niente problemi di permessi su `.data` e `backup/`.

### F3. La shape: 2 OCPU, 12 GB
**Change shape** → **Ampere** → **VM.Standard.A1.Flex** → **Number of OCPUs**: `2`, **Amount of memory (GB)**: `12`.
Anche qui: etichetta **Always Free-eligible**. **Non** salire oltre (parte A2).

### F4. La rete
- **Primary VNIC** → **Create new virtual cloud network** e **Create new public subnet** (i nomi proposti vanno bene).
- **Assign a public IPv4 address**: **sì**. Serve solo per il primo accesso SSH; dopo la parte I la porta resta chiusa
  e l'indirizzo pubblico non dà accesso a niente.

### F5. Chiave SSH e disco
- **Add SSH keys** → **Upload public key files (.pub)** → il file `wallettracker-oracle.pub` della parte E (oppure
  *Paste public keys* e incolli il suo contenuto).
- **Boot volume** → **Specify a custom boot volume size**: `100` GB. Il valore di serie (circa 50 GB) basterebbe, ma le
  immagini Docker, la cache di compilazione, Ollama e i backup crescono; 100 GB lasciano margine e restano dentro i
  200 GB gratuiti. Lascia la cifratura di serie.

Premi **Create**. Dopo uno o due minuti lo stato diventa **Running** e nella pagina compare il **Public IP address**:
annotalo.

### F6. "Out of host capacity"
Se compare questo errore, la regione non ha VM ARM libere in questo momento. In ordine:
1. se la regione ha più availability domain, torna a **Placement** e prova gli altri;
2. prova una shape più piccola (**1 OCPU e 6 GB**): a volte c'è posto per quella e non per l'altra. Per WalletTracker
   senza Ollama basta;
3. riprova più tardi, anche a orari "strani" (notte, mattina presto): la capacità si libera quando altri cancellano le
   loro VM. Possono servire giorni.

---

## Parte G: il primo accesso e la preparazione di Ubuntu

Dal **PC di casa**:

```bash
ssh -i ~/.ssh/wallettracker-oracle ubuntu@INDIRIZZO_PUBBLICO
```

Alla prima connessione chiede di confermare l'"impronta" del server: scrivi `yes`. Ora sei **nella VM**: ogni comando
fino alla parte J si scrive qui.

Aggiorna il sistema e riavvia:

```bash
sudo apt update && sudo apt full-upgrade -y
```

```bash
sudo reboot
```

Dopo un minuto rientra con lo stesso comando `ssh`.

**Fuso orario** (il backup automatico usa `TZ=Europe/Rome` dentro il container, ma i log della VM sono più leggibili
con l'ora italiana):

```bash
sudo timedatectl set-timezone Europe/Rome
```

**Gli aggiornamenti di sicurezza automatici** di Ubuntu (`unattended-upgrades`) sono già attivi di serie. Controlla:

```bash
systemctl is-enabled unattended-upgrades
```

Deve dire `enabled`.

**Il firewall dentro la VM:** le immagini Ubuntu di Oracle hanno già regole `iptables` che lasciano entrare solo SSH.
**Non attivare `ufw`** e non toccarle: Docker e Tailscale aggiungono le loro regole, e `ufw` con Docker crea conflitti
noti. Il nostro firewall vero è la security list di Oracle (parte I3) più il fatto che l'app ascolta solo su
`127.0.0.1`.

---

## Parte H: Docker

Su un server Linux non c'è Docker Desktop: si installa **Docker Engine** con il plugin **Compose** (il comando
`docker compose`, quello che usa `installa.sh`). Il modo più breve è lo script ufficiale di Docker:

```bash
curl -fsSL https://get.docker.com -o get-docker.sh
```

```bash
less get-docker.sh
```

(guardalo, è buona abitudine: esci con `q`), poi:

```bash
sudo sh get-docker.sh
```

Lo script riconosce Ubuntu ARM e installa Docker Engine, `docker compose` e `buildx`. (Docker lo consiglia per
ambienti di prova; per un server personale va bene. In alternativa, la procedura "apt repository" su
docs.docker.com fa la stessa cosa in più passi.)

Fai usare Docker al tuo utente senza `sudo`, poi **esci e rientra** perché valga:

```bash
sudo usermod -aG docker ubuntu
```

```bash
exit
```

Rientrato, controlla:

```bash
docker run --rm hello-world
```

```bash
docker compose version
```

```bash
systemctl is-enabled docker
```

L'ultimo deve dire `enabled`: Docker parte da solo all'avvio della VM, e con lui i container di WalletTracker
(hanno tutti `restart: unless-stopped`). È quello che fa ripartire l'app dopo un riavvio o dopo un recupero (parte N).

> Il gruppo `docker` equivale, in pratica, a essere amministratore della VM. Su una VM con un solo utente, il tuo, va
> bene; non aggiungere altri utenti a quel gruppo.

**ARM:** tutte le immagini di partenza di WalletTracker (`eclipse-temurin:21`, `maven`, `node:24-alpine`,
`nginx-unprivileged`, `postgres:18-alpine`, `ollama/ollama`) esistono anche per ARM (`arm64`), e backend e frontend si
compilano sulla VM: non serve cambiare niente.

---

## Parte I: Tailscale sulla VM, e chiudere la porta SSH a Internet

### I1. Installare e collegare

È la stessa installazione Linux di `docs/47`, parte C3:

```bash
curl -fsSL https://tailscale.com/install.sh | sh
```

```bash
sudo tailscale up --ssh
```

`--ssh` attiva **Tailscale SSH**: potrai entrare nella VM da qualunque tuo dispositivo della tailnet, senza porta 22
aperta a Internet e senza dover avere la chiave della parte E su ogni dispositivo (l'accesso lo decide Tailscale, con
il tuo account). Il comando scrive un indirizzo: aprilo nel browser **del PC di casa** e accedi con lo stesso account
Tailscale degli altri dispositivi.

Poi, perché `installa.sh --tailscale` possa configurare Tailscale senza `sudo` (come in `docs/47`):

```bash
sudo tailscale set --operator=ubuntu
```

### I2. Il nome della VM nella tailnet

Nella console di Tailscale (**login.tailscale.com/admin/machines**) la VM compare con il suo nome (di solito
`wallettracker` o `instance-…`). Dai un nome **neutro** (`docs/47`, parte E1: finirà in un registro pubblico dei
certificati), per esempio `wt-cloud`: menu **⋯** accanto alla VM → **Edit machine name…**.

Già che ci sei, nello stesso menu scegli **Disable key expiry**: le "chiavi" dei dispositivi Tailscale di serie
scadono dopo alcuni mesi e chiedono di riaccedere dal browser. Per un server senza schermo conviene che non scadano
(sul telefono e sul PC lasciale scadere: lì riaccedere è facile).

Prova dal **PC di casa** (che ha Tailscale, `docs/47`):

```bash
ssh ubuntu@wt-cloud
```

Se entri, Tailscale SSH funziona.

### I3. Chiudere la porta 22 a Internet

Ora che entri da Tailscale, la porta SSH aperta al mondo non serve più: tutti i server con SSH aperto ricevono ogni
giorno migliaia di tentativi di accesso automatici. Chiudila nella **security list** di Oracle:

1. Console → **Networking** → **Virtual cloud networks** → la tua VCN → **Security** (o **Security Lists**) →
   **Default Security List for …**.
2. Tra le **Ingress Rules** c'è quella con **Source** `0.0.0.0/0`, **Protocol** TCP, **Destination port** `22`:
   **eliminala**. Le regole ICMP puoi lasciarle.
3. Non serve aggiungere niente per Tailscale: si collega **dall'interno verso l'esterno** (come il browser), quindi non
   ha bisogno di porte in ingresso. Se vuoi connessioni dirette invece che passare da un "relay" di Tailscale, puoi
   aggiungere una regola in ingresso **UDP 41641** da `0.0.0.0/0` (lo suggerisce la guida di Tailscale per Oracle); per
   WalletTracker la velocità del relay è più che sufficiente, quindi è facoltativo.

Verifica: dal PC di casa `ssh -i ~/.ssh/wallettracker-oracle ubuntu@INDIRIZZO_PUBBLICO` ora **non** deve rispondere,
mentre `ssh ubuntu@wt-cloud` sì.

**Se un giorno Tailscale non funziona** e sei chiuso fuori: nella pagina della VM c'è **Console connection** (la console
seriale, dal browser) e c'è **Cloud Shell**; oppure rimetti per qualche minuto la regola della porta 22. Per la console
seriale serve una password per l'utente `ubuntu`: impostala adesso, una volta, e conservala nel tuo gestore di
password:

```bash
sudo passwd ubuntu
```

---

## Parte J: WalletTracker sulla VM

Sempre nella VM (`ssh ubuntu@wt-cloud`):

```bash
git clone https://github.com/rosemgo/WalletTracker.git
```

```bash
cd WalletTracker
```

```bash
./installa.sh --senza-browser
```

`--senza-browser` perché la VM non ha schermo. Lo script fa le stesse cose di `docs/45`: crea il `.env` con una password
del database generata a caso, la cartella `.data`, compila le immagini (la prima volta qualche minuto) e avvia. Alla
fine il backend è "healthy".

Con Ollama (facoltativo, `docs/27`): `./installa.sh --ia --senza-browser`. Con 12 GB di RAM un modello piccolo ci sta;
in alternativa usa Gemini dalle Impostazioni, che non occupa memoria sulla VM.

Poi Tailscale per l'app (`docs/47`, parte E):

```bash
./installa.sh --tailscale --senza-browser
```

Scrive nel `.env` il nome della VM (`wt-cloud.<la-tua-rete>.ts.net`) e l'indirizzo di ritorno di Enable Banking, e
configura `tailscale serve` verso la porta "remota" (8001, dove serve sempre il PIN).

### J1. Aprire l'app "dal server", senza schermo

In `docs/46` e `docs/47` alcune cose si fanno solo **dal server**, cioè dalla porta 8000 (per esempio scegliere il PIN
la prima volta, scaricare un backup dalla dashboard). Ma la VM non ha un browser. La soluzione è un **tunnel SSH**: dal
PC di casa porti la porta 8000 della VM sul tuo PC.

```bash
ssh -L 9000:localhost:8000 ubuntu@wt-cloud
```

Finché quel terminale resta aperto, sul **PC di casa** apri `http://localhost:9000`: per l'app sei "sul server". Uso
9000 e non 8000 perché sul PC di casa la 8000 potrebbe essere già occupata dal WalletTracker di sviluppo. Va bene lo
stesso: l'app controlla il nome (`localhost`), non la porta (`LocalOnlyRequestFilter`).

È sicuro? Sì: per aprire il tunnel serve entrare in SSH nella VM, cioè essere sulla tua tailnet con il tuo account.

---

## Parte K: portare i dati dal PC di casa

È la parte J di `docs/48`, con la VM al posto del server.

1. **Sul PC di casa**, dalla cartella del progetto: `./installa.sh --backup` (su Windows `installa.ps1 -Backup`). Nasce
   un file in `backup/`, per esempio `backup/wallettracker-manuale-20261103-101500.backup`.
2. **Copialo sulla VM** passando da Tailscale:

   ```bash
   scp backup/wallettracker-manuale-20261103-101500.backup ubuntu@wt-cloud:~/
   ```

3. **Sulla VM** ripristina. Lo script chiede la **password del backup** (quella del PC di casa) e, siccome nel `.env`
   della VM non ce n'è ancora una, la tiene: da quella notte partono i backup automatici con la stessa password.

   ```bash
   cd ~/WalletTracker && ./installa.sh --ripristina ~/wallettracker-manuale-20261103-101500.backup --senza-browser
   ```

   Conferma con `RIPRISTINA`. Arrivano conti, movimenti, regole, impostazioni, PIN e segreti (la chiave privata di
   Enable Banking salvata in Impostazioni compresa: viaggia nel backup con la sua chiave di cifratura, `docs/48`,
   parte B).
4. **Solo se** le credenziali di Enable Banking sono nel `.env` e non in Impostazioni: copia anche `secrets/enable-banking.pem`
   (`scp secrets/enable-banking.pem ubuntu@wt-cloud:~/WalletTracker/secrets/`) e le righe `ENABLE_BANKING_…` del
   `.env`, poi rilancia `./installa.sh --senza-browser`.
5. Cancella la copia del backup dalla cartella home della VM (`rm ~/wallettracker-manuale-*.backup`): l'originale è sul
   PC di casa, e i backup della VM vanno nella sua cartella `backup/`.

---

## Parte L: Enable Banking e lo spegnimento del PC di casa

**L'indirizzo di ritorno.** La VM ha un nome Tailscale suo, quindi un indirizzo di ritorno suo:
`https://wt-cloud.<la-tua-rete>.ts.net/callback`. In Impostazioni dell'app (`docs/47`, parte I4) lo vedi scritto;
aggiungilo tra i *redirect URL* della tua applicazione nel pannello di Enable Banking. Tienilo **in aggiunta** a quelli
che ci sono, così puoi ancora collegare banche dal WalletTracker di sviluppo se ti serve.

**Un solo WalletTracker che legge le banche.** Se restano accesi sia il PC di casa sia la VM, entrambi sincronizzano
dalla banca ognuno nel proprio database: doppio traffico verso Enable Banking e due storie diverse. Sul PC di casa:

```bash
docker compose down
```

```bash
tailscale serve --https=443 off
```

(Su Windows gli stessi comandi in PowerShell.) Per lo **sviluppo** continui a usare il "modo 2" di `docs/47` (database
di sviluppo, `./mvnw spring-boot:run`, `npm start`), che ha un database suo e non disturba.

**Sui dispositivi remoti** (telefono, portatile): apri `https://wt-cloud.<la-tua-rete>.ts.net`, metti il PIN, e se
avevi installato l'app (PWA, `docs/42`) dal vecchio indirizzo, reinstallala dal nuovo.

---

## Parte M: i backup **fuori** da Oracle

Sulla VM il backup automatico di ogni notte (`docs/48`, parte G) continua a funzionare, ma i file stanno **sullo stesso
disco** della VM. Se Oracle cancella la VM o l'account (parte B4), spariscono con lei. Per la regola del 3-2-1
(`docs/48`, parte A3) serve almeno una copia altrove. I file sono cifrati: puoi metterli ovunque.

**Il modo più semplice: il PC di casa li scarica quando è acceso.** Dalla cartella del progetto sul PC di casa:

```bash
scp "ubuntu@wt-cloud:WalletTracker/backup/*.backup" ./backup-cloud/
```

(crea prima la cartella `backup-cloud`, e aggiungila al `.gitignore` se è dentro il progetto; oppure usa una cartella
fuori dal progetto.) Fallo ogni tanto, per esempio la domenica: non serve che il PC sia acceso sempre, serve solo che
ogni tanto una copia esca dalla VM. Su Linux e macOS lo può fare anche `cron`, su Windows l'Utilità di pianificazione;
`rsync -a` al posto di `scp` copia solo i file nuovi.

I file sulla VM appartengono all'utente 1000 con permessi 600 (`docs/48`, parte F): su Ubuntu l'utente `ubuntu` **è**
l'utente 1000, quindi `scp` li legge senza `sudo`.

**Alternative:** l'Object Storage gratuito di Oracle (circa 20 GB) con `rclone`, o un altro servizio cloud. Attenzione
però: un bucket di **Oracle** sparisce con l'account Oracle, quindi non protegge dal rischio B4. Meglio un posto non
Oracle.

**Prova il ripristino** almeno una volta, sul PC di casa, con un backup scaricato dalla VM (`docs/48`, parte A4: un
backup mai ripristinato è solo una speranza).

---

## Parte N: se Oracle ferma la VM per inattività

Che cosa vedi: l'app non risponde da Tailscale, la console di Tailscale mostra `wt-cloud` offline, e (di solito) arriva
una mail di Oracle sul recupero delle istanze inattive. Nella console Oracle la VM è **Stopped**.

1. Console → **Compute** → **Instances** → `wallettracker` → **Start**.
2. Dopo un paio di minuti Docker parte da solo, e con lui i container (`restart: unless-stopped`); Tailscale si ricollega
   da solo.
3. Controlla da SSH: `cd ~/WalletTracker && docker compose ps` deve mostrare tutto "Up" e il backend "healthy".
4. Le sincronizzazioni mancate non sono un problema: alla successiva l'app scarica i movimenti dei giorni persi (entro i
   limiti di storico della banca, `docs/08`).

Se succede spesso, è il momento di valutare il passaggio a **Pay As You Go** (parte B3), con l'avviso di spesa della
parte D già attivo: Console → **Billing & Cost Management** → **Upgrade and Manage Payment**.

---

## Parte O: aggiornamenti e manutenzione

| Che cosa | Come | Quando |
|---|---|---|
| sicurezza di Ubuntu | da sola (`unattended-upgrades`) | sempre |
| riavvio dopo aggiornamenti del kernel | se all'accesso SSH compare `*** System restart required ***`: `sudo reboot` | ogni tanto |
| WalletTracker | `cd ~/WalletTracker && git pull && ./installa.sh --senza-browser` | quando aggiorni il codice |
| spazio su disco | `df -h /` e `docker system df`; per liberare la cache di compilazione: `docker builder prune` | ogni qualche mese |
| backup fuori da Oracle | parte M | ogni settimana |
| controllo dei limiti gratuiti | rileggi la pagina *Always Free Resources* | quando Oracle manda mail |

---

## Parte P: prova

1. `ssh ubuntu@wt-cloud` entra; `ssh -i ~/.ssh/wallettracker-oracle ubuntu@INDIRIZZO_PUBBLICO` **non** risponde.
2. Sulla VM: `cd ~/WalletTracker && docker compose ps` mostra `db`, `backend`, `web`, `backup` attivi, il backend
   "healthy".
3. Dal telefono, con Tailscale acceso e il Wi-Fi **spento** (rete mobile): `https://wt-cloud.<la-tua-rete>.ts.net`
   chiede il PIN e mostra i tuoi movimenti.
4. Dal PC di casa, con il tunnel della parte J1: `http://localhost:9000` apre l'app senza PIN.
5. Riavvia la VM (`sudo reboot`): dopo due minuti il punto 3 funziona di nuovo, senza fare niente.
6. Il giorno dopo: in Impostazioni → Backup c'è il backup automatico della notte; dal PC di casa lo scarichi con la
   parte M.
7. Spegni il PC di casa per un giorno: al rientro, i movimenti nuovi ci sono (la sincronizzazione delle 6 ore è
   avvenuta sulla VM).
8. Console Oracle → **Billing** → **Cost Analysis**: spesa **0**.

---

## Parte Q: problemi comuni

| Problema | Causa probabile | Soluzione |
|---|---|---|
| "Out of host capacity" creando la VM | VM ARM esaurite nella home region | parte F6 |
| la registrazione fallisce con un errore generico | controllo antifrode di Oracle | parte C, "Se la registrazione fallisce" |
| la shape A1 non è "Always Free-eligible" | regione sbagliata (non la home region) o valori oltre 2 OCPU / 12 GB | controlla regione e valori |
| `installa.sh`: "Docker non risponde" | utente non ancora nel gruppo `docker` | `sudo usermod -aG docker ubuntu`, poi esci e rientra |
| `permission denied` su `.data` o `backup/` | la cartella non è dell'utente 1000 | su Ubuntu di Oracle l'utente `ubuntu` è il 1000: se usi un altro utente, `docs/45`, parte F |
| la compilazione si ferma con "killed" | memoria finita (succede solo con la VM da 1 OCPU / 6 GB e Ollama acceso) | spegni Ollama durante la compilazione, o aggiungi uno swap file |
| il telefono non apre `https://wt-cloud…` | Tailscale sul telefono spento, o `--tailscale` non lanciato sulla VM | `docs/47`, parte P |
| dopo qualche mese la VM sparisce dalla tailnet | è scaduta la chiave del dispositivo Tailscale | riaccedi con `sudo tailscale up --ssh` dalla console seriale (parte I3), poi **Disable key expiry** |
| Enable Banking dice "redirect URL not allowed" | manca l'indirizzo della VM nel pannello di Enable Banking | parte L |
| la VM è "Stopped" senza che tu l'abbia fermata | recupero per inattività | parte N |
| arriva la mail dell'avviso di spesa | è stata creata una risorsa non gratuita | console → **Cost Analysis**, trova la risorsa e cancellala |
| Oracle cambia di nuovo le regole e non ti va più bene | parte B4 | ripristina un backup su un'altra macchina: un mini PC o un Raspberry Pi in casa (5-10 W) o una VPS ARM economica (pochi euro al mese) |

---

## Esercizi guidati

1. **Quanto consuma davvero?** Sulla VM, `docker stats --no-stream` mostra memoria e CPU di ogni container. Sommali: quanta
   parte dei 12 GB usa WalletTracker? E con Ollama acceso e un modello caricato?
2. **Il percentile.** Oracle guarda il 95° percentile della CPU su 7 giorni. Se la VM è al 2% per tutta la settimana
   tranne 30 minuti al giorno al 100% (la compilazione, una sincronizzazione), il 95° percentile è alto o basso? Quanti
   minuti al giorno al 100% servirebbero per superare il 5% più alto della settimana? (Suggerimento: il 5% di una
   settimana sono circa 8 ore e 24 minuti.)
3. **Chi può entrare?** Elenca tutte le strade per arrivare all'app sulla VM (porte, servizi, account) e per ognuna chi
   la può usare. Confronta con il server di casa di `docs/47`: che cosa cambia, e che cosa no?
4. **Il disastro.** Immagina che domani l'account Oracle sia chiuso. Scrivi, in ordine, i comandi per avere di nuovo
   WalletTracker funzionante sul PC di casa, con i dati di ieri. Che cosa ti serve, e dove sta?

---

## Fonti

Consultate il 3 novembre 2026. Le pagine ufficiali di Oracle vanno ricontrollate prima di registrarsi.

- Oracle, [Always Free Resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm) e [riferimento delle risorse](https://docs.oracle.com/en-us/iaas/Content/FreeTier/resourceref.htm) (limiti, home region, recupero delle istanze inattive)
- Oracle, [FAQ del Free Tier](https://www.oracle.com/cloud/free/faq/) ("Out of host capacity")
- Oracle, [la regione di Milano](https://docs.oracle.com/iaas/releasenotes/changes/f4d5bd9e-c628-4eab-9d6a-f2452767350b)
- InfoQ, [Oracle Quietly Halves Free Tier Ampere A1 Compute Limits](https://www.infoq.com/news/2026/07/oracle-cloud-free-tier-limits/) (luglio 2026)
- terminalbytes, [Oracle Cloud free tier 2026: 4 OCPU/24GB cut to 2 OCPU/12GB](https://terminalbytes.com/oracle-cloud-free-tier-changes-2026/)
- ETTAYEB, [Oracle Halves Always Free ARM Limits… Until August 18, 2026](https://ettayeb.fr/en/selfhosted/oracle-always-free-arm-limits-halved-2026/)
- daily.dev, [la confusione sugli account Pay As You Go](https://daily.dev/posts/oracle-quietly-halves-free-tier-ampere-a1-compute-limits-with-no-public-announcement-ldgjrd9vs)
- fullmetalbrackets, [Breaking down the free tier of Oracle Cloud Infrastructure](https://fullmetalbrackets.com/blog/oci-free-tier-breakdown)
- Site2US, [Oracle Cloud Always Free (2026 Limits)](https://site2us.com/en/servers/oracle-cloud-free-tier) e AI Skill Market, [Keeping Your Free Oracle VPS From Getting Reclaimed](https://aiskill.market/blog/keeping-your-free-oracle-vps-from-being-reclaimed) (soglie del recupero per inattività)
- Draft, [Is Oracle Cloud's Free Tier Still Worth It in 2026?](https://draft-publish.com/articles/is-oracle-clouds-free-tier-still-worth-it-in-2026--0c75b5b7) e Oracle Customer Connect, [registrazione bloccata](https://community.oracle.com/customerconnect/discussion/948402/free-tier-sign-up-blocked-error-processing-transaction-despite-valid-card)
- vpsrankings, [Oracle Cloud Free VPS Setup Tutorial 2026](https://vpsrankings.com/en/articles/fd3guspb-oracle-cloud-free-vps-setup-tutorial-2026-step-by-step) (cause dei rifiuti in registrazione)
- Tailscale, documentazione su Oracle Cloud e su Tailscale SSH (tailscale.com/kb)
