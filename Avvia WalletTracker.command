#!/bin/bash
# Avvia WalletTracker e apre l'app nel browser. (docs/50, parte N)
# Sul Mac un file .command si apre con un doppio clic: il Terminale lo esegue. La prima volta il Mac può dire che
# "proviene da uno sviluppatore non identificato": tasto destro sul file, poi Apri, una volta sola.
cd "$(dirname "$0")" || exit 1
./installa.sh "$@"
codice=$?
echo
echo "Premi Invio per chiudere questa finestra."
read -r _
# Il risultato dello script (0 = tutto bene), anche se l'ultima cosa fatta è stata aspettare Invio.
exit "$codice"
