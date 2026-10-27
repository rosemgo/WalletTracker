# Installa e avvia WalletTracker su Windows (Fase 4, passo 2: docs/45).
# Su macOS e Linux c'è lo stesso script per il terminale: installa.sh.
#
# Si lancia da PowerShell, nella cartella del progetto:
#   powershell -ExecutionPolicy Bypass -File installa.ps1                      primo avvio, e tutte le volte dopo
#   powershell -ExecutionPolicy Bypass -File installa.ps1 -Ia                  con anche Ollama (l'IA locale)
#   powershell -ExecutionPolicy Bypass -File installa.ps1 -CambiaPasswordDb    password nuova per il database
#   powershell -ExecutionPolicy Bypass -File installa.ps1 -SenzaBrowser        non apre il browser alla fine
#
# "-ExecutionPolicy Bypass": Windows, di serie, non esegue gli script scaricati. Così il permesso vale solo per
# questo comando, senza cambiare le impostazioni del PC.
#
# Si può rilanciare quante volte si vuole: quello che c'è già (il .env, la chiave, i dati) non viene toccato.
# Fa le stesse cose di installa.sh, nello stesso ordine: vedi i commenti lì per i dettagli.

param(
    [switch]$Ia,
    [switch]$CambiaPasswordDb,
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
New-Item -ItemType Directory -Force -Path '.data', 'secrets' | Out-Null

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

# --- 5. Avvio ----------------------------------------------------------------------------------------------
Passo 'Avvio di WalletTracker (la prima volta ci vuole qualche minuto: si scaricano e si compilano le immagini)'
if ($Ia) { & docker compose --profile ia up -d --build }
else { & docker compose up -d --build }
if ($LASTEXITCODE -ne 0) { Errore 'docker compose non è riuscito ad avviare l''app: leggi il messaggio qui sopra.' }

Passo 'Aspetto che il backend sia pronto'
Aspetta-Salute 'backend' 240
Write-Host 'Il backend è pronto.'

# --- 6. Fatto ----------------------------------------------------------------------------------------------
$porta = Valore-Env 'WEB_PORT'
if ($porta -eq '') { $porta = '8000' }
$indirizzo = "http://localhost:$porta"
Passo "WalletTracker è avviato: $indirizzo"
Write-Host 'Per fermarlo:  docker compose down   (i dati restano)'
if (-not $SenzaBrowser) { Start-Process $indirizzo }
