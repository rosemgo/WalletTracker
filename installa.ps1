# Installa e avvia WalletTracker su Windows (Fase 4, passo 2: docs/45).
# Su macOS e Linux c'è lo stesso script per il terminale: installa.sh.
#
# Si lancia da PowerShell, nella cartella del progetto:
#   powershell -ExecutionPolicy Bypass -File installa.ps1                      primo avvio, e tutte le volte dopo
#   powershell -ExecutionPolicy Bypass -File installa.ps1 -Ia                  con anche Ollama (l'IA locale)
#   powershell -ExecutionPolicy Bypass -File installa.ps1 -CambiaPasswordDb    password nuova per il database
#   powershell -ExecutionPolicy Bypass -File installa.ps1 -SenzaBrowser        non apre il browser alla fine
#   powershell -ExecutionPolicy Bypass -File installa.ps1 -ReimpostaPin        PIN dimenticato: lo azzera
#   powershell -ExecutionPolicy Bypass -File installa.ps1 -Tailscale           l'app sui tuoi dispositivi (docs/47)
#   powershell -ExecutionPolicy Bypass -File installa.ps1 -Backup              un backup adesso (docs/48)
#   powershell -ExecutionPolicy Bypass -File installa.ps1 -Ripristina FILE     SOSTITUISCE i dati con quelli del backup
#   powershell -ExecutionPolicy Bypass -File installa.ps1 -Aggiorna            l'ultima versione, con un backup prima (docs/50)
#   powershell -ExecutionPolicy Bypass -File installa.ps1 -CartellaBackup      sceglie (o cambia) la cartella dei backup
#
# "-ExecutionPolicy Bypass": Windows, di serie, non esegue gli script scaricati. Così il permesso vale solo per
# questo comando, senza cambiare le impostazioni del PC.
#
# Si può rilanciare quante volte si vuole: quello che c'è già (il .env, la chiave, i dati) non viene toccato.
# Fa le stesse cose di installa.sh, nello stesso ordine: vedi i commenti lì per i dettagli.

param(
    [switch]$Ia,
    [switch]$CambiaPasswordDb,
    [switch]$ReimpostaPin,
    [switch]$Tailscale,
    [switch]$Backup,
    [string]$Ripristina = '',
    [switch]$Aggiorna,
    [switch]$CartellaBackup,
    [switch]$SenzaBrowser
)

# "Continue" e non "Stop": con "Stop" Windows PowerShell tratta come un errore qualsiasi riga che un programma
# esterno (docker) scrive sullo standard error, anche i semplici messaggi di avanzamento. Gli errori veri li
# controlliamo a mano, con $LASTEXITCODE (il codice di uscita dell'ultimo programma: 0 = tutto bene).
$ErrorActionPreference = 'Continue'

# Tutti i percorsi partono dalla cartella dello script, da qualunque cartella lo si lanci.
Set-Location -Path $PSScriptRoot

# Il .env si legge e si scrive in UTF-8 SENZA "BOM": Windows PowerShell, di serie, aggiungerebbe all'inizio del
# file tre byte invisibili (il BOM), e Docker Compose li leggerebbe come parte del primo nome di variabile.
$Utf8 = New-Object System.Text.UTF8Encoding $false
$EnvFile = Join-Path $PSScriptRoot '.env'

function Passo([string]$testo) { Write-Host ""; Write-Host "==> $testo" -ForegroundColor Cyan }

function Errore([string]$testo) {
    Write-Host ""
    Write-Host "ERRORE: $testo" -ForegroundColor Red
    exit 1
}

# 32 lettere e cifre dal generatore casuale crittografico di Windows. Solo lettere e cifre: niente caratteri che
# nel .env, in un URL o in SQL vanno protetti. Si scartano i byte oltre 247 (= 4 x 62 - 1) perché ogni carattere
# abbia la stessa probabilità.
function Nuova-Password {
    $alfabeto = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
    $generatore = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $byte = New-Object byte[] 1
    $password = ''
    while ($password.Length -lt 32) {
        $generatore.GetBytes($byte)
        if ($byte[0] -lt 248) { $password += $alfabeto[$byte[0] % 62] }
    }
    return $password
}

function Leggi-Env {
    if (-not (Test-Path $EnvFile)) { return @() }
    return [System.IO.File]::ReadAllText($EnvFile, $Utf8) -split "`r?`n"
}

# Il valore di una variabile del .env (l'ultima riga NOME=..., come fa Docker Compose); vuoto se manca.
function Valore-Env([string]$nome) {
    $valore = ''
    foreach ($riga in (Leggi-Env)) {
        if ($riga.StartsWith("$nome=")) { $valore = $riga.Substring($nome.Length + 1) }
    }
    return $valore
}

# Scrive NOME=valore nel .env: sostituisce la riga se c'è, la aggiunge in fondo se manca. Prima un file
# temporaneo, poi lo si mette al posto del .env: se qualcosa si interrompe a metà, il .env vecchio resta intero.
function Scrivi-Env([string]$nome, [string]$valore) {
    $righe = New-Object System.Collections.Generic.List[string]
    $fatto = $false
    foreach ($riga in (Leggi-Env)) {
        if ($riga.StartsWith("$nome=")) { $righe.Add("$nome=$valore"); $fatto = $true }
        else { $righe.Add($riga) }
    }
    if (-not $fatto) { $righe.Add("$nome=$valore") }
    $temporaneo = "$EnvFile.nuovo"
    [System.IO.File]::WriteAllText($temporaneo, ($righe -join "`r`n"), $Utf8)
    Move-Item -Force -Path $temporaneo -Destination $EnvFile
}

# Aspetta che il controllo di salute di un servizio (es. backend, il container wallettracker-backend) dica "healthy" (al massimo $massimo secondi).
function Aspetta-Salute([string]$servizio, [int]$massimo) {
    $container = "wallettracker-$servizio"
    $stato = ''
    for ($secondi = 0; $secondi -lt $massimo; $secondi += 3) {
        $stato = (& docker inspect --format '{{.State.Health.Status}}' $container 2>$null)
        if ($stato -eq 'healthy') { return }
        Start-Sleep -Seconds 3
    }
    Errore "$servizio non è pronto dopo $massimo secondi (stato: $stato). Guarda i log con:  docker compose logs $servizio"
}

