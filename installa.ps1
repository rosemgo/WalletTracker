# Installa e avvia WalletTracker su Windows (Fase 4, passo 2: docs/45).
# Su macOS e Linux c'è lo stesso script per il terminale: installa.sh.
#
# Si lancia da PowerShell, nella cartella del progetto:
#   powershell -ExecutionPolicy Bypass -File installa.ps1            primo avvio, e tutte le volte dopo
#   powershell -ExecutionPolicy Bypass -File installa.ps1 -Aiuto     l'elenco delle opzioni (in inglese: -Help; i due
#                                                                    nomi di ogni opzione sono nel blocco "param")
#
# "-ExecutionPolicy Bypass": Windows, di serie, non esegue gli script scaricati. Così il permesso vale solo per
# questo comando, senza cambiare le impostazioni del PC.
#
# Si può rilanciare quante volte si vuole: quello che c'è già (il .env, la chiave, i dati) non viene toccato.
# Fa le stesse cose di installa.sh, nello stesso ordine: vedi i commenti lì per i dettagli.
#
# Le lingue (docs/lingue-3-installazione.md): lo script parla italiano o inglese. Ogni frase è scritta due volte, una
# accanto all'altra, prima in italiano e poi in inglese: chi ne cambia una vede subito l'altra da cambiare.

