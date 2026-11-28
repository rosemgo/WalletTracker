@echo off
rem Enable Tailscale: the app from your phone and the connection of the banks. (docs/47; docs/lingue-3-installazione.md)
rem The English twin of "Attiva Tailscale.cmd", in the folder above: a double click on this file runs
rem installa.ps1 -Tailscale with PowerShell, from the WalletTracker folder. Only characters without accents: cmd.exe
rem reads .cmd files in an old encoding, and accented letters would become strange symbols.
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\installa.ps1" -Tailscale %*
set codice=%errorlevel%
echo.
echo The window stays open so you can read the messages above.
pause
rem The result of the script (0 = all good), even if the last thing done was the pause.
exit /b %codice%
