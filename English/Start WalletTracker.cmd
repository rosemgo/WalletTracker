@echo off
rem Starts WalletTracker and opens the app in the browser. (docs/50, parte N; docs/lingue-3-installazione.md)
rem The English twin of "Avvia WalletTracker.cmd", in the folder above: a double click on this file runs installa.ps1
rem with PowerShell, from the WalletTracker folder. Only characters without accents: cmd.exe reads .cmd files in an
rem old encoding, and accented letters would become strange symbols.
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\installa.ps1" %*
set codice=%errorlevel%
echo.
echo The window stays open so you can read the messages above.
pause
rem The result of the script (0 = all good), even if the last thing done was the pause.
exit /b %codice%