# Ogni opzione ha due nomi, quello italiano e quello inglese (Alias): valgono tutti e due, in qualsiasi lingua. I file
# da doppio clic del pacchetto italiano e l'aggiornamento usano quelli italiani.
param(
    [Alias('Ai')][switch]$Ia,
    [Alias('ChangeDbPassword')][switch]$CambiaPasswordDb,
    [Alias('ResetPin')][switch]$ReimpostaPin,
    [switch]$Tailscale,
    [switch]$Backup,
    [Alias('Restore')][string]$Ripristina = '',
    [Alias('Update')][switch]$Aggiorna,
    [Alias('BackupFolder')][switch]$CartellaBackup,
    [Alias('NoBrowser')][switch]$SenzaBrowser,
    [Alias('Language')][string]$Lingua = '',
    [Alias('Help')][switch]$Aiuto
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

# Il valore di una variabile in un file di righe NOME=valore (l'ultima riga NOME=..., come fa Docker Compose); vuoto
# se manca, o se manca il file.
function Valore-In([string]$file, [string]$nome) {
    $valore = ''
    if (-not (Test-Path $file)) { return $valore }
    foreach ($riga in ([System.IO.File]::ReadAllText($file, $Utf8) -split "`r?`n")) {
        if ($riga.StartsWith("$nome=")) { $valore = $riga.Substring($nome.Length + 1) }
    }
    return $valore
}

# --- La lingua dello script ---------------------------------------------------------------------------------
# Come in installa.sh (vedi i commenti lì): WALLETTRACKER_LANGUAGE nel .env (it o en); se il .env c'è ma la riga manca
# è un'installazione di prima delle lingue, quindi italiano; se il .env non c'è ancora (la prima installazione) lo
# script la CHIEDE, una volta sola, prima di ogni altra cosa (Chiedi-Lingua, più sotto), e da lì in poi parla solo
# quella. -Lingua it|en la sceglie senza domanda, o la cambia dopo. La lingua dell'APP è un'altra cosa
# (Impostazioni -> Lingua): in un'installazione nuova parte da questa.
$LinguaScelta = $Lingua.Trim().ToLower()
$Lingua = (Valore-In $EnvFile 'WALLETTRACKER_LANGUAGE').Trim()
if ($Lingua -ne 'it' -and $Lingua -ne 'en') { $Lingua = 'it' }

# T ITALIANO INGLESE: la frase nella lingua dello script.
function T([string]$it, [string]$en) { if ($Lingua -eq 'en') { return $en } return $it }
# Dici: una riga (con un colore, se serve); Passo: il titolo di una fase; Errore: il motivo, e lo script si ferma.
function Dici([string]$it, [string]$en, [string]$colore = '') {
    if ($colore -ne '') { Write-Host (T $it $en) -ForegroundColor $colore } else { Write-Host (T $it $en) }
}
function Passo([string]$it, [string]$en) { Write-Host ''; Write-Host "==> $(T $it $en)" -ForegroundColor Cyan }
function Errore([string]$it, [string]$en) {
    Write-Host ''
    Write-Host "$(T 'ERRORE' 'ERROR'): $(T $it $en)" -ForegroundColor Red
    exit 1
}

if ($LinguaScelta -ne '') {
    if ($LinguaScelta -ne 'it' -and $LinguaScelta -ne 'en') {
        Errore '-Lingua vuole it (italiano) oppure en (inglese), per esempio:  installa.ps1 -Lingua en' '-Language needs it (Italian) or en (English), for example:  installa.ps1 -Language it'
    }
    $Lingua = $LinguaScelta
}

if ($Aiuto) {
    # Prima dell'installazione non c'è ancora una lingua: l'aiuto è in quella di Windows (italiano, o inglese).
    if (-not (Test-Path $EnvFile) -and $LinguaScelta -eq '' -and (Get-UICulture).TwoLetterISOLanguageName -ne 'it') { $Lingua = 'en' }
    if ($Lingua -eq 'en') {
        Write-Host @'
Installs and starts WalletTracker on Windows. On macOS and Linux: installa.sh.
Run it from PowerShell, in the WalletTracker folder:

  powershell -ExecutionPolicy Bypass -File installa.ps1                      first start, and every time after
  powershell -ExecutionPolicy Bypass -File installa.ps1 -Ai                  with Ollama too (the local AI)
  powershell -ExecutionPolicy Bypass -File installa.ps1 -ChangeDbPassword    new password for the database
  powershell -ExecutionPolicy Bypass -File installa.ps1 -ResetPin            PIN forgotten: resets it
  powershell -ExecutionPolicy Bypass -File installa.ps1 -Tailscale           the app on your devices
  powershell -ExecutionPolicy Bypass -File installa.ps1 -Backup              a backup now
  powershell -ExecutionPolicy Bypass -File installa.ps1 -Restore FILE        REPLACES the data with the data of the backup
  powershell -ExecutionPolicy Bypass -File installa.ps1 -BackupFolder        chooses (or changes) the backup folder
  powershell -ExecutionPolicy Bypass -File installa.ps1 -Update              the latest version, with a backup first
  powershell -ExecutionPolicy Bypass -File installa.ps1 -Language it         the language of this script: it or en
  powershell -ExecutionPolicy Bypass -File installa.ps1 -NoBrowser           does not open the browser at the end

You can run it as many times as you like: what is already there (the .env, the key, the data) is not touched.
The Italian names of the options work too (-Lingua, -Aiuto...).
'@
    }
    else {
        Write-Host @'
Installa e avvia WalletTracker su Windows. Su macOS e Linux: installa.sh.
Si lancia da PowerShell, nella cartella di WalletTracker:

  powershell -ExecutionPolicy Bypass -File installa.ps1                      primo avvio, e tutte le volte dopo
  powershell -ExecutionPolicy Bypass -File installa.ps1 -Ia                  con anche Ollama (l'IA locale)
  powershell -ExecutionPolicy Bypass -File installa.ps1 -CambiaPasswordDb    password nuova per il database
  powershell -ExecutionPolicy Bypass -File installa.ps1 -ReimpostaPin        PIN dimenticato: lo azzera
  powershell -ExecutionPolicy Bypass -File installa.ps1 -Tailscale           l'app sui tuoi dispositivi (docs/47)
  powershell -ExecutionPolicy Bypass -File installa.ps1 -Backup              un backup adesso (docs/48)
  powershell -ExecutionPolicy Bypass -File installa.ps1 -Ripristina FILE     SOSTITUISCE i dati con quelli del backup
  powershell -ExecutionPolicy Bypass -File installa.ps1 -CartellaBackup      sceglie (o cambia) la cartella dei backup
  powershell -ExecutionPolicy Bypass -File installa.ps1 -Aggiorna            l'ultima versione, con un backup prima (docs/50)
  powershell -ExecutionPolicy Bypass -File installa.ps1 -Lingua en           la lingua di questo script: it oppure en
  powershell -ExecutionPolicy Bypass -File installa.ps1 -SenzaBrowser        non apre il browser alla fine

Si può rilanciare quante volte si vuole: quello che c'è già (il .env, la chiave, i dati) non viene toccato.
Valgono anche i nomi inglesi delle opzioni (-Language, -Help...).
'@
    }
    exit 0
}

# La domanda sulla lingua: solo alla prima installazione (il .env non c'è ancora), solo se non è già stata scelta con
# -Lingua, e solo da una finestra vera. È l'unico punto in cui lo script scrive nelle due lingue insieme: non sa
# ancora quale capisci. Invio da solo sceglie quella proposta, che è quella di Windows.
function Chiedi-Lingua {
    $proposta = '2'
    if ((Get-UICulture).TwoLetterISOLanguageName -eq 'it') { $proposta = '1' }
    Write-Host ''
    Write-Host 'Lingua / Language'
    Write-Host '  1) Italiano'
    Write-Host '  2) English'
    while ($true) {
        $risposta = ([string](Read-Host "Scrivi 1 o 2 e premi Invio / Type 1 or 2 and press Enter [$proposta]")).Trim().ToLower()
        if ($risposta -eq '') { $risposta = $proposta }
        if ('1', 'i', 'it', 'italiano' -contains $risposta) { return 'it' }
        if ('2', 'e', 'en', 'english', 'inglese' -contains $risposta) { return 'en' }
    }
}
if (-not (Test-Path $EnvFile) -and $LinguaScelta -eq '' -and -not [Console]::IsInputRedirected) {
    $Lingua = Chiedi-Lingua
    $LinguaScelta = $Lingua
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

# Il valore di una variabile del .env; vuoto se manca.
function Valore-Env([string]$nome) { return Valore-In $EnvFile $nome }

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
    Errore "$servizio non è pronto dopo $massimo secondi (stato: $stato). Guarda i log con:  docker compose logs $servizio" "$servizio is not ready after $massimo seconds (state: $stato). Look at the logs with:  docker compose logs $servizio"
}

# --- 0. Da dove arrivano le immagini (docs/50, parte C) ----------------------------------------------------
# Come in installa.sh: con il codice sorgente (backend/pom.xml) le immagini si costruiscono dal codice, versione
# "sviluppo"; in una versione scaricata (il file VERSION) si scaricano già pronte. $env:WALLETTRACKER_VERSION la legge
# docker-compose.yml, nel nome delle immagini.
$DalCodice = Test-Path 'backend/pom.xml'
if ($DalCodice) { $env:WALLETTRACKER_VERSION = 'sviluppo' }
else {
    if (-not (Test-Path 'VERSION')) {
        Errore 'Manca il file VERSION: scarica WalletTracker dalla pagina delle versioni su GitHub (docs/50) e lancia lo script dalla sua cartella.' 'The VERSION file is missing: download WalletTracker from the releases page on GitHub and run the script from its folder.'
    }
    $env:WALLETTRACKER_VERSION = ([System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'VERSION'))).Trim()
}

if ($Aggiorna) {
    # Dal codice si aggiorna con Git, non con le versioni pubblicate: lo script non tocca il tuo lavoro.
    if ($DalCodice) {
        Dici 'Questa è la cartella del CODICE SORGENTE (c''è backend/pom.xml): -Aggiorna serve alle versioni scaricate.' 'This is the SOURCE CODE folder (there is backend/pom.xml): -Update is for the downloaded versions.'
        Dici 'Per avere le novità dal codice:  git switch main   poi   git pull   e rilancia lo script.' 'To get what is new from the code:  git switch main   then   git pull   and run the script again.'
        exit 0
    }
    if ($Backup -or $Ripristina -ne '' -or $CambiaPasswordDb -or $ReimpostaPin -or $Tailscale) {
        Errore '-Aggiorna va lanciato da solo (al massimo con -Ia): le altre operazioni falle prima o dopo.' '-Update must be run on its own (at most with -Ai): do the other operations before or after.'
    }
}

# --- 1. Docker ---------------------------------------------------------------------------------------------
Passo 'Controllo Docker' 'Checking Docker'
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Errore 'Docker non è installato: installa Docker Desktop (docs/45, parte B).' 'Docker is not installed: install Docker Desktop (the guide, chapter 2).'
}
& docker info *> $null
if ($LASTEXITCODE -ne 0) {
    # Docker Desktop spento (docs/50, parte O): lo accendo io e aspetto che risponda, al massimo 3 minuti.
    $dockerDesktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (-not (Test-Path $dockerDesktop)) {
        Errore 'Docker non risponde, e non trovo Docker Desktop: avvialo tu (o installalo: docs/45, parte B), aspetta che dica "Engine running" e riprova.' 'Docker does not answer, and I cannot find Docker Desktop: start it yourself (or install it: the guide, chapter 2), wait until it says "Engine running" and try again.'
    }
    Dici 'Docker Desktop era spento: lo sto accendendo (al massimo 3 minuti)...' 'Docker Desktop was off: I am starting it (3 minutes at most)...'
    Start-Process -FilePath $dockerDesktop
    $secondi = 0
    while ($true) {
        & docker info *> $null
        if ($LASTEXITCODE -eq 0) { break }
        if ($secondi -ge 180) { Errore 'Docker Desktop non risponde dopo 3 minuti: aprilo tu, aspetta che dica "Engine running" e riprova.' 'Docker Desktop does not answer after 3 minutes: open it yourself, wait until it says "Engine running" and try again.' }
        Start-Sleep -Seconds 3
        $secondi += 3
    }
}
& docker compose version *> $null
if ($LASTEXITCODE -ne 0) { Errore 'Manca Docker Compose v2 (il comando "docker compose"): aggiorna Docker Desktop.' 'Docker Compose v2 is missing (the "docker compose" command): update Docker Desktop.' }
Dici 'Docker è avviato.' 'Docker is running.'

