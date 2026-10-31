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

# --- 1. Docker ---------------------------------------------------------------------------------------------
Passo 'Controllo Docker'
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Errore 'Docker non è installato: installa Docker Desktop (docs/45, parte B).'
}
& docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Errore 'Docker non risponde: avvia Docker Desktop, aspetta che dica "Engine running" e riprova.'
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

# --- 3. Le cartelle ----------------------------------------------------------------------------------------
# La cartella dei backup: di serie backup/ nel progetto; BACKUP_DIR nel .env per un'altra (docs/48).
$CartellaBackup = Valore-Env 'BACKUP_DIR'
if ($CartellaBackup -eq '') { $CartellaBackup = './backup' }
New-Item -ItemType Directory -Force -Path '.data', 'secrets', $CartellaBackup | Out-Null
$CartellaBackup = (Resolve-Path $CartellaBackup).Path

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
    Scrivi-Env $nome "$attuale,$nuovo"
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
    if (Aggiungi-A-Elenco 'ENABLE_BANKING_REDIRECT_URL' "https://$NomeTs/callback" 'https://localhost:8443/callback') {
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
if ($Backup -and $PasswordBackup -eq '') {
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
Passo 'Avvio di WalletTracker (la prima volta ci vuole qualche minuto: si scaricano e si compilano le immagini)'
if ($Ia) { & docker compose --profile ia up -d --build }
else { & docker compose up -d --build }
if ($LASTEXITCODE -ne 0) { Errore 'docker compose non è riuscito ad avviare l''app: leggi il messaggio qui sopra.' }

# Dopo aver azzerato il PIN, il backend si riavvia: le sessioni aperte (anche su dispositivi persi) si chiudono.
if ($ReimpostaPin) { & docker compose restart backend | Out-Null }

Passo 'Aspetto che il backend sia pronto'
Aspetta-Salute 'backend' 240
Write-Host 'Il backend è pronto.'

# --- 6. Backup e ripristino (solo con -Backup o -Ripristina) ----------------------------------------------
# Il lavoro lo fa il container "backup" (docker/backup/wallettracker-backup.sh): vedi i commenti in installa.sh.
if ($Backup) {
    Passo 'Backup'
    & docker compose run --rm -T backup crea manuale
    if ($LASTEXITCODE -ne 0) { Errore 'Il backup non è riuscito: leggi il messaggio qui sopra.' }
    Write-Host "Il backup è nella cartella $CartellaBackup. Copialo anche FUORI da questo computer (docs/48, parte F)."
}

if ($Ripristina -ne '') {
    Passo "Ripristino da $Ripristina"
    if (-not (Test-Path $Ripristina -PathType Leaf)) { Errore "Non trovo il file $Ripristina." }
    # Il container vede solo la cartella dei backup: se il file è altrove, lo copio lì (con lo stesso nome).
    $file = (Resolve-Path $Ripristina).Path
    $nomeBackup = Split-Path $file -Leaf
    $destinazione = Join-Path $CartellaBackup $nomeBackup
    if ($file -ne $destinazione) {
        if ((Test-Path $destinazione) -and ((Get-FileHash $file).Hash -ne (Get-FileHash $destinazione).Hash)) {
            Errore "Nella cartella dei backup c'è già un file diverso di nome $nomeBackup`: rinomina quello da ripristinare."
        }
        Copy-Item $file $destinazione -Force
        Write-Host "Copiato $nomeBackup nella cartella dei backup ($CartellaBackup)."
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
Passo "WalletTracker è avviato: $indirizzo"
if ($Tailscale) {
    Write-Host "Dai dispositivi remoti, con Tailscale:  https://$NomeTs   (lì serve il PIN: crealo prima da qui, dal server)"
    Write-Host "Per collegare una banca da un dispositivo remoto, registra su Enable Banking anche:  https://$NomeTs/callback"
}
if ((Valore-Env 'WALLETTRACKER_BACKUP_PASSWORD') -eq '') {
    Write-Host 'Backup automatico SPENTO: per accenderlo, e fare il primo backup, rilancia lo script con -Backup (docs/48).'
}
Write-Host 'Per fermarlo:  docker compose down   (i dati restano)'
if (-not $SenzaBrowser) { Start-Process $indirizzo }
