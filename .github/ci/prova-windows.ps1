# La prova di installa.ps1 su Windows (docs/lingue-3-installazione.md). La usa la CI (job "Script di Windows").
#
#   prova-windows.ps1 -SoloLettura   solo il lettore di PowerShell: errori di scrittura, e il BOM
#   prova-windows.ps1                lo script dall'inizio alla fine, con un finto docker (docker.cmd, qui accanto)
#
# Questo file ha solo caratteri senza accenti, apposta: non ha il BOM, e Windows PowerShell 5.1 leggerebbe male le
# lettere accentate. Per lo stesso motivo le frasi cercate nell'uscita di installa.ps1 sono pezzi senza accenti.
param([switch]$SoloLettura)

$ErrorActionPreference = 'Stop'
$radice = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$installa = Join-Path $radice 'installa.ps1'

function Fallito([string]$motivo) {
    Write-Host ''
    Write-Host "PROVA FALLITA: $motivo" -ForegroundColor Red
    exit 1
}

# --- 1. Il lettore di PowerShell ---------------------------------------------------------------------------
# Senza il BOM (i tre byte EF BB BF all'inizio) Windows PowerShell 5.1 legge il file con la codifica vecchia di
# Windows: le lettere accentate diventano altri caratteri, e una frase puo' perfino chiudersi nel punto sbagliato.
$byte = [System.IO.File]::ReadAllBytes($installa)
if ($byte[0] -ne 0xEF -or $byte[1] -ne 0xBB -or $byte[2] -ne 0xBF) { Fallito 'installa.ps1 deve cominciare con il BOM di UTF-8' }
$gettoni = $null
$errori = $null
[System.Management.Automation.Language.Parser]::ParseFile($installa, [ref]$gettoni, [ref]$errori) | Out-Null
if ($errori.Count -gt 0) {
    foreach ($errore in $errori) { Write-Host "installa.ps1, riga $($errore.Extent.StartLineNumber): $($errore.Message)" }
    Fallito "installa.ps1 ha $($errori.Count) errori di scrittura"
}
Write-Host "installa.ps1 si legge senza errori (PowerShell $($PSVersionTable.PSVersion))."
if ($SoloLettura) { exit 0 }

# --- 2. Lo script dall'inizio alla fine, con il finto docker ---------------------------------------------------
$lavoro = Join-Path $env:RUNNER_TEMP 'prova-windows'
New-Item -ItemType Directory -Force -Path $lavoro | Out-Null
Copy-Item (Join-Path $PSScriptRoot 'docker.cmd') $lavoro -Force
$env:PATH = "$lavoro;$env:PATH"
$env:FINTO_DOCKER_LOG = Join-Path $lavoro 'docker.log'
$env:WALLETTRACKER_ATTESA_BACKEND = '9'
$vuoto = Join-Path $lavoro 'vuoto.txt'
[System.IO.File]::WriteAllText($vuoto, '')
if ((Get-Command docker).Source -ne (Join-Path $lavoro 'docker.cmd')) { Fallito "il docker trovato non e' quello finto: $((Get-Command docker).Source)" }

# Lancia installa.ps1 come il doppio clic (Windows PowerShell 5.1, -File), senza tastiera: l'ingresso arriva da un
# file vuoto, quindi lo script non puo' fare domande. Restituisce quello che ha scritto; il codice e' in $Codice.
$Codice = 0
function Lancia([string[]]$opzioni) {
    $uscita = Join-Path $lavoro 'uscita.txt'
    $errori = Join-Path $lavoro 'errori.txt'
    $argomenti = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$installa`"") + $opzioni
    $processo = Start-Process -FilePath 'powershell.exe' -ArgumentList $argomenti -NoNewWindow -Wait -PassThru `
        -RedirectStandardInput $vuoto -RedirectStandardOutput $uscita -RedirectStandardError $errori
    $script:Codice = $processo.ExitCode
    $testo = [System.IO.File]::ReadAllText($uscita) + [System.IO.File]::ReadAllText($errori)
    Write-Host "---- installa.ps1 $($opzioni -join ' ')  (codice $($script:Codice))"
    Write-Host $testo
    return $testo
}
function Deve-Avere([string]$testo, [string]$pezzo, [string]$che) {
    if (-not $testo.Contains($pezzo)) { Fallito "$che (manca: $pezzo)" }
    Write-Host "OK   $che"
}
function Non-Deve-Avere([string]$testo, [string]$pezzo, [string]$che) {
    if ($testo.Contains($pezzo)) { Fallito "$che (c'e': $pezzo)" }
    Write-Host "OK   $che"
}
function Riga-Env([string]$nome) {
    foreach ($riga in [System.IO.File]::ReadAllLines((Join-Path $radice '.env'))) {
        if ($riga.StartsWith("$nome=")) { return $riga.Substring($nome.Length + 1) }
    }
    return $null
}