# --- 0. Da dove arrivano le immagini (docs/50, parte C) ----------------------------------------------------
# Come in installa.sh: con il codice sorgente (backend/pom.xml) le immagini si costruiscono dal codice, versione
# "sviluppo"; in una versione scaricata (il file VERSION) si scaricano già pronte. $env:WALLETTRACKER_VERSION la legge
# docker-compose.yml, nel nome delle immagini.
$DalCodice = Test-Path 'backend/pom.xml'
if ($DalCodice) { $env:WALLETTRACKER_VERSION = 'sviluppo' }
else {
    if (-not (Test-Path 'VERSION')) {
        Errore 'Manca il file VERSION: scarica WalletTracker dalla pagina delle versioni su GitHub (docs/50) e lancia lo script dalla sua cartella.'
    }
    $env:WALLETTRACKER_VERSION = ([System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'VERSION'))).Trim()
}

if ($Aggiorna) {
    # Dal codice si aggiorna con Git, non con le versioni pubblicate: lo script non tocca il tuo lavoro.
    if ($DalCodice) {
        Write-Host 'Questa è la cartella del CODICE SORGENTE (c''è backend/pom.xml): -Aggiorna serve alle versioni scaricate.'
        Write-Host 'Per avere le novità dal codice:  git switch main   poi   git pull   e rilancia lo script.'
        exit 0
    }
    if ($Backup -or $Ripristina -ne '' -or $CambiaPasswordDb -or $ReimpostaPin -or $Tailscale) {
        Errore '-Aggiorna va lanciato da solo (al massimo con -Ia): le altre operazioni falle prima o dopo.'
    }
}

# --- 1. Docker ---------------------------------------------------------------------------------------------
Passo 'Controllo Docker'
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Errore 'Docker non è installato: installa Docker Desktop (docs/45, parte B).'
}
& docker info *> $null
if ($LASTEXITCODE -ne 0) {
    # Docker Desktop spento (docs/50, parte O): lo accendo io e aspetto che risponda, al massimo 3 minuti.
    $dockerDesktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (-not (Test-Path $dockerDesktop)) {
        Errore 'Docker non risponde, e non trovo Docker Desktop: avvialo tu (o installalo: docs/45, parte B), aspetta che dica "Engine running" e riprova.'
    }
    Write-Host 'Docker Desktop era spento: lo sto accendendo (al massimo 3 minuti)...'
    Start-Process -FilePath $dockerDesktop
    $secondi = 0
    while ($true) {
        & docker info *> $null
        if ($LASTEXITCODE -eq 0) { break }
        if ($secondi -ge 180) { Errore 'Docker Desktop non risponde dopo 3 minuti: aprilo tu, aspetta che dica "Engine running" e riprova.' }
        Start-Sleep -Seconds 3
        $secondi += 3
    }
}
& docker compose version *> $null
if ($LASTEXITCODE -ne 0) { Errore 'Manca Docker Compose v2 (il comando "docker compose"): aggiorna Docker Desktop.' }
Write-Host 'Docker è avviato.'

# --- 2. Il file .env ---------------------------------------------------------------------------------------
Passo 'Configurazione (.env)'
if (-not (Test-Path $EnvFile)) {
    if (-not (Test-Path '.env.example')) {
        Errore 'Manca .env.example: lo script va lanciato dalla cartella del progetto scaricato.'
    }
    # Il .env manca, ma il database di un'installazione precedente c'è ancora: ha la SUA password, scritta nel
    # vecchio .env. Con una password nuova il backend non entrerebbe (installa.sh spiega lo stesso).
    & docker volume inspect wallettracker_db-data *> $null
    if ($LASTEXITCODE -eq 0) {
        Errore 'Manca il file .env, ma c''è già il database di un''installazione precedente, con la sua password. Rimetti qui il vecchio .env (dal backup) e rilancia lo script. Se invece vuoi ripartire da zero, CANCELLANDO tutti i dati di prima, i comandi sono in docs/45, parte M.'
    }
    Copy-Item '.env.example' $EnvFile
    Scrivi-Env 'POSTGRES_PASSWORD' (Nuova-Password)
    Write-Host 'Creato il file .env, con una password del database generata a caso.'
}
else {
    Write-Host 'Il file .env c''è già: lo uso così com''è.'
    $attuale = Valore-Env 'POSTGRES_PASSWORD'
    if ($attuale -eq '') {
        # Un .env copiato a mano, con la password vuota: senza password Compose non avrebbe mai avviato il
        # database, quindi non esiste ancora un database con un'altra password.
        Scrivi-Env 'POSTGRES_PASSWORD' (Nuova-Password)
        Write-Host 'Mancava la password del database: ne ho generata una a caso.'
    }
    elseif (($attuale -eq 'cambia-questa-password' -or $attuale.Length -lt 16) -and -not $CambiaPasswordDb) {
        Write-Host 'ATTENZIONE: la password del database è quella d''esempio, o è più corta di 16 caratteri.' -ForegroundColor Yellow
        Write-Host '            Per sostituirla con una generata a caso, rilancia lo script con -CambiaPasswordDb' -ForegroundColor Yellow
    }
}

