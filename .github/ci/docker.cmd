@echo off
rem Il finto docker della prova di installa.ps1 su Windows (.github/ci/prova-windows.ps1). Non avvia niente: scrive
rem in un file come e' stato chiamato e risponde quello che serve allo script per arrivare in fondo.
rem   docker volume ...   1: "il volume non c'e'" (un'installazione nuova, senza il database di una precedente)
rem   docker inspect ...  scrive "healthy": il backend e' pronto
rem   tutto il resto      0: "tutto bene"
if not "%FINTO_DOCKER_LOG%"=="" echo docker %*>>"%FINTO_DOCKER_LOG%"
if "%1"=="volume" exit /b 1
if "%1"=="inspect" echo healthy
exit /b 0
