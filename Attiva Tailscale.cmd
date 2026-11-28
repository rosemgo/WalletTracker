@echo off
rem Attiva Tailscale: l'app dal telefono e il collegamento delle banche. (docs/47; docs/50, parte N)
rem Un doppio clic su questo file lancia installa.ps1 -Tailscale con PowerShell, dalla cartella di WalletTracker.
rem Solo caratteri senza accenti: cmd.exe legge i file .cmd in una codifica vecchia, e le lettere accentate
rem diventerebbero simboli strani.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0installa.ps1" -Tailscale %*
set codice=%errorlevel%
echo.
echo La finestra resta aperta per leggere i messaggi qui sopra.
pause
rem Il risultato dello script (0 = tutto bene), anche se l ultima cosa fatta e stata la pausa.
exit /b %codice%