# --- 2b. La cartella dei backup (una volta sola, o con -CartellaBackup: docs/48, parte F) -------------------
# Come in installa.sh (vedi i commenti lì): la si sceglie qui, senza aprire il .env.
function Chiedi-Cartella-Backup {
    $attuale = Valore-Env 'BACKUP_DIR'
    Write-Host 'Dove salvo i backup? Meglio fuori da questo disco: un disco esterno, o la cartella di Google Drive, OneDrive o'
    Write-Host 'Dropbox sul computer (con Google Drive per desktop: "Esegui il mirroring dei file"). I backup sono cifrati.'
    if ($attuale -eq '') { Write-Host 'Adesso: la cartella "backup" qui accanto.' } else { Write-Host "Adesso: $attuale." }
    Write-Host 'Potrai cambiarla quando vuoi con -CartellaBackup.'
    $risposta = Read-Host "Scrivi (o incolla) il percorso di una cartella, oppure premi Invio per lasciarla com'è"
    # Gli spazi e le virgolette che Esplora file aggiunge con "Copia come percorso".
    $risposta = ([string]$risposta).Trim().Trim('"', "'").Trim()
    if ($risposta -eq '') {
        # Scritta anche quella di serie: così la domanda non torna a ogni avvio.
        if ($attuale -eq '') { Scrivi-Env 'BACKUP_DIR' './backup' }
        if ($attuale -eq '') { Write-Host 'La cartella dei backup resta backup, qui accanto.' } else { Write-Host "La cartella dei backup resta $attuale." }
        return
    }
    if ($risposta -match '[$#`]') { Errore 'Il percorso non può contenere $, # o `: scegli un''altra cartella.' }
    try { New-Item -ItemType Directory -Force -Path $risposta -ErrorAction Stop | Out-Null }
    catch { Errore "Non riesco a creare la cartella $risposta`: controlla il percorso, o che il disco sia collegato." }
    # Nel .env le barre sono "/": Docker Compose le capisce anche su Windows (es. D:/Backup/WalletTracker).
    $percorso = (Resolve-Path $risposta).Path.Replace('\', '/')
    Scrivi-Env 'BACKUP_DIR' $percorso
    Write-Host "I backup andranno in $percorso (BACKUP_DIR nel .env). Quelli vecchi restano dove sono: spostali tu, se vuoi."
}

if ($CartellaBackup) {
    Passo 'La cartella dei backup'
    Chiedi-Cartella-Backup
}
elseif ((Valore-Env 'BACKUP_DIR') -eq '' -and $Ripristina -eq '' -and -not [Console]::IsInputRedirected) {
    # Nel .env non c'è ancora BACKUP_DIR: si chiede una volta, a qualunque avvio (anche il doppio clic su "Avvia"),
    # e solo da una finestra vera (installa.sh, parte 2b).
    Passo 'La cartella dei backup'
    Chiedi-Cartella-Backup
}

# --- 3. Le cartelle ----------------------------------------------------------------------------------------
# La cartella dei backup: di serie backup/ nel progetto; BACKUP_DIR nel .env per un'altra (docs/48).
$CartellaBackupScelta = Valore-Env 'BACKUP_DIR'
if ($CartellaBackupScelta -eq '') { $CartellaBackupScelta = './backup' }
New-Item -ItemType Directory -Force -Path '.data', 'secrets', $CartellaBackupScelta | Out-Null
$CartellaBackupScelta = (Resolve-Path $CartellaBackupScelta).Path

# --- 4. Cambiare la password del database (solo con -CambiaPasswordDb) ------------------------------------
if ($CambiaPasswordDb) {
    Passo 'Cambio della password del database'
    $utente = Valore-Env 'POSTGRES_USER'
    $database = Valore-Env 'POSTGRES_DB'
    if ($utente -eq '' -or $database -eq '') { Errore 'Nel .env mancano POSTGRES_USER o POSTGRES_DB.' }
    # Prima nel database (la "serratura"), poi nel .env (la "chiave"): docs/44, parte E2, e docs/45, parte E.
    & docker compose up -d db | Out-Null
    Aspetta-Salute 'db' 60
    $nuova = Nuova-Password
    & docker compose exec -T db psql -q -v ON_ERROR_STOP=1 -U $utente -d $database -c "ALTER USER `"$utente`" PASSWORD '$nuova'"
    if ($LASTEXITCODE -ne 0) { Errore 'Il database non ha accettato la password nuova: niente è cambiato.' }
    Scrivi-Env 'POSTGRES_PASSWORD' $nuova
    Write-Host 'Password cambiata nel database e salvata nel .env. Il backend la riceve al riavvio, qui sotto.'
}

# --- 4b. Azzerare il PIN (solo con -ReimpostaPin) ---------------------------------------------------------
# Il PIN dimenticato, e perso anche il codice di recupero: il PIN non si legge (nel database c'è solo il suo hash,
# docs/46), si cancella. Alla prossima apertura dal PC l'app chiede di nuovo: con un PIN o senza. Si fa solo da qui, dal PC.
if ($ReimpostaPin) {
    Passo 'Azzero il PIN'
    $utente = Valore-Env 'POSTGRES_USER'
    $database = Valore-Env 'POSTGRES_DB'
    & docker compose up -d db | Out-Null
    Aspetta-Salute 'db' 60
    & docker compose exec -T db psql -q -v ON_ERROR_STOP=1 -U $utente -d $database -c 'DELETE FROM app_pin'
    if ($LASTEXITCODE -ne 0) { Errore 'Non sono riuscito ad azzerare il PIN (l''app è già stata avviata almeno una volta?).' }
    Write-Host 'PIN azzerato. Apri l''app da qui, dal server (http://localhost:8000): sceglierai di nuovo, con un PIN nuovo o senza.'
}

# --- 4c. Tailscale (solo con -Tailscale) -------------------------------------------------------------------
# Le stesse tre cose di installa.sh (vedi i commenti lì): tailscale serve verso la porta "remota" di nginx, il nome
# del PC tra i nomi consentiti, l'indirizzo di ritorno di Enable Banking per quel nome (docs/47).

# Aggiunge un valore a una variabile del .env fatta di valori separati da virgole, se non c'è già. $true se l'ha aggiunto.
function Aggiungi-A-Elenco([string]$nome, [string]$nuovo, [string]$predefinito) {
    $attuale = Valore-Env $nome
    if ($attuale -eq '') { $attuale = $predefinito }
    if (($attuale -split ',') -contains $nuovo) { return $false }
    # Con l'elenco vuoto (e niente valore di serie), il nuovo da solo: senza una virgola davanti.
    if ($attuale -eq '') { Scrivi-Env $nome $nuovo } else { Scrivi-Env $nome "$attuale,$nuovo" }
    return $true
}

$NomeTs = ''
if ($Tailscale) {
    Passo 'Tailscale'
    # Il programma di installazione di Tailscale lo mette nel PATH; se no, lo cerchiamo dove si installa di solito.
    $TsCmd = (Get-Command tailscale -ErrorAction SilentlyContinue).Source
    if (-not $TsCmd) {
        $predefinito = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
        if (Test-Path $predefinito) { $TsCmd = $predefinito }
        else { Errore 'Non trovo Tailscale: installalo e accedi con il tuo account (docs/47, parte C), poi rilancia.' }
    }

    # tailscale status --json: lo stato di Tailscale in JSON. ConvertFrom-Json lo trasforma in un oggetto:
    # BackendState vale "Running" quando è collegato; Self.DNSName è il nome di questo PC, con un punto finale.
    $testo = (& $TsCmd status --json 2>$null) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $testo -eq '') { Errore 'Tailscale non risponde: aprilo e accedi con il tuo account (docs/47, parte C).' }
    $stato = $testo | ConvertFrom-Json
    if ($stato.BackendState -ne 'Running') { Errore 'Tailscale è installato ma non collegato: aprilo, accedi con il tuo account e rilancia.' }
    $NomeTs = ([string]$stato.Self.DNSName).TrimEnd('.')
    if (-not $NomeTs.EndsWith('.ts.net')) {
        Errore 'Non trovo il nome del server nella rete Tailscale: accendi MagicDNS nella console di Tailscale (docs/47, parte E1) e rilancia.'
    }
    Write-Host "Il nome di questo server nella rete Tailscale è: $NomeTs"

    # Funnel pubblica su Internet: per WalletTracker mai (docs/47, parte F5).
    $serve = (& $TsCmd serve status --json 2>$null) -join "`n"
    if ($serve -match '"AllowFunnel"\s*:\s*\{[^}]*:\s*true') {
        Errore "Tailscale Funnel è acceso: renderebbe l'app raggiungibile da Internet. Spegnilo con:  tailscale funnel --https=443 off   e rilancia."
    }

    $portaRemota = Valore-Env 'REMOTE_PORT'
    if ($portaRemota -eq '') { $portaRemota = '8001' }
    Write-Host "Chiedo a Tailscale di ricevere i tuoi dispositivi su https://$NomeTs e di passarli a 127.0.0.1:$portaRemota."
    Write-Host "(Se è la prima volta, Tailscale può chiederti di attivare HTTPS per la tua rete: apri il link che scrive,"
    Write-Host " conferma, e lo script prosegue da solo.)"
    & $TsCmd serve --bg --https=443 "http://127.0.0.1:$portaRemota"
    if ($LASTEXITCODE -ne 0) { Errore 'tailscale serve non è riuscito: leggi il messaggio qui sopra (docs/47, parte P).' }

    if (Aggiungi-A-Elenco 'WALLETTRACKER_ALLOWED_HOSTS' $NomeTs 'localhost,127.0.0.1') {
        Write-Host "Aggiunto $NomeTs ai nomi consentiti dal backend (WALLETTRACKER_ALLOWED_HOSTS nel .env)."
    }
    # Come in installa.sh: dal codice anche quello di ng serve (npm start), da una versione scaricata solo Tailscale.
    $ritornoDiSerie = ''
    if ($DalCodice) { $ritornoDiSerie = 'https://localhost:8443/callback' }
    if (Aggiungi-A-Elenco 'ENABLE_BANKING_REDIRECT_URL' "https://$NomeTs/callback" $ritornoDiSerie) {
        Write-Host "Aggiunto https://$NomeTs/callback agli indirizzi di ritorno di Enable Banking (nel .env)."
    }
}

# --- 4d. La password dei backup (con -Backup, se manca) ---------------------------------------------------
# Le stesse regole di installa.sh (vedi i commenti lì): almeno 12 caratteri, senza quelli che nel .env danno problemi.
function Password-Backup-Valida([string]$p) {
    return ($p.Length -ge 12) -and ($p -notmatch '[''"`$\\#]') -and ($p -eq $p.Trim())
}

# Legge una riga senza mostrarla (Read-Host -AsSecureString) e la restituisce come testo.
function Leggi-Segreto([string]$domanda) {
    $sicura = Read-Host -Prompt $domanda -AsSecureString
    $puntatore = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sicura)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($puntatore) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($puntatore) }
}

