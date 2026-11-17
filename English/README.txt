WalletTracker - getting started
===============================

1. Install Docker Desktop (free): https://www.docker.com/products/docker-desktop
   Open it and wait until it says "Engine running".
   In Settings -> General tick "Start Docker Desktop when you sign in to your computer":
   this way WalletTracker restarts by itself when you turn the computer on.

2. Extract the ZIP into a folder you will not move any more (for example Documents).
   On Windows, BEFORE extracting it: right-click the ZIP -> Properties -> tick "Unblock" -> OK.

3. In this folder ("English"), double-click "Start WalletTracker" (.cmd on Windows, .command on a Mac).
   The first time:
   - Windows: "Windows protected your PC" -> More info -> Run anyway;
   - Mac: right-click the file -> Open -> Open.
   The script asks "Lingua / Language": type 2 for English. From then on the script speaks English
   only, and the app opens in English.
   At the end the browser opens on http://localhost:8000

The other two files in this folder:
   "Backup WalletTracker"   makes a backup now (the first time it asks for the backup password:
                            write it down OUTSIDE this computer too, without it they cannot be opened);
   "Update WalletTracker"   moves to the latest version, with a backup first.

The files with Italian names in the folder above ("Avvia", "Aggiorna", "Backup") do exactly the
same things: use whichever you like.

The complete guide (the PIN, the banks, the phone, the common problems):
   https://github.com/rosemgo/WalletTracker/blob/main/GUIDE.md
What is new in each version is in CHANGELOG.md, in the folder above.
The language of the app can be changed at any time from the app: Settings -> Language.
