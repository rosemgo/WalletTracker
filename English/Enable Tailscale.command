#!/bin/bash
# Enable Tailscale: the app from your phone and the connection of the banks. (docs/47; docs/lingue-3-installazione.md)
# The English twin of "Attiva Tailscale.command", in the folder above. On a Mac a .command file opens with a double
# click: the Terminal runs it. The first time the Mac may say it is "from an unidentified developer": right-click the
# file, then Open, only once.
cd "$(dirname "$0")/.." || exit 1
./installa.sh --tailscale "$@"
codice=$?
echo
echo "Press Enter to close this window."
read -r _
# The result of the script (0 = all good), even if the last thing done was waiting for Enter.
exit "$codice"