# 2a. La prima installazione, senza tastiera: niente domanda sulla lingua, e italiano (come e' sempre stato).
if (Test-Path (Join-Path $radice '.env')) { Fallito "c'e' gia' un .env: la prova vuole una cartella pulita" }
$testo = Lancia @('-SenzaBrowser')
if ($Codice -ne 0) { Fallito "la prima installazione e' finita con il codice $Codice" }
Deve-Avere $testo '==> Controllo Docker' 'la prima installazione parla italiano'
Deve-Avere $testo 'Creato il file .env' 'il .env viene creato'
Deve-Avere $testo 'avviato: http://localhost:8000' "arriva in fondo"
Non-Deve-Avere $testo 'Lingua / Language' 'senza tastiera la domanda sulla lingua non arriva'
Non-Deve-Avere $testo 'Checking Docker' "niente inglese nell'installazione italiana"
if ((Riga-Env 'POSTGRES_PASSWORD') -notmatch '^[A-Za-z0-9]{32}$') { Fallito 'la password del database non e'' di 32 lettere e cifre' }
if ($null -ne (Riga-Env 'WALLETTRACKER_LANGUAGE')) { Fallito 'la lingua e'' stata scritta nel .env senza che nessuno la scegliesse' }
$inizio = [System.IO.File]::ReadAllBytes((Join-Path $radice '.env'))
if ($inizio[0] -eq 0xEF -and $inizio[1] -eq 0xBB) { Fallito 'il .env comincia con il BOM: Docker Compose lo leggerebbe male' }
$chiamate = [System.IO.File]::ReadAllText($env:FINTO_DOCKER_LOG)
Deve-Avere $chiamate 'compose -f docker-compose.yml -f docker-compose.build.yml up -d --build' 'docker compose viene chiamato come dal codice'

# 2b. In inglese, con il nome inglese delle opzioni; poi resta in inglese da solo.
$testo = Lancia @('-Language', 'en', '-NoBrowser')
if ($Codice -ne 0) { Fallito "-Language en e' finito con il codice $Codice" }
Deve-Avere $testo '==> Checking Docker' '-Language en: lo script parla inglese'
Deve-Avere $testo 'From now on this script speaks English' '-Language en: lo dice'
Deve-Avere $testo 'is running: http://localhost:8000' '-Language en: arriva in fondo'
Non-Deve-Avere $testo 'Controllo Docker' "-Language en: niente italiano"
if ((Riga-Env 'WALLETTRACKER_LANGUAGE') -ne 'en') { Fallito '-Language en non ha scritto la lingua nel .env' }
$testo = Lancia @('-SenzaBrowser')
Deve-Avere $testo 'is running: http://localhost:8000' 'al lancio dopo resta in inglese'
Non-Deve-Avere $testo 'From now on this script speaks' 'e non lo ripete'

# 2c. Un errore, in inglese: il backup senza la password, e senza tastiera per chiederla.
$testo = Lancia @('-Backup', '-NoBrowser')
if ($Codice -ne 1) { Fallito "il backup senza password doveva finire con il codice 1, non $Codice" }
Deve-Avere $testo 'ERROR: The backup password is missing' "l'errore e' in inglese"

# 2d. L'aiuto e -Update dal codice, in inglese.
$testo = Lancia @('-Help')
Deve-Avere $testo '-BackupFolder' "l'aiuto in inglese"
$testo = Lancia @('-Update')
if ($Codice -ne 0) { Fallito "-Update dal codice doveva finire con il codice 0, non $Codice" }
Deve-Avere $testo 'SOURCE CODE folder' '-Update dal codice: lo spiega, in inglese'

# 2e. Di nuovo in italiano, con il nome italiano; una lingua che non c'e' viene rifiutata.
$testo = Lancia @('-Lingua', 'it', '-SenzaBrowser')
if ($Codice -ne 0) { Fallito "-Lingua it e' finito con il codice $Codice" }
Deve-Avere $testo 'Da adesso questo script parla italiano' '-Lingua it: lo dice'
Deve-Avere $testo '==> Controllo Docker' '-Lingua it: lo script parla italiano'
if ((Riga-Env 'WALLETTRACKER_LANGUAGE') -ne 'it') { Fallito '-Lingua it non ha scritto la lingua nel .env' }
$testo = Lancia @('-Aiuto')
Deve-Avere $testo '-CartellaBackup' "l'aiuto in italiano"
$testo = Lancia @('-Lingua', 'fr')
if ($Codice -ne 1) { Fallito "-Lingua fr doveva finire con il codice 1, non $Codice" }
Deve-Avere $testo '-Lingua vuole it (italiano) oppure en (inglese)' '-Lingua fr viene rifiutata'
$testo = Lancia @('-Aggiorna')
Deve-Avere $testo 'CODICE SORGENTE' '-Aggiorna dal codice: lo spiega, in italiano'

Write-Host ''
Write-Host 'Tutte le prove di installa.ps1 sono passate.'
