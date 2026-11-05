@echo off
rem Avvia WalletTracker e apre l'app nel browser. (docs/50, parte N)
rem Un doppio clic su questo file lancia installa.ps1 con PowerShell, dalla cartella di WalletTracker. Solo
rem caratteri senza accenti: cmd.exe legge i file .cmd in una codifica vecchia, e le lettere accentate
rem diventerebbero simboli strani.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0installa.ps1" %*
set codice=%errorlevel%
echo.
echo La finestra resta aperta per leggere i messaggi qui sopra.
pause
rem Il risultato dello script (0 = tutto bene), anche se l ultima cosa fatta e stata la pausa.
exit /b %codice%
