# Le novità di WalletTracker

Una sezione per ogni versione, dalla più nuova. Quando si pubblica una versione (docs/50), il suo testo diventa la
descrizione della release su GitHub. I numeri seguono la regola MAGGIORE.MINORE.CORREZIONE: `1.0.1` corregge,
`1.1.0` aggiunge qualcosa, `2.0.0` cambia qualcosa di importante per chi lo usa.

## 1.2.2

- **Riepilogo**: "Dove vanno" ora si chiama **"Spese per categoria"**; sul telefono la scheda sta subito sotto il
  grafico, e la liquidità dopo.
- **I backup non crescono più per sempre**: si tengono gli ultimi 14 automatici, 10 fatti a mano e 3 di prima di ogni
  aggiornamento o ripristino. In Impostazioni → Backup se ne vedono 5, con "Mostra tutti", e le date in colonna.

## 1.2.1

- **"Aggiorna" funziona anche quando il servizio di GitHub per le versioni non risponde**: lo script riprova da solo, e
  se serve legge l'ultima versione dalla pagina normale di GitHub. Lo ZIP si controlla con la sua impronta, come sempre.
- **Gli errori dicono il motivo**: il codice di GitHub e che cosa vuol dire, invece di "controlla la connessione".
- **Ritocchi di "Aggiorna"**: su Windows la domanda di conferma diceva "Aggiorno WalletTracker alla  Prima…", senza il
  numero della versione; e le novità si leggono senza i segni di formattazione (`**`).

Chi ha già scelto la cartella dei backup non si vede fare la domanda: lo script la chiede solo a chi non l'ha mai scelta.

## 1.2.0

- **Scegli dove salvare i backup**: al primo avvio lo script chiede la cartella (un disco esterno, o la cartella di
  Google Drive sul computer), e per cambiarla dopo c'è `installa.ps1 -CartellaBackup`
  (`./installa.sh --cartella-backup`). Niente più `.env` da modificare a mano.
- **L'app mostra dove sono i backup**: in Impostazioni → Backup, con il comando per cambiare cartella.
- **La guida**: come tenere i backup su Google Drive ("Esegui il mirroring dei file"), capitolo 8.

Hai già la 1.1.1? Doppio clic su **Aggiorna WalletTracker**: una volta sola ti chiederà dove salvare i backup
(Invio lascia quella di adesso, la cartella **backup**). Se ne scegli un'altra, i backup già fatti restano dove sono:
spostali tu.

## 1.1.1

- **Collegare le banche dallo ZIP funziona**: Enable Banking vuole un indirizzo https, e ora l'app usa quello di
  Tailscale anche quando la apri dal server (http://localhost:8000). Al ritorno dalla banca si apre l'indirizzo di
  Tailscale, l'app chiede il PIN e finisce il collegamento. Prima la banca rimandava a una pagina che non rispondeva.
- **Senza Tailscale l'app lo dice prima**: in "Collega una banca" e in Impostazioni, con che cosa fare, invece di
  fermarsi dopo il consenso alla banca.
- **Investimenti più compatti**: gli elenchi lunghi ("Da dove", con un titolo per riga) mostrano le prime 5 righe, con
  "Mostra tutti" e "Mostra meno"; la liquidità è sotto i bonifici, e la pagina non lascia più spazi vuoti.
- **La guida**: Tailscale prima delle banche, perché serve anche a collegarle.

Hai già la 1.1.0? Doppio clic su **Aggiorna WalletTracker**. Se avevi registrato su Enable Banking solo
`https://localhost:8443/callback`, aggiungi l'indirizzo di Tailscale che trovi in Impostazioni.

## 1.1.0

- **Aggiorna con un doppio clic** ("Aggiorna WalletTracker"): l'app passa all'ultima versione da sola, con un backup
  prima; se la versione nuova non parte, torna da sola a quella di prima, con i dati di prima.
- **L'app avvisa quando esce una versione nuova**: una striscia in alto, e le novità in Impostazioni → Informazioni.
- **Avvia, Aggiorna e Backup con un doppio clic**, su Windows e sul Mac: niente terminale e niente comandi.
- **Docker Desktop spento?** Lo script lo accende da solo, e alla fine dice se all'accensione del computer
  WalletTracker ripartirà da solo.
- **Ritocchi**: le maiuscole a inizio riga in tutta l'app ("Acquisto titoli", "Rinomina"…), niente riquadro dopo un
  clic su "Cambia il logo", le azioni che non vanno più a capo a metà; "crea una regola" in un movimento senza
  categoria è un collegamento.

**Da questa versione in poi** si aggiorna con "Aggiorna WalletTracker". Per arrivarci dalla 1.0.0, una volta sola,
scarica lo ZIP della 1.1.0 dalla pagina delle versioni (`docs/50`, parte S).

## 1.0.0

La prima versione pubblicata.

- **I tuoi conti**, letti dalle banche con Enable Banking o importati da file CSV (anche Trade Republic e Tinaba),
  classificati da regole, suggerimenti e, se vuoi, dall'intelligenza artificiale.
- **Riepilogo, Movimenti, Analisi, Budget, Abbonamenti e Investimenti**, pensati per il telefono.
- **Un PIN** per aprire l'app (facoltativo dal computer su cui è installata), con il codice di recupero.
- **Dal telefono**, ovunque, con Tailscale e un https vero; l'app si installa come un'app vera.
- **Backup** cifrati con una password tua, ogni notte da soli, e "Fai un backup adesso" dalle Impostazioni.