# --- 2. Il file .env ---------------------------------------------------------------------------------------
Passo 'Configurazione (.env)' 'Configuration (.env)'
$EnvEsisteva = Test-Path $EnvFile
if (-not $EnvEsisteva) {
    if (-not (Test-Path '.env.example')) {
        Errore 'Manca .env.example: lo script va lanciato dalla cartella del progetto scaricato.' '.env.example is missing: the script must be run from the folder of the downloaded project.'
    }
    # Il .env manca, ma il database di un'installazione precedente c'è ancora: ha la SUA password, scritta nel
    # vecchio .env. Con una password nuova il backend non entrerebbe (installa.sh spiega lo stesso).
    & docker volume inspect wallettracker_db-data *> $null
    if ($LASTEXITCODE -eq 0) {
        Errore 'Manca il file .env, ma c''è già il database di un''installazione precedente, con la sua password. Rimetti qui il vecchio .env (dal backup) e rilancia lo script. Se invece vuoi ripartire da zero, CANCELLANDO tutti i dati di prima, i comandi sono in docs/45, parte M.' 'The .env file is missing, but the database of a previous installation is already there, with its own password. Put the old .env back here (from your copy) and run the script again. If you want to start from scratch instead, DELETING all the previous data, the commands are in docs/45, part M.'
    }
    Copy-Item '.env.example' $EnvFile
    Scrivi-Env 'POSTGRES_PASSWORD' (Nuova-Password)
    Dici 'Creato il file .env, con una password del database generata a caso.' 'Created the .env file, with a randomly generated database password.'
}
else {
    Dici 'Il file .env c''è già: lo uso così com''è.' 'The .env file is already there: I use it as it is.'
    $attuale = Valore-Env 'POSTGRES_PASSWORD'
    if ($attuale -eq '') {
        # Un .env copiato a mano, con la password vuota: senza password Compose non avrebbe mai avviato il
        # database, quindi non esiste ancora un database con un'altra password.
        Scrivi-Env 'POSTGRES_PASSWORD' (Nuova-Password)
        Dici 'Mancava la password del database: ne ho generata una a caso.' 'The database password was missing: I generated a random one.'
    }
    elseif (($attuale -eq 'cambia-questa-password' -or $attuale.Length -lt 16) -and -not $CambiaPasswordDb) {
        Dici 'ATTENZIONE: la password del database è quella d''esempio, o è più corta di 16 caratteri.' 'WARNING: the database password is the example one, or is shorter than 16 characters.' Yellow
        Dici '            Per sostituirla con una generata a caso, rilancia lo script con -CambiaPasswordDb' '         To replace it with a randomly generated one, run the script again with -ChangeDbPassword' Yellow
    }
}

# La lingua scelta (con la domanda o con -Lingua) si scrive nel .env, ora che c'è di sicuro (installa.sh, stesso
# punto). L'app già in uso non cambia da qui: lo si dice a chi cambia lingua dopo, con -Lingua.
if ($LinguaScelta -ne '' -and (Valore-Env 'WALLETTRACKER_LANGUAGE') -ne $LinguaScelta) {
    Scrivi-Env 'WALLETTRACKER_LANGUAGE' $LinguaScelta
    if ($EnvEsisteva) {
        Dici 'Da adesso questo script parla italiano (WALLETTRACKER_LANGUAGE nel .env). La lingua dell''app si cambia dall''app: Impostazioni -> Lingua.' 'From now on this script speaks English (WALLETTRACKER_LANGUAGE in the .env). The language of the app is changed from the app: Settings -> Language.'
    }
}

