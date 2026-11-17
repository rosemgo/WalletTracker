#!/bin/bash
# Starts WalletTracker and opens the app in the browser. (docs/50, parte N; docs/lingue-3-installazione.md)
# The English twin of "Avvia WalletTracker.command", in the folder above. On a Mac a .command file opens with a double
# click: the Terminal runs it. The first time the Mac may say that it "is from an unidentified developer": right-click
# the file, then Open, once.
cd "$(dirname "$0")/.." || exit 1
./installa.sh "$@"
codice=$?
echo
echo "Press Enter to close this window."
read -r _
# The result of the script (0 = all good), even if the last thing done was waiting for Enter.
exit "$codice"