$PasswordBackup = Valore-Env 'WALLETTRACKER_BACKUP_PASSWORD'
# Anche per -Aggiorna: il backup di prima dell'aggiornamento è quello che permette di tornare indietro.
if (($Backup -or $Aggiorna) -and $PasswordBackup -eq '') {
    Passo 'La password dei backup'
    if ([Console]::IsInputRedirected) {
        Errore 'Manca la password dei backup: lancia lo script con -Backup da PowerShell (la chiede), o scrivila nel .env come WALLETTRACKER_BACKUP_PASSWORD (docs/48).'
    }
    Write-Host 'Scegli la password dei backup. Ogni backup sarà cifrato con lei: chi trova il file senza la password non'
    Write-Host 'legge niente. Almeno 12 caratteri; niente virgolette, apostrofi, $, \, ` e #, né spazi all''inizio o alla fine.'
    Write-Host 'SCRIVILA ANCHE IN UN POSTO SICURO, FUORI DA QUESTO COMPUTER (un gestore di password): senza, i backup non si aprono.' -ForegroundColor Yellow
    while ($true) {
        $prima = Leggi-Segreto 'Password dei backup'
        $seconda = Leggi-Segreto 'Ripetila'
        if (-not (Password-Backup-Valida $prima)) { Write-Host 'Non va bene: almeno 12 caratteri, e senza i caratteri qui sopra. Riprova.' }
        elseif ($prima -ne $seconda) { Write-Host 'Le due password non sono uguali. Riprova.' }
        else { break }
    }
    Scrivi-Env 'WALLETTRACKER_BACKUP_PASSWORD' $prima
    $PasswordBackup = $prima
    Write-Host 'Password salvata nel .env: da stanotte parte anche il backup automatico.'
}

# --- 5. Avvio ----------------------------------------------------------------------------------------------
$Profilo = @()
if ($Ia) { $Profilo = @('--profile', 'ia') }
if ($DalCodice) {
    Passo 'Avvio di WalletTracker dal codice (la prima volta ci vuole qualche minuto: si compilano le immagini)'
    & docker compose -f docker-compose.yml -f docker-compose.build.yml @Profilo up -d --build
}
else {
    Passo "Avvio di WalletTracker $($env:WALLETTRACKER_VERSION) (la prima volta si scaricano le immagini, qualche centinaio di MB)"
    # Senza rete, se le immagini ci sono già tutte, si va avanti con quelle (vedi installa.sh).
    & docker compose @Profilo pull
    if ($LASTEXITCODE -ne 0) {
        foreach ($immagine in 'backend', 'web', 'backup') {
            & docker image inspect "ghcr.io/rosemgo/wallettracker-$immagine`:$($env:WALLETTRACKER_VERSION)" *> $null
            if ($LASTEXITCODE -ne 0) {
                Errore "Non riesco a scaricare le immagini della versione $($env:WALLETTRACKER_VERSION) (controlla la connessione a Internet) e sul computer non ci sono ancora."
            }
        }
        Write-Host 'ATTENZIONE: non ho potuto controllare le immagini su Internet; uso quelle già scaricate.' -ForegroundColor Yellow
    }
    & docker compose @Profilo up -d
}
if ($LASTEXITCODE -ne 0) { Errore 'docker compose non è riuscito ad avviare l''app: leggi il messaggio qui sopra.' }