# --- 2b. La cartella dei backup (una volta sola, o con -CartellaBackup: docs/48, parte F) -------------------
# Come in installa.sh (vedi i commenti lì): la si sceglie qui, senza aprire il .env.
function Chiedi-Cartella-Backup {
    $attuale = Valore-Env 'BACKUP_DIR'
    Dici 'Dove salvo i backup? Meglio fuori da questo disco: un disco esterno, o la cartella di Google Drive, OneDrive o' 'Where do I save the backups? Better outside this disk: an external disk, or the Google Drive, OneDrive or'
    Dici 'Dropbox sul computer (con Google Drive per desktop: "Esegui il mirroring dei file"). I backup sono cifrati.' 'Dropbox folder on the computer (with Google Drive for desktop: "Mirror files"). The backups are encrypted.'
    if ($attuale -eq '') { Dici 'Adesso: la cartella "backup" qui accanto.' 'Now: the "backup" folder next to this script.' } else { Dici "Adesso: $attuale." "Now: $attuale." }
    Dici 'Potrai cambiarla quando vuoi con -CartellaBackup.' 'You can change it whenever you like with -BackupFolder.'
    $risposta = Read-Host (T "Scrivi (o incolla) il percorso di una cartella, oppure premi Invio per lasciarla com'è" 'Type (or paste) the path of a folder, or press Enter to leave it as it is')
    # Gli spazi e le virgolette che Esplora file aggiunge con "Copia come percorso".
    $risposta = ([string]$risposta).Trim().Trim('"', "'").Trim()
    if ($risposta -eq '') {
        # Scritta anche quella di serie: così la domanda non torna a ogni avvio.
        if ($attuale -eq '') { Scrivi-Env 'BACKUP_DIR' './backup' }
        if ($attuale -eq '') { Dici 'La cartella dei backup resta backup, qui accanto.' 'The backup folder stays backup, next to this script.' } else { Dici "La cartella dei backup resta $attuale." "The backup folder stays $attuale." }
        return
    }
    if ($risposta -match '[$#`]') { Errore 'Il percorso non può contenere $, # o `: scegli un''altra cartella.' 'The path cannot contain $, # or `: choose another folder.' }
    try { New-Item -ItemType Directory -Force -Path $risposta -ErrorAction Stop | Out-Null }
    catch { Errore "Non riesco a creare la cartella $risposta`: controlla il percorso, o che il disco sia collegato." "I cannot create the folder $risposta`: check the path, or that the disk is connected." }
    # Nel .env le barre sono "/": Docker Compose le capisce anche su Windows (es. D:/Backup/WalletTracker).
    $percorso = (Resolve-Path $risposta).Path.Replace('\', '/')
    Scrivi-Env 'BACKUP_DIR' $percorso
    Dici "I backup andranno in $percorso (BACKUP_DIR nel .env). Quelli vecchi restano dove sono: spostali tu, se vuoi." "The backups will go to $percorso (BACKUP_DIR in the .env). The old ones stay where they are: move them yourself, if you like."
}

if ($CartellaBackup) {
    Passo 'La cartella dei backup' 'The backup folder'
    Chiedi-Cartella-Backup
}
elseif ((Valore-Env 'BACKUP_DIR') -eq '' -and $Ripristina -eq '' -and -not [Console]::IsInputRedirected) {
    # Nel .env non c'è ancora BACKUP_DIR: si chiede una volta, a qualunque avvio (anche il doppio clic su "Avvia"),
    # e solo da una finestra vera (installa.sh, parte 2b).
    Passo 'La cartella dei backup' 'The backup folder'
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
    Passo 'Cambio della password del database' 'Changing the database password'
    $utente = Valore-Env 'POSTGRES_USER'
    $database = Valore-Env 'POSTGRES_DB'
    if ($utente -eq '' -or $database -eq '') { Errore 'Nel .env mancano POSTGRES_USER o POSTGRES_DB.' 'POSTGRES_USER or POSTGRES_DB is missing in the .env.' }
    # Prima nel database (la "serratura"), poi nel .env (la "chiave"): docs/44, parte E2, e docs/45, parte E.
    & docker compose up -d db | Out-Null
    Aspetta-Salute 'db' 60
    $nuova = Nuova-Password
    & docker compose exec -T db psql -q -v ON_ERROR_STOP=1 -U $utente -d $database -c "ALTER USER `"$utente`" PASSWORD '$nuova'"
    if ($LASTEXITCODE -ne 0) { Errore 'Il database non ha accettato la password nuova: niente è cambiato.' 'The database did not accept the new password: nothing has changed.' }
    Scrivi-Env 'POSTGRES_PASSWORD' $nuova
    Dici 'Password cambiata nel database e salvata nel .env. Il backend la riceve al riavvio, qui sotto.' 'Password changed in the database and saved in the .env. The backend receives it at the restart, below.'
}

# --- 4b. Azzerare il PIN (solo con -ReimpostaPin) ---------------------------------------------------------
# Il PIN dimenticato, e perso anche il codice di recupero: il PIN non si legge (nel database c'è solo il suo hash,
# docs/46), si cancella. Alla prossima apertura dal PC l'app chiede di nuovo: con un PIN o senza. Si fa solo da qui, dal PC.
if ($ReimpostaPin) {
    Passo 'Azzero il PIN' 'Resetting the PIN'
    $utente = Valore-Env 'POSTGRES_USER'
    $database = Valore-Env 'POSTGRES_DB'
    & docker compose up -d db | Out-Null
    Aspetta-Salute 'db' 60
    & docker compose exec -T db psql -q -v ON_ERROR_STOP=1 -U $utente -d $database -c 'DELETE FROM app_pin'
    if ($LASTEXITCODE -ne 0) { Errore 'Non sono riuscito ad azzerare il PIN (l''app è già stata avviata almeno una volta?).' 'I could not reset the PIN (has the app been started at least once?).' }
    Dici 'PIN azzerato. Apri l''app da qui, dal server (http://localhost:8000): sceglierai di nuovo, con un PIN nuovo o senza.' 'PIN reset. Open the app from here, from the server (http://localhost:8000): you will choose again, with a new PIN or without.'
}

# --- 4c. Tailscale (solo con -Tailscale) -------------------------------------------------------------------
# Le stesse tre cose di installa.sh (vedi i commenti lì): tailscale serve verso la porta "remota" di nginx, il nome
# del PC tra i nomi consentiti, l'indirizzo di ritorno di Enable Banking per quel nome (docs/47).

# Mette il nome Tailscale di QUESTO PC in una variabile del .env fatta di valori separati da virgole, e toglie gli
# altri nomi *.ts.net. Quelli vecchi (un PC sostituito, un dispositivo rinominato) non rispondono più, ma restando
# prima nell'elenco verrebbero scelti come indirizzo di ritorno di Enable Banking, e il collegamento finirebbe in una
# pagina di errore. Gli altri valori (localhost, https://localhost:8443/callback...) restano com'erano.
# Restituisce Aggiunta ($true se il nome è stato aggiunto) e Tolte (i valori tolti).
function Metti-Ts-In-Elenco([string]$nome, [string]$nuovo, [string]$predefinito) {
    $attuale = Valore-Env $nome
    if ($attuale -eq '') { $attuale = $predefinito }
    $voci = @($attuale -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
    # Un nome Tailscale, da solo (nomi consentiti) o dentro un indirizzo (https://nome.ts.net/callback).
    $tolte = @($voci | Where-Object { $_ -ne $nuovo -and $_ -match '\.ts\.net(:\d+)?(/|$)' })
    $restano = @($voci | Where-Object { $tolte -notcontains $_ })
    $aggiunta = $restano -notcontains $nuovo
    if ($aggiunta) { $restano += $nuovo }
    if ($aggiunta -or $tolte.Count -gt 0) { Scrivi-Env $nome ($restano -join ',') }
    return [pscustomobject]@{ Aggiunta = $aggiunta; Tolte = $tolte }
}

$NomeTs = ''
if ($Tailscale) {
    Passo 'Tailscale' 'Tailscale'
    # Il programma di installazione di Tailscale lo mette nel PATH; se no, lo cerchiamo dove si installa di solito.
    $TsCmd = (Get-Command tailscale -ErrorAction SilentlyContinue).Source
    if (-not $TsCmd) {
        $predefinito = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
        if (Test-Path $predefinito) { $TsCmd = $predefinito }
        else { Errore 'Non trovo Tailscale: installalo e accedi con il tuo account (docs/47, parte C), poi rilancia.' 'I cannot find Tailscale: install it and sign in with your account (the guide, chapter 6), then run this again.' }
    }

    # tailscale status --json: lo stato di Tailscale in JSON. ConvertFrom-Json lo trasforma in un oggetto:
    # BackendState vale "Running" quando è collegato; Self.DNSName è il nome di questo PC, con un punto finale.
    $testo = (& $TsCmd status --json 2>$null) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $testo -eq '') { Errore 'Tailscale non risponde: aprilo e accedi con il tuo account (docs/47, parte C).' 'Tailscale does not answer: open it and sign in with your account (the guide, chapter 6).' }
    $stato = $testo | ConvertFrom-Json
    if ($stato.BackendState -ne 'Running') { Errore 'Tailscale è installato ma non collegato: aprilo, accedi con il tuo account e rilancia.' 'Tailscale is installed but not connected: open it, sign in with your account and run this again.' }
    $NomeTs = ([string]$stato.Self.DNSName).TrimEnd('.')
    if (-not $NomeTs.EndsWith('.ts.net')) {
        Errore 'Non trovo il nome del server nella rete Tailscale: accendi MagicDNS nella console di Tailscale (docs/47, parte E1) e rilancia.' 'I cannot find the name of the server in the Tailscale network: turn on MagicDNS in the Tailscale console (docs/47, part E1) and run this again.'
    }
    Dici "Il nome di questo server nella rete Tailscale è: $NomeTs" "The name of this server in the Tailscale network is: $NomeTs"

    # Funnel pubblica su Internet: per WalletTracker mai (docs/47, parte F5).
    $serve = (& $TsCmd serve status --json 2>$null) -join "`n"
    if ($serve -match '"AllowFunnel"\s*:\s*\{[^}]*:\s*true') {
        Errore "Tailscale Funnel è acceso: renderebbe l'app raggiungibile da Internet. Spegnilo con:  tailscale funnel --https=443 off   e rilancia." 'Tailscale Funnel is on: it would make the app reachable from the Internet. Turn it off with:  tailscale funnel --https=443 off   and run this again.'
    }

    $portaRemota = Valore-Env 'REMOTE_PORT'
    if ($portaRemota -eq '') { $portaRemota = '8001' }
    Dici "Chiedo a Tailscale di ricevere i tuoi dispositivi su https://$NomeTs e di passarli a 127.0.0.1:$portaRemota." "I ask Tailscale to receive your devices on https://$NomeTs and to pass them to 127.0.0.1:$portaRemota."
    Dici '(Se è la prima volta, Tailscale può chiederti di attivare HTTPS per la tua rete: apri il link che scrive,' '(If it is the first time, Tailscale may ask you to enable HTTPS for your network: open the link it prints,'
    Dici ' conferma, e lo script prosegue da solo.)' ' confirm, and the script goes on by itself.)'
    & $TsCmd serve --bg --https=443 "http://127.0.0.1:$portaRemota"
    if ($LASTEXITCODE -ne 0) { Errore 'tailscale serve non è riuscito: leggi il messaggio qui sopra (docs/47, parte P).' 'tailscale serve failed: read the message above (docs/47, part P).' }

    $esito = Metti-Ts-In-Elenco 'WALLETTRACKER_ALLOWED_HOSTS' $NomeTs 'localhost,127.0.0.1'
    if ($esito.Aggiunta) {
        Dici "Aggiunto $NomeTs ai nomi consentiti dal backend (WALLETTRACKER_ALLOWED_HOSTS nel .env)." "Added $NomeTs to the names allowed by the backend (WALLETTRACKER_ALLOWED_HOSTS in the .env)."
    }
    foreach ($vecchio in $esito.Tolte) {
        Dici "Tolto $vecchio dai nomi consentiti: è un nome Tailscale che non è questo PC." "Removed $vecchio from the allowed names: it is a Tailscale name that is not this PC."
    }
    # Come in installa.sh: dal codice anche quello di ng serve (npm start), da una versione scaricata solo Tailscale.
    $ritornoDiSerie = ''
    if ($DalCodice) { $ritornoDiSerie = 'https://localhost:8443/callback' }
    $esito = Metti-Ts-In-Elenco 'ENABLE_BANKING_REDIRECT_URL' "https://$NomeTs/callback" $ritornoDiSerie
    if ($esito.Aggiunta) {
        Dici "Aggiunto https://$NomeTs/callback agli indirizzi di ritorno di Enable Banking (nel .env)." "Added https://$NomeTs/callback to the Enable Banking redirect URLs (in the .env)."
        Dici "Registralo anche nella tua applicazione su Enable Banking: lo trovi in Impostazioni." "Register it in your Enable Banking application too: you find it in Settings."
    }
    foreach ($vecchio in $esito.Tolte) {
        Dici "Tolto $vecchio dagli indirizzi di ritorno: puoi toglierlo anche da Enable Banking." "Removed $vecchio from the redirect URLs: you can remove it from Enable Banking too."
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
    Passo 'La password dei backup' 'The backup password'
    if ([Console]::IsInputRedirected) {
        Errore 'Manca la password dei backup: lancia lo script con -Backup da PowerShell (la chiede), o scrivila nel .env come WALLETTRACKER_BACKUP_PASSWORD (docs/48).' 'The backup password is missing: run the script with -Backup from PowerShell (it asks for it), or write it in the .env as WALLETTRACKER_BACKUP_PASSWORD.'
    }
    Dici 'Scegli la password dei backup. Ogni backup sarà cifrato con lei: chi trova il file senza la password non' 'Choose the backup password. Every backup will be encrypted with it: whoever finds the file without the password'
    Dici 'legge niente. Almeno 12 caratteri; niente virgolette, apostrofi, $, \, ` e #, né spazi all''inizio o alla fine.' 'reads nothing. At least 12 characters; no quotes, apostrophes, $, \, ` or #, and no spaces at the start or the end.'
    Dici 'SCRIVILA ANCHE IN UN POSTO SICURO, FUORI DA QUESTO COMPUTER (un gestore di password): senza, i backup non si aprono.' 'WRITE IT DOWN IN A SAFE PLACE TOO, OUTSIDE THIS COMPUTER (a password manager): without it, the backups cannot be opened.' Yellow
    while ($true) {
        $prima = Leggi-Segreto (T 'Password dei backup' 'Backup password')
        $seconda = Leggi-Segreto (T 'Ripetila' 'Repeat it')
        if (-not (Password-Backup-Valida $prima)) { Dici 'Non va bene: almeno 12 caratteri, e senza i caratteri qui sopra. Riprova.' 'Not good: at least 12 characters, and without the characters above. Try again.' }
        elseif ($prima -ne $seconda) { Dici 'Le due password non sono uguali. Riprova.' 'The two passwords are not the same. Try again.' }
        else { break }
    }
    Scrivi-Env 'WALLETTRACKER_BACKUP_PASSWORD' $prima
    $PasswordBackup = $prima
    Dici 'Password salvata nel .env: da stanotte parte anche il backup automatico.' 'Password saved in the .env: from tonight the automatic backup starts too.'
}

# --- 5. Avvio ----------------------------------------------------------------------------------------------
$Profilo = @()
if ($Ia) { $Profilo = @('--profile', 'ia') }
if ($DalCodice) {
    Passo 'Avvio di WalletTracker dal codice (la prima volta ci vuole qualche minuto: si compilano le immagini)' 'Starting WalletTracker from the code (the first time it takes a few minutes: the images are built)'
    & docker compose -f docker-compose.yml -f docker-compose.build.yml @Profilo up -d --build
}
else {
    Passo "Avvio di WalletTracker $($env:WALLETTRACKER_VERSION) (la prima volta si scaricano le immagini, qualche centinaio di MB)" "Starting WalletTracker $($env:WALLETTRACKER_VERSION) (the first time the images are downloaded, a few hundred MB)"
    # Senza rete, se le immagini ci sono già tutte, si va avanti con quelle (vedi installa.sh).
    & docker compose @Profilo pull
    if ($LASTEXITCODE -ne 0) {
        foreach ($immagine in 'backend', 'web', 'backup') {
            & docker image inspect "ghcr.io/rosemgo/wallettracker-$immagine`:$($env:WALLETTRACKER_VERSION)" *> $null
            if ($LASTEXITCODE -ne 0) {
                Errore "Non riesco a scaricare le immagini della versione $($env:WALLETTRACKER_VERSION) (controlla la connessione a Internet) e sul computer non ci sono ancora." "I cannot download the images of version $($env:WALLETTRACKER_VERSION) (check the Internet connection) and they are not on the computer yet."
            }
        }
        Dici 'ATTENZIONE: non ho potuto controllare le immagini su Internet; uso quelle già scaricate.' 'WARNING: I could not check the images on the Internet; I use the ones already downloaded.' Yellow
    }
    & docker compose @Profilo up -d
}
if ($LASTEXITCODE -ne 0) { Errore 'docker compose non è riuscito ad avviare l''app: leggi il messaggio qui sopra.' 'docker compose could not start the app: read the message above.' }

# Dopo aver azzerato il PIN, il backend si riavvia: le sessioni aperte (anche su dispositivi persi) si chiudono.
if ($ReimpostaPin) { & docker compose restart backend | Out-Null }

$Attesa = 240
if ($env:WALLETTRACKER_ATTESA_BACKEND) { $Attesa = [int]$env:WALLETTRACKER_ATTESA_BACKEND }
Passo 'Aspetto che il backend sia pronto' 'Waiting for the backend to be ready'
Aspetta-Salute 'backend' $Attesa
Dici 'Il backend è pronto.' 'The backend is ready.'

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
            return (T "GitHub ha rifiutato la richiesta (codice $codice): di solito è il limite di richieste senza account, che si azzera entro un'ora. Riprova più tardi." "GitHub refused the request (code $codice): usually it is the limit of requests without an account, which resets within an hour. Try again later.")
        }
        if ($codice -ge 500) {
            return (T "GitHub non risponde in questo momento (codice $codice), anche dopo 3 tentativi: di solito dura pochi minuti, riprova più tardi. Se continua, una VPN, un proxy o l'antivirus potrebbero mettersi in mezzo." "GitHub is not answering right now (code $codice), even after 3 attempts: usually it lasts a few minutes, try again later. If it goes on, a VPN, a proxy or the antivirus might be getting in the way.")
        }
        if ($codice) { return (T "GitHub ha risposto con il codice $codice." "GitHub answered with code $codice.") }
        return (T "Windows dice: $($errore.Exception.Message) Controlla la connessione a Internet (e, se c'è, il proxy o l'antivirus)." "Windows says: $($errore.Exception.Message) Check the Internet connection (and the proxy or the antivirus, if there is one).")
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
                Dici "GitHub non ha risposto (tentativo $tentativo di 3): riprovo tra 5 secondi..." "GitHub did not answer (attempt $tentativo of 3): I try again in 5 seconds..."
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

    Passo 'Cerco una versione nuova' 'Looking for a new version'
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
        Dici 'Il servizio di GitHub per le versioni non risponde: provo dalla pagina delle versioni.' 'The GitHub service for the versions does not answer: I try from the releases page.'
        try { $pagina = Con-Tentativi { Invoke-WebRequest -UseBasicParsing -TimeoutSec 30 -Uri $paginaVersioni } }
        catch {
            $secondoMotivo = Motivo-Rete $_
            Errore "Non riesco a chiedere a GitHub l'ultima versione, né dal servizio API ($primoMotivo) né dalla pagina delle versioni ($secondoMotivo). Non è cambiato niente." "I cannot ask GitHub for the latest version, neither from the API service ($primoMotivo) nor from the releases page ($secondoMotivo). Nothing has changed."
        }
        # L'indirizzo d'arrivo, dopo i rinvii: in Windows PowerShell 5.1 è in ResponseUri, in PowerShell 7 nella richiesta.
        $arrivo = ''
        if ($pagina.BaseResponse.ResponseUri) { $arrivo = [string]$pagina.BaseResponse.ResponseUri.AbsoluteUri }
        elseif ($pagina.BaseResponse.RequestMessage.RequestUri) { $arrivo = [string]$pagina.BaseResponse.RequestMessage.RequestUri.AbsoluteUri }
        $tag = ''
        if ($arrivo -match '/releases/tag/(v[0-9]+\.[0-9]+\.[0-9]+)$') { $tag = $Matches[1] }
        $dallaPagina = $true
    }
    if (-not $dallaPagina) { $tag = [string]$ultima.tag_name }
    if ($tag -notmatch '^v[0-9]+\.[0-9]+\.[0-9]+$') { Errore "GitHub ha risposto qualcosa che non capisco (versione: `"$tag`"). Non è cambiato niente." "GitHub answered something I do not understand (version: `"$tag`"). Nothing has changed." }
    $nuova = $tag.Substring(1)
    $adesso = $env:WALLETTRACKER_VERSION
    if ($adesso -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') { Errore "Il file VERSION non contiene una versione valida (`"$adesso`"). Non è cambiato niente." "The VERSION file does not contain a valid version (`"$adesso`"). Nothing has changed." }
    # [version] confronta i numeri uno per uno: 1.10.0 è più nuova di 1.9.0.
    if (-not ([version]$nuova -gt [version]$adesso)) {
        Remove-Item -Recurse -Force $cartellaNuova
        Passo "Hai già l'ultima versione: WalletTracker $adesso" "You already have the latest version: WalletTracker $adesso"
        exit 0
    }
    Dici "C'è una versione nuova: $nuova (adesso hai la $adesso)." "There is a new version: $nuova (you now have $adesso)."

    if ($dallaPagina) {
        $baseDownload = $paginaVersioni -replace '/latest$', "/download/$tag"
        $urlZip = "$baseDownload/WalletTracker-$nuova.zip"
        $urlImpronte = "$baseDownload/SHA256SUMS"
    }
    else {
        $urlZip = [string]($ultima.assets | Where-Object { $_.name -eq "WalletTracker-$nuova.zip" } | Select-Object -First 1).browser_download_url
        $urlImpronte = [string]($ultima.assets | Where-Object { $_.name -eq 'SHA256SUMS' } | Select-Object -First 1).browser_download_url
    }
    if ($urlZip -eq '' -or $urlImpronte -eq '') { Errore "La versione $nuova non ha lo ZIP o SHA256SUMS. Non è cambiato niente." "Version $nuova does not have the ZIP or SHA256SUMS. Nothing has changed." }
    if (-not $env:WALLETTRACKER_RELEASES_URL -and -not $env:WALLETTRACKER_RELEASES_PAGE) {
        $origine = "https://github.com/rosemgo/WalletTracker/releases/download/$tag/"
        if (-not $urlZip.StartsWith($origine) -or -not $urlImpronte.StartsWith($origine)) {
            Errore 'Gli indirizzi dei file non sono quelli della pagina delle versioni di WalletTracker. Non è cambiato niente.' 'The addresses of the files are not the ones of the WalletTracker releases page. Nothing has changed.'
        }
    }

    Passo "Scarico WalletTracker $nuova e controllo che sia integro" "Downloading WalletTracker $nuova and checking that it is intact"
    $zip = Join-Path $cartellaNuova "WalletTracker-$nuova.zip"
    $impronte = Join-Path $cartellaNuova 'SHA256SUMS'
    try {
        Con-Tentativi { Invoke-WebRequest -UseBasicParsing -TimeoutSec 600 -Uri $urlZip -OutFile $zip }
        Con-Tentativi { Invoke-WebRequest -UseBasicParsing -TimeoutSec 60 -Uri $urlImpronte -OutFile $impronte }
    }
    catch {
        $motivo = Motivo-Rete $_
        Errore "Lo scaricamento non è riuscito. $motivo Non è cambiato niente." "The download failed. $motivo Nothing has changed."
    }
    $attesa = ''
    foreach ($riga in [System.IO.File]::ReadAllLines($impronte)) {
        $parti = $riga -split '\s+', 2
        if ($parti.Count -eq 2 -and $parti[1].TrimStart('*') -eq "WalletTracker-$nuova.zip") { $attesa = $parti[0] }
    }
    if ($attesa -eq '' -or (Get-FileHash -Algorithm SHA256 -Path $zip).Hash -ine $attesa) {
        Errore 'Lo ZIP scaricato NON corrisponde alla sua impronta (SHA256SUMS): è rovinato o non è quello pubblicato. Non l''ho usato; non è cambiato niente.' 'The downloaded ZIP does NOT match its checksum (SHA256SUMS): it is damaged or it is not the published one. I did not use it; nothing has changed.'
    }
    Expand-Archive -Path $zip -DestinationPath $cartellaNuova -Force
    $sorgente = Join-Path $cartellaNuova "WalletTracker-$nuova"
    $versioneZip = ''
    if (Test-Path (Join-Path $sorgente 'VERSION')) { $versioneZip = ([System.IO.File]::ReadAllText((Join-Path $sorgente 'VERSION'))).Trim() }
    if (-not (Test-Path (Join-Path $sorgente 'installa.ps1')) -or -not (Test-Path (Join-Path $sorgente 'docker-compose.yml')) -or $versioneZip -ne $nuova) {
        Errore "Lo ZIP non contiene la versione $nuova come me l'aspetto. Non è cambiato niente." "The ZIP does not contain version $nuova as I expect it. Nothing has changed."
    }
    Dici 'Lo ZIP è integro.' 'The ZIP is intact.'

    Passo "Le novità dalla $adesso alla $nuova" "What is new from $adesso to $nuova"
    # Le novità: NOVITA.md in italiano, CHANGELOG.md in inglese (nello ZIP ci sono tutti e due).
    $novita = Join-Path $sorgente (T 'NOVITA.md' 'CHANGELOG.md')
    if (-not (Test-Path $novita)) { $novita = Join-Path $sorgente 'NOVITA.md' }
    $dentro = $false
    foreach ($riga in [System.IO.File]::ReadAllLines($novita, $Utf8)) {
        if ($riga -eq "## $adesso") { break }
        if ($riga.StartsWith('## ')) { $dentro = $true }
        # Nel terminale il grassetto (**) e il codice (`) di Markdown non si vedono come tali: si tolgono i segni.
        if ($dentro) { Write-Host ($riga -replace '\*\*', '' -replace '`', '') }
    }
    Write-Host ''
    # ${nuova} con le graffe: in PowerShell "?" può far parte del nome di una variabile, e "$nuova?" sarebbe vuota.
    $risposta = Read-Host (T "Aggiorno WalletTracker alla ${nuova}? Prima faccio un backup dei dati. [s/N]" "Do I update WalletTracker to ${nuova}? First I make a backup of the data. [y/N]")
    # "s" e "y" valgono in tutte e due le lingue: chi ha l'abitudine dell'altra non si trova l'aggiornamento annullato.
    if (([string]$risposta).Trim() -notmatch '^(s|si|sì|y|yes)$') {
        Dici 'Aggiornamento annullato: non è cambiato niente.' 'Update cancelled: nothing has changed.'
        exit 0
    }

    Passo 'Backup prima dell''aggiornamento' 'Backup before the update'
    $uscita = & docker compose run --rm -T backup crea prima-dell-aggiornamento 2>&1 | ForEach-Object { "$_" }
    $codiceBackup = $LASTEXITCODE
    $uscita | ForEach-Object { Write-Host $_ }
    if ($codiceBackup -ne 0) { Errore 'Il backup non è riuscito: aggiornamento annullato, non è cambiato niente.' 'The backup failed: update cancelled, nothing has changed.' }
    # Il nome del backup appena fatto, dalle righe del container. Non si cerca una frase (cambia con la lingua) ma il
    # nome del file: contiene data e ora, quindi in ordine alfabetico l'ultimo è il più nuovo, cioè questo (gli altri
    # nomi che il container può scrivere sono quelli dei backup vecchi che ha tolto).
    $nomeBackup = ''
    $nomi = @($uscita | ForEach-Object { [regex]::Matches([string]$_, 'wallettracker-prima-dell-aggiornamento-[0-9-]+\.backup') } | ForEach-Object { $_.Value } | Sort-Object)
    if ($nomi.Count -gt 0) { $nomeBackup = [string]$nomi[$nomi.Count - 1] }
    if ($nomeBackup -eq '') { Errore 'Non trovo il nome del backup appena fatto: aggiornamento annullato, non è cambiato niente.' 'I cannot find the name of the backup just made: update cancelled, nothing has changed.' }

    Passo "Sostituisco i file dell'app (la $adesso la metto da parte in .aggiornamento\precedente)" "Replacing the files of the app (I put $adesso aside in .aggiornamento\precedente)"
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

    Passo "Avvio di WalletTracker $nuova (con lo script nuovo)" "Starting WalletTracker $nuova (with the new script)"
    # Le opzioni con i nomi italiani: sono quelli che lo script di ogni versione, anche futura, deve capire.
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
        Passo "Aggiornamento completato: WalletTracker $nuova è avviato" "Update completed: WalletTracker $nuova is running"
        Dici "Il backup di prima dell'aggiornamento è $nomeBackup (nella cartella dei backup)." "The backup made before the update is $nomeBackup (in the backup folder)."
        Dici "I file della $adesso restano in .aggiornamento\precedente fino al prossimo aggiornamento." "The files of $adesso stay in .aggiornamento\precedente until the next update."
        $porta = Valore-Env 'WEB_PORT'
        if ($porta -eq '') { $porta = '8000' }
        if (-not $SenzaBrowser) { Start-Process "http://localhost:$porta" }
        exit 0
    }

    # --- Non è andata: si torna alla versione di prima, da soli ---
    Passo "L'aggiornamento non è riuscito: torno alla versione $adesso" "The update failed: going back to version $adesso"
    foreach ($f in [System.IO.File]::ReadAllText((Join-Path $precedente '.aggiunti'), $Utf8) -split "`n") {
        if ($f -ne '' -and (Test-Path $f)) { Remove-Item -Force $f }
    }
    foreach ($f in (File-Di $precedente | Where-Object { $_ -ne '.aggiunti' })) { Metti-Al-Posto (Join-Path $precedente $f) $f }
    $env:WALLETTRACKER_VERSION = $adesso
    & docker compose @Profilo up -d db *> $null
    & docker compose stop backend backup *> $null
    & docker compose run --rm -T backup ripristina $nomeBackup
    if ($LASTEXITCODE -ne 0) {
        Errore "Non sono riuscito a rimettere i dati di prima. I file della $adesso sono di nuovo al loro posto; per i dati: rilancia lo script con  -Ripristina backup\$nomeBackup   (docs/50, parte I)." "I could not put the previous data back. The files of $adesso are in their place again; for the data: run the script again with  -Restore backup\$nomeBackup   (docs/50, part I)."
    }
    & docker compose @Profilo up -d
    if ($LASTEXITCODE -ne 0) { Errore "Non riesco a riavviare la versione $adesso`: leggi il messaggio qui sopra (docs/50, parte I)." "I cannot restart version $adesso`: read the message above (docs/50, part I)." }
    Aspetta-Salute 'backend' $Attesa
    Errore "Sono tornato a WalletTracker $adesso, con i dati di prima dell'aggiornamento: è tutto com'era. Il motivo è nei messaggi qui sopra; lo ZIP della $nuova è rimasto in .aggiornamento\nuova (docs/50, parte I)." "I went back to WalletTracker $adesso, with the data from before the update: everything is as it was. The reason is in the messages above; the ZIP of $nuova is still in .aggiornamento\nuova (docs/50, part I)."
}