# Dopo aver azzerato il PIN, il backend si riavvia: le sessioni aperte (anche su dispositivi persi) si chiudono.
if ($ReimpostaPin) { & docker compose restart backend | Out-Null }

$Attesa = 240
if ($env:WALLETTRACKER_ATTESA_BACKEND) { $Attesa = [int]$env:WALLETTRACKER_ATTESA_BACKEND }
Passo 'Aspetto che il backend sia pronto'
Aspetta-Salute 'backend' $Attesa
Write-Host 'Il backend è pronto.'

# --- 5b. Aggiornare all'ultima versione (solo con -Aggiorna: docs/50, parte I) ----------------------------
# Gli stessi passi di installa.sh (vedi i commenti lì). PowerShell legge tutto lo script prima di cominciare: si può
# sostituire il file mentre gira.
if ($Aggiorna) {
    $indirizzoVersioni = 'https://api.github.com/repos/rosemgo/WalletTracker/releases/latest'
    if ($env:WALLETTRACKER_RELEASES_URL) { $indirizzoVersioni = $env:WALLETTRACKER_RELEASES_URL }
    # La seconda strada, se il servizio API non risponde: la pagina normale, che rinvia a quella dell'ultima versione.
    $paginaVersioni = 'https://github.com/rosemgo/WalletTracker/releases/latest'
    if ($env:WALLETTRACKER_RELEASES_PAGE) { $paginaVersioni = $env:WALLETTRACKER_RELEASES_PAGE }
    $agg = Join-Path $PSScriptRoot '.aggiornamento'
    $cartellaNuova = Join-Path $agg 'nuova'
    $precedente = Join-Path $agg 'precedente'
    # Windows PowerShell 5.1: https moderno (TLS 1.2) e niente barra di avanzamento, che rallenta moltissimo gli scaricamenti.
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $ProgressPreference = 'SilentlyContinue'
    # Il motivo vero di un errore di rete, in una frase: il codice HTTP se c'è (403/429: il limite di richieste di GitHub
    # senza account, 60 all'ora per indirizzo, che si azzera da solo), altrimenti il messaggio di Windows (nome non
    # trovato, connessione rifiutata, proxy...). Prima diceva sempre "controlla la connessione", anche quando non era quella.
    function Motivo-Rete($errore) {
        $codice = $null
        if ($errore.Exception.Response) { try { $codice = [int]$errore.Exception.Response.StatusCode } catch { } }
        if ($codice -eq 403 -or $codice -eq 429) {
            return "GitHub ha rifiutato la richiesta (codice $codice): di solito è il limite di richieste senza account, che si azzera entro un'ora. Riprova più tardi."
        }
        if ($codice -ge 500) {
            return "GitHub non risponde in questo momento (codice $codice), anche dopo 3 tentativi: di solito dura pochi minuti, riprova più tardi. Se continua, una VPN, un proxy o l'antivirus potrebbero mettersi in mezzo."
        }
        if ($codice) { return "GitHub ha risposto con il codice $codice." }
        return "Windows dice: $($errore.Exception.Message) Controlla la connessione a Internet (e, se c'è, il proxy o l'antivirus)."
    }
    # Una richiesta a GitHub, ritentata fino a 3 volte (5 secondi tra l'una e l'altra) se l'errore è passeggero: nessuna
    # risposta, un 5xx (GitHub momentaneamente non disponibile) o 429. Un altro errore (404, 403) non cambia riprovando.
    # È quello che fa curl --retry nella versione per Linux e Mac.
    function Con-Tentativi([scriptblock]$richiesta) {
        for ($tentativo = 1; ; $tentativo++) {
            try { return & $richiesta }
            catch {
                $errore = $_  # salvato: il try qui sotto non deve cambiare l'errore che si rilancia
                $codice = $null
                if ($errore.Exception.Response) { try { $codice = [int]$errore.Exception.Response.StatusCode } catch { } }
                $passeggero = ($null -eq $codice) -or ($codice -ge 500) -or ($codice -eq 429)
                if (-not $passeggero -or $tentativo -ge 3) { throw $errore }
                Write-Host "GitHub non ha risposto (tentativo $tentativo di 3): riprovo tra 5 secondi..."
                Start-Sleep -Seconds 5
            }
        }
    }
    # I file da non toccare mai, anche se uno ZIP li contenesse.
    function Da-Non-Toccare([string]$f) { return $f -eq '.env' -or $f -match '^(\.data|backup|secrets|\.aggiornamento)/' }
    function Metti-Al-Posto([string]$da, [string]$dove) {
        $cartella = Split-Path -Parent $dove
        if ($cartella -and -not (Test-Path $cartella)) { New-Item -ItemType Directory -Force -Path $cartella | Out-Null }
        Copy-Item -Force -Path $da -Destination "$dove.nuovo"
        Move-Item -Force -Path "$dove.nuovo" -Destination $dove
    }
    # I file di una cartella, con il percorso relativo e le barre "/" (come nello ZIP).
    function File-Di([string]$cartella) {
        $base = (Resolve-Path $cartella).Path.TrimEnd('\', '/')
        return Get-ChildItem -Path $cartella -Recurse -File -Force |
            ForEach-Object { $_.FullName.Substring($base.Length + 1).Replace('\', '/') }
    }

    Passo 'Cerco una versione nuova'
    if (Test-Path $cartellaNuova) { Remove-Item -Recurse -Force $cartellaNuova }
    New-Item -ItemType Directory -Force -Path $cartellaNuova | Out-Null
    # Prima strada: il servizio API (api.github.com).
    $ultima = $null
    $dallaPagina = $false
    try { $ultima = Con-Tentativi { Invoke-RestMethod -UseBasicParsing -TimeoutSec 30 -Uri $indirizzoVersioni -Headers @{ Accept = 'application/vnd.github+json' } } }
    catch {
        # Seconda strada: la pagina normale rinvia a quella dell'ultima versione (…/releases/tag/v1.2.0), e il numero è
        # in fondo all'indirizzo d'arrivo. Gli indirizzi dei due file si ricavano dal numero.
        $primoMotivo = Motivo-Rete $_
        Write-Host "Il servizio di GitHub per le versioni non risponde: provo dalla pagina delle versioni."
        try { $pagina = Con-Tentativi { Invoke-WebRequest -UseBasicParsing -TimeoutSec 30 -Uri $paginaVersioni } }
        catch { Errore "Non riesco a chiedere a GitHub l'ultima versione, né dal servizio API ($primoMotivo) né dalla pagina delle versioni ($(Motivo-Rete $_)). Non è cambiato niente." }
        # L'indirizzo d'arrivo, dopo i rinvii: in Windows PowerShell 5.1 è in ResponseUri, in PowerShell 7 nella richiesta.
        $arrivo = ''
        if ($pagina.BaseResponse.ResponseUri) { $arrivo = [string]$pagina.BaseResponse.ResponseUri.AbsoluteUri }
        elseif ($pagina.BaseResponse.RequestMessage.RequestUri) { $arrivo = [string]$pagina.BaseResponse.RequestMessage.RequestUri.AbsoluteUri }
        $tag = ''
        if ($arrivo -match '/releases/tag/(v[0-9]+\.[0-9]+\.[0-9]+)$') { $tag = $Matches[1] }
        $dallaPagina = $true
    }
    if (-not $dallaPagina) { $tag = [string]$ultima.tag_name }
    if ($tag -notmatch '^v[0-9]+\.[0-9]+\.[0-9]+$') { Errore "GitHub ha risposto qualcosa che non capisco (versione: `"$tag`"). Non è cambiato niente." }
    $nuova = $tag.Substring(1)
    $adesso = $env:WALLETTRACKER_VERSION
    if ($adesso -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') { Errore "Il file VERSION non contiene una versione valida (`"$adesso`"). Non è cambiato niente." }
    # [version] confronta i numeri uno per uno: 1.10.0 è più nuova di 1.9.0.
    if (-not ([version]$nuova -gt [version]$adesso)) {
        Remove-Item -Recurse -Force $cartellaNuova
        Passo "Hai già l'ultima versione: WalletTracker $adesso"
        exit 0
    }
    Write-Host "C'è una versione nuova: $nuova (adesso hai la $adesso)."

    if ($dallaPagina) {
        $baseDownload = $paginaVersioni -replace '/latest$', "/download/$tag"
        $urlZip = "$baseDownload/WalletTracker-$nuova.zip"
        $urlImpronte = "$baseDownload/SHA256SUMS"
    }
    else {
        $urlZip = [string]($ultima.assets | Where-Object { $_.name -eq "WalletTracker-$nuova.zip" } | Select-Object -First 1).browser_download_url
        $urlImpronte = [string]($ultima.assets | Where-Object { $_.name -eq 'SHA256SUMS' } | Select-Object -First 1).browser_download_url
    }
    if ($urlZip -eq '' -or $urlImpronte -eq '') { Errore "La versione $nuova non ha lo ZIP o SHA256SUMS. Non è cambiato niente." }
    if (-not $env:WALLETTRACKER_RELEASES_URL -and -not $env:WALLETTRACKER_RELEASES_PAGE) {
        $origine = "https://github.com/rosemgo/WalletTracker/releases/download/$tag/"
        if (-not $urlZip.StartsWith($origine) -or -not $urlImpronte.StartsWith($origine)) {
            Errore 'Gli indirizzi dei file non sono quelli della pagina delle versioni di WalletTracker. Non è cambiato niente.'
        }
    }

    Passo "Scarico WalletTracker $nuova e controllo che sia integro"
    $zip = Join-Path $cartellaNuova "WalletTracker-$nuova.zip"
    $impronte = Join-Path $cartellaNuova 'SHA256SUMS'
    try {
        Con-Tentativi { Invoke-WebRequest -UseBasicParsing -TimeoutSec 600 -Uri $urlZip -OutFile $zip }
        Con-Tentativi { Invoke-WebRequest -UseBasicParsing -TimeoutSec 60 -Uri $urlImpronte -OutFile $impronte }
    }
    catch { Errore "Lo scaricamento non è riuscito. $(Motivo-Rete $_) Non è cambiato niente." }
    $attesa = ''
    foreach ($riga in [System.IO.File]::ReadAllLines($impronte)) {
        $parti = $riga -split '\s+', 2
        if ($parti.Count -eq 2 -and $parti[1].TrimStart('*') -eq "WalletTracker-$nuova.zip") { $attesa = $parti[0] }
    }
    if ($attesa -eq '' -or (Get-FileHash -Algorithm SHA256 -Path $zip).Hash -ine $attesa) {
        Errore 'Lo ZIP scaricato NON corrisponde alla sua impronta (SHA256SUMS): è rovinato o non è quello pubblicato. Non l''ho usato; non è cambiato niente.'
    }
    Expand-Archive -Path $zip -DestinationPath $cartellaNuova -Force
    $sorgente = Join-Path $cartellaNuova "WalletTracker-$nuova"
    $versioneZip = ''
    if (Test-Path (Join-Path $sorgente 'VERSION')) { $versioneZip = ([System.IO.File]::ReadAllText((Join-Path $sorgente 'VERSION'))).Trim() }
    if (-not (Test-Path (Join-Path $sorgente 'installa.ps1')) -or -not (Test-Path (Join-Path $sorgente 'docker-compose.yml')) -or $versioneZip -ne $nuova) {
        Errore "Lo ZIP non contiene la versione $nuova come me l'aspetto. Non è cambiato niente."
    }
    Write-Host 'Lo ZIP è integro.'

    Passo "Le novità dalla $adesso alla $nuova"
    $dentro = $false
    foreach ($riga in [System.IO.File]::ReadAllLines((Join-Path $sorgente 'NOVITA.md'), $Utf8)) {
        if ($riga -eq "## $adesso") { break }
        if ($riga.StartsWith('## ')) { $dentro = $true }
        if ($dentro) { Write-Host $riga }
    }
    Write-Host ''
    $risposta = Read-Host "Aggiorno WalletTracker alla $nuova? Prima faccio un backup dei dati. [s/N]"
    if ($risposta -notmatch '^(s|si|sì)$') {
        Write-Host 'Aggiornamento annullato: non è cambiato niente.'
        exit 0
    }

    Passo 'Backup prima dell''aggiornamento'
    $uscita = & docker compose run --rm -T backup crea prima-dell-aggiornamento 2>&1 | ForEach-Object { "$_" }
    $codiceBackup = $LASTEXITCODE
    $uscita | ForEach-Object { Write-Host $_ }
    if ($codiceBackup -ne 0) { Errore 'Il backup non è riuscito: aggiornamento annullato, non è cambiato niente.' }
    $nomeBackup = ''
    foreach ($riga in $uscita) {
        if ($riga -match 'Backup creato: (wallettracker-prima-dell-aggiornamento-[0-9-]+\.backup)') { $nomeBackup = $Matches[1] }
    }
    if ($nomeBackup -eq '') { Errore 'Non trovo il nome del backup appena fatto: aggiornamento annullato, non è cambiato niente.' }

    Passo "Sostituisco i file dell'app (la $adesso la metto da parte in .aggiornamento\precedente)"
    if (Test-Path $precedente) { Remove-Item -Recurse -Force $precedente }
    New-Item -ItemType Directory -Force -Path $precedente | Out-Null
    $fileNuovi = @(File-Di $sorgente | Where-Object { -not (Da-Non-Toccare $_) })
    $aggiunti = New-Object System.Collections.Generic.List[string]
    foreach ($f in $fileNuovi) {
        if (Test-Path $f) { Metti-Al-Posto $f (Join-Path $precedente $f) }
        else { $aggiunti.Add($f) }
    }
    [System.IO.File]::WriteAllText((Join-Path $precedente '.aggiunti'), ($aggiunti -join "`n"), $Utf8)
    foreach ($f in $fileNuovi) { Metti-Al-Posto (Join-Path $sorgente $f) $f }

    Passo "Avvio di WalletTracker $nuova (con lo script nuovo)"
    $powershell = (Get-Process -Id $PID).Path
    $opzioni = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'installa.ps1'), '-SenzaBrowser')
    if ($Ia) { $opzioni += '-Ia' }
    & $powershell @opzioni
    $codice = $LASTEXITCODE
    # Il controllo finale: il backend acceso deve essere proprio quello della versione nuova. (Le etichette in JSON:
    # Windows PowerShell rovina le virgolette dentro gli argomenti dei programmi esterni.)
    $avviata = ''
    try { $avviata = [string]((& docker inspect --format '{{json .Config.Labels}}' wallettracker-backend 2>$null | ConvertFrom-Json).'org.opencontainers.image.version') } catch { }
    if ($codice -eq 0 -and $avviata -eq $nuova) {
        Remove-Item -Recurse -Force $cartellaNuova
        Passo "Aggiornamento completato: WalletTracker $nuova è avviato"
        Write-Host "Il backup di prima dell'aggiornamento è $nomeBackup (nella cartella dei backup)."
        Write-Host "I file della $adesso restano in .aggiornamento\precedente fino al prossimo aggiornamento."
        $porta = Valore-Env 'WEB_PORT'
        if ($porta -eq '') { $porta = '8000' }
        if (-not $SenzaBrowser) { Start-Process "http://localhost:$porta" }
        exit 0
    }

    # --- Non è andata: si torna alla versione di prima, da soli ---
    Passo "L'aggiornamento non è riuscito: torno alla versione $adesso"
    foreach ($f in [System.IO.File]::ReadAllText((Join-Path $precedente '.aggiunti'), $Utf8) -split "`n") {
        if ($f -ne '' -and (Test-Path $f)) { Remove-Item -Force $f }
    }
    foreach ($f in (File-Di $precedente | Where-Object { $_ -ne '.aggiunti' })) { Metti-Al-Posto (Join-Path $precedente $f) $f }
    $env:WALLETTRACKER_VERSION = $adesso
    & docker compose @Profilo up -d db *> $null
    & docker compose stop backend backup *> $null
    & docker compose run --rm -T backup ripristina $nomeBackup
    if ($LASTEXITCODE -ne 0) {
        Errore "Non sono riuscito a rimettere i dati di prima. I file della $adesso sono di nuovo al loro posto; per i dati: rilancia lo script con  -Ripristina backup\$nomeBackup   (docs/50, parte I)."
    }
    & docker compose @Profilo up -d
    if ($LASTEXITCODE -ne 0) { Errore "Non riesco a riavviare la versione $adesso`: leggi il messaggio qui sopra (docs/50, parte I)." }
    Aspetta-Salute 'backend' $Attesa
    Errore "Sono tornato a WalletTracker $adesso, con i dati di prima dell'aggiornamento: è tutto com'era. Il motivo è nei messaggi qui sopra; lo ZIP della $nuova è rimasto in .aggiornamento\nuova (docs/50, parte I)."
}

# --- 6. Backup e ripristino (solo con -Backup o -Ripristina) ----------------------------------------------
# Il lavoro lo fa il container "backup" (docker/backup/wallettracker-backup.sh): vedi i commenti in installa.sh.
if ($Backup) {
    Passo 'Backup'
    & docker compose run --rm -T backup crea manuale
    if ($LASTEXITCODE -ne 0) { Errore 'Il backup non è riuscito: leggi il messaggio qui sopra.' }
    Write-Host "Il backup è nella cartella $CartellaBackupScelta. Copialo anche FUORI da questo computer (docs/48, parte F)."
}

if ($Ripristina -ne '') {
    Passo "Ripristino da $Ripristina"
    if (-not (Test-Path $Ripristina -PathType Leaf)) { Errore "Non trovo il file $Ripristina." }
    # Il container vede solo la cartella dei backup: se il file è altrove, lo copio lì (con lo stesso nome).
    $file = (Resolve-Path $Ripristina).Path
    $nomeBackup = Split-Path $file -Leaf
    $destinazione = Join-Path $CartellaBackupScelta $nomeBackup
    if ($file -ne $destinazione) {
        if ((Test-Path $destinazione) -and ((Get-FileHash $file).Hash -ne (Get-FileHash $destinazione).Hash)) {
            Errore "Nella cartella dei backup c'è già un file diverso di nome $nomeBackup`: rinomina quello da ripristinare."
        }
        Copy-Item $file $destinazione -Force
        Write-Host "Copiato $nomeBackup nella cartella dei backup ($CartellaBackupScelta)."
    }

    Write-Host ''
    Write-Host 'ATTENZIONE: il ripristino SOSTITUISCE tutti i dati di adesso (conti, movimenti, regole, impostazioni, PIN)' -ForegroundColor Yellow
    Write-Host 'con quelli del backup. Prima ne faccio una copia di sicurezza, se nel .env c''è la password dei backup.' -ForegroundColor Yellow
    $risposta = Read-Host 'Per continuare scrivi RIPRISTINA'
    if ($risposta -cne 'RIPRISTINA') { Errore 'Ripristino annullato: non è cambiato niente.' }

    # 1. La copia di sicurezza di adesso, con la password del .env.
    if (Password-Backup-Valida $PasswordBackup) {
        $env:WALLETTRACKER_BACKUP_PASSWORD = $PasswordBackup
        & docker compose run --rm -T backup crea prima-del-ripristino
        if ($LASTEXITCODE -ne 0) { Errore 'Non riesco a fare la copia di sicurezza dei dati di adesso: ripristino annullato, non è cambiato niente.' }
    }
    else { Write-Host 'Nel .env non c''è la password dei backup: niente copia di sicurezza dei dati di adesso.' }
    # Il primo tentativo usa la password del .env: niente variabile d'ambiente che la copra.
    Remove-Item Env:WALLETTRACKER_BACKUP_PASSWORD -ErrorAction SilentlyContinue

    # 2. Fermi il backend e il backup automatico.
    & docker compose stop backend backup | Out-Null
    function Riparti {
        & docker compose start backend backup | Out-Null
        Aspetta-Salute 'backend' 240
    }

    # 3. Il ripristino; con "password sbagliata o mancante" (codici 3 e 4) la chiedo, fino a tre volte.
    $tentativi = 0
    $passwordInserita = ''
    while ($true) {
        & docker compose run --rm -T backup ripristina $nomeBackup
        $codice = $LASTEXITCODE
        if ($codice -eq 0) { break }
        if (($codice -ne 3 -and $codice -ne 4) -or $tentativi -ge 3) {
            Riparti
            Errore 'Il ripristino non è riuscito (leggi il messaggio qui sopra). Se si è fermato prima di "Svuoto il database", non è cambiato niente.'
        }
        $tentativi++
        $passwordInserita = Leggi-Segreto 'Password di QUESTO backup (Invio vuoto per annullare)'
        if ($passwordInserita -eq '') { Riparti; Errore 'Ripristino annullato: non è cambiato niente.' }
        # Docker Compose preferisce le variabili dell'ambiente a quelle del .env.
        $env:WALLETTRACKER_BACKUP_PASSWORD = $passwordInserita
    }
    Remove-Item Env:WALLETTRACKER_BACKUP_PASSWORD -ErrorAction SilentlyContinue

    # Un computer nuovo, senza password dei backup nel .env: tengo quella appena usata.
    if ($PasswordBackup -eq '' -and (Password-Backup-Valida $passwordInserita)) {
        Scrivi-Env 'WALLETTRACKER_BACKUP_PASSWORD' $passwordInserita
        Write-Host 'Ho salvato nel .env la password del backup: da stanotte parte il backup automatico.'
    }

    # 4. Si riparte: il backend legge la chiave rimessa a posto, e Flyway porta il database alla versione dell'app.
    Riparti
    Write-Host 'Ripristino completato: l''app ha i dati del backup. (Il PIN è quello di allora, come tutto il resto.)'
}

# --- 7. Fatto ----------------------------------------------------------------------------------------------
$porta = Valore-Env 'WEB_PORT'
if ($porta -eq '') { $porta = '8000' }
$indirizzo = "http://localhost:$porta"
Passo "WalletTracker $($env:WALLETTRACKER_VERSION) è avviato: $indirizzo"
if ($Tailscale) {
    Write-Host "Dai dispositivi remoti, con Tailscale:  https://$NomeTs   (lì serve il PIN: crealo prima da qui, dal server)"
    Write-Host "Per collegare una banca da un dispositivo remoto, registra su Enable Banking anche:  https://$NomeTs/callback"
}
if ((Valore-Env 'WALLETTRACKER_BACKUP_PASSWORD') -eq '') {
    Write-Host 'Backup automatico SPENTO: per accenderlo, e fare il primo backup, rilancia lo script con -Backup (docs/48).'
}
Write-Host 'Per fermarlo:  docker compose down   (i dati restano)'

# L'avvio automatico (docs/50, parte P): come in installa.sh, lo controllo soltanto. Docker Desktop scrive le sue
# impostazioni in %APPDATA%\Docker (settings-store.json nelle versioni nuove, settings.json in quelle vecchie).
$avvioAutomatico = ''
foreach ($nome in 'settings-store.json', 'settings.json') {
    $file = Join-Path $env:APPDATA "Docker\$nome"
    if ($avvioAutomatico -eq '' -and (Test-Path $file)) {
        $testo = [System.IO.File]::ReadAllText($file)
        if ($testo -match '"autostart"\s*:\s*true') { $avvioAutomatico = 'si' }
        elseif ($testo -match '"autostart"\s*:\s*false') { $avvioAutomatico = 'no' }
    }
}
if ($avvioAutomatico -eq 'si') {
    Write-Host 'All''accensione del computer WalletTracker riparte da solo (parte Docker Desktop, e i container con lui).'
}
elseif ($avvioAutomatico -eq 'no') {
    Write-Host 'AVVIO AUTOMATICO SPENTO: all''accensione del computer Docker Desktop non parte da solo, e quindi nemmeno WalletTracker.' -ForegroundColor Yellow
    Write-Host '  Per accenderlo: Docker Desktop -> Settings (l''ingranaggio) -> General -> "Start Docker Desktop when you sign in'
    Write-Host '  to your computer" -> Apply (docs/50, parte P).'
}
else {
    Write-Host 'Per farlo ripartire da solo all''accensione: Docker Desktop -> Settings -> General -> "Start Docker Desktop when you sign in" (docs/50, parte P).'
}
if (-not $SenzaBrowser) { Start-Process $indirizzo }