# --- 6. Backup e ripristino (solo con -Backup o -Ripristina) ----------------------------------------------
# Il lavoro lo fa il container "backup" (docker/backup/wallettracker-backup.sh): vedi i commenti in installa.sh.
if ($Backup) {
    Passo 'Backup' 'Backup'
    & docker compose run --rm -T backup crea manuale
    if ($LASTEXITCODE -ne 0) { Errore 'Il backup non è riuscito: leggi il messaggio qui sopra.' 'The backup failed: read the message above.' }
    Dici "Il backup è nella cartella $CartellaBackupScelta. Copialo anche FUORI da questo computer (docs/48, parte F)." "The backup is in the folder $CartellaBackupScelta. Copy it OUTSIDE this computer too (the guide, chapter 8)."
}

if ($Ripristina -ne '') {
    Passo "Ripristino da $Ripristina" "Restoring from $Ripristina"
    if (-not (Test-Path $Ripristina -PathType Leaf)) { Errore "Non trovo il file $Ripristina." "I cannot find the file $Ripristina." }
    # Il container vede solo la cartella dei backup: se il file è altrove, lo copio lì (con lo stesso nome).
    $file = (Resolve-Path $Ripristina).Path
    $nomeBackup = Split-Path $file -Leaf
    $destinazione = Join-Path $CartellaBackupScelta $nomeBackup
    if ($file -ne $destinazione) {
        if ((Test-Path $destinazione) -and ((Get-FileHash $file).Hash -ne (Get-FileHash $destinazione).Hash)) {
            Errore "Nella cartella dei backup c'è già un file diverso di nome $nomeBackup`: rinomina quello da ripristinare." "In the backup folder there is already a different file named $nomeBackup`: rename the one to restore."
        }
        Copy-Item $file $destinazione -Force
        Dici "Copiato $nomeBackup nella cartella dei backup ($CartellaBackupScelta)." "Copied $nomeBackup to the backup folder ($CartellaBackupScelta)."
    }

    Write-Host ''
    Dici 'ATTENZIONE: il ripristino SOSTITUISCE tutti i dati di adesso (conti, movimenti, regole, impostazioni, PIN)' 'WARNING: the restore REPLACES all the current data (accounts, transactions, rules, settings, PIN)' Yellow
    Dici 'con quelli del backup. Prima ne faccio una copia di sicurezza, se nel .env c''è la password dei backup.' 'with the data of the backup. First I make a safety copy of it, if the backup password is in the .env.' Yellow
    # La parola da scrivere è nella lingua dello script: RIPRISTINA in italiano, RESTORE in inglese.
    $parola = T 'RIPRISTINA' 'RESTORE'
    $risposta = Read-Host (T "Per continuare scrivi $parola" "To continue type $parola")
    if ($risposta -cne $parola) { Errore 'Ripristino annullato: non è cambiato niente.' 'Restore cancelled: nothing has changed.' }

    # 1. La copia di sicurezza di adesso, con la password del .env.
    if (Password-Backup-Valida $PasswordBackup) {
        $env:WALLETTRACKER_BACKUP_PASSWORD = $PasswordBackup
        & docker compose run --rm -T backup crea prima-del-ripristino
        # 6: il database di adesso non ha le tabelle di WalletTracker (installa.sh, e docs/48 parte G4): si va avanti.
        if ($LASTEXITCODE -eq 6) { Dici 'Il database di adesso non ha dati di WalletTracker: niente copia di sicurezza, non c''è niente da salvare.' 'The current database has no WalletTracker data: no safety copy, there is nothing to save.' }
        elseif ($LASTEXITCODE -ne 0) { Errore 'Non riesco a fare la copia di sicurezza dei dati di adesso: ripristino annullato, non è cambiato niente.' 'I cannot make the safety copy of the current data: restore cancelled, nothing has changed.' }
    }
    else { Dici 'Nel .env non c''è la password dei backup: niente copia di sicurezza dei dati di adesso.' 'The backup password is not in the .env: no safety copy of the current data.' }
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
            Errore 'Il ripristino non è riuscito (leggi il messaggio qui sopra). Se si è fermato prima di "Svuoto il database", non è cambiato niente.' 'The restore failed (read the message above). If it stopped before "Emptying the database", nothing has changed.'
        }
        $tentativi++
        $passwordInserita = Leggi-Segreto (T 'Password di QUESTO backup (Invio vuoto per annullare)' 'Password of THIS backup (empty Enter to cancel)')
        if ($passwordInserita -eq '') { Riparti; Errore 'Ripristino annullato: non è cambiato niente.' 'Restore cancelled: nothing has changed.' }
        # Docker Compose preferisce le variabili dell'ambiente a quelle del .env.
        $env:WALLETTRACKER_BACKUP_PASSWORD = $passwordInserita
    }
    Remove-Item Env:WALLETTRACKER_BACKUP_PASSWORD -ErrorAction SilentlyContinue

    # Un computer nuovo, senza password dei backup nel .env: tengo quella appena usata.
    if ($PasswordBackup -eq '' -and (Password-Backup-Valida $passwordInserita)) {
        Scrivi-Env 'WALLETTRACKER_BACKUP_PASSWORD' $passwordInserita
        Dici 'Ho salvato nel .env la password del backup: da stanotte parte il backup automatico.' 'I saved the password of the backup in the .env: from tonight the automatic backup starts.'
    }

    # 4. Si riparte: il backend legge la chiave rimessa a posto, e Flyway porta il database alla versione dell'app.
    Riparti
    Dici 'Ripristino completato: l''app ha i dati del backup. (Il PIN è quello di allora, come tutto il resto.)' 'Restore completed: the app has the data of the backup. (The PIN is the one of that time, like everything else.)'
}

# --- 7. Fatto ----------------------------------------------------------------------------------------------
$porta = Valore-Env 'WEB_PORT'
if ($porta -eq '') { $porta = '8000' }
$indirizzo = "http://localhost:$porta"
Passo "WalletTracker $($env:WALLETTRACKER_VERSION) è avviato: $indirizzo" "WalletTracker $($env:WALLETTRACKER_VERSION) is running: $indirizzo"
if ($Tailscale) {
    Dici "Dai dispositivi remoti, con Tailscale:  https://$NomeTs   (lì serve il PIN: crealo prima da qui, dal server)" "From the remote devices, with Tailscale:  https://$NomeTs   (the PIN is needed there: create it first from here, from the server)"
    Dici "Per collegare una banca da un dispositivo remoto, registra su Enable Banking anche:  https://$NomeTs/callback" "To connect a bank from a remote device, register on Enable Banking also:  https://$NomeTs/callback"
}
if ((Valore-Env 'WALLETTRACKER_BACKUP_PASSWORD') -eq '') {
    Dici 'Backup automatico SPENTO: per accenderlo, e fare il primo backup, rilancia lo script con -Backup (docs/48).' 'Automatic backup OFF: to turn it on, and make the first backup, run the script again with -Backup (the guide, chapter 8).'
}
Dici 'Per fermarlo:  docker compose down   (i dati restano)' 'To stop it:  docker compose down   (the data stays)'

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
    Dici 'All''accensione del computer WalletTracker riparte da solo (parte Docker Desktop, e i container con lui).' 'When the computer is turned on WalletTracker restarts by itself (Docker Desktop starts, and the containers with it).'
}
elseif ($avvioAutomatico -eq 'no') {
    Dici 'AVVIO AUTOMATICO SPENTO: all''accensione del computer Docker Desktop non parte da solo, e quindi nemmeno WalletTracker.' 'AUTOMATIC START OFF: when the computer is turned on Docker Desktop does not start by itself, and so neither does WalletTracker.' Yellow
    Dici '  Per accenderlo: Docker Desktop -> Settings (l''ingranaggio) -> General -> "Start Docker Desktop when you sign in' '  To turn it on: Docker Desktop -> Settings (the gear) -> General -> "Start Docker Desktop when you sign in'
    Dici '  to your computer" -> Apply (docs/50, parte P).' '  to your computer" -> Apply.'
}
else {
    Dici 'Per farlo ripartire da solo all''accensione: Docker Desktop -> Settings -> General -> "Start Docker Desktop when you sign in" (docs/50, parte P).' 'To make it restart by itself when the computer is turned on: Docker Desktop -> Settings -> General -> "Start Docker Desktop when you sign in".'
}
if (-not $SenzaBrowser) { Start-Process $indirizzo }
