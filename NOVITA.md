# Le novità di WalletTracker

Una sezione per ogni versione, dalla più nuova. Quando si pubblica una versione (docs/50), il suo testo diventa la
descrizione della release su GitHub. I numeri seguono la regola MAGGIORE.MINORE.CORREZIONE: `1.0.1` corregge,
`1.1.0` aggiunge qualcosa, `2.0.0` cambia qualcosa di importante per chi lo usa.

## 1.2.7

- **Cedole e dividendi davvero netti, titolo per titolo**: le ritenute di Fineco ("Rit.ced.su", "Rit.div.su") non si
  abbinavano alla loro cedola o al loro dividendo, e finivano tutte nella riga "Ritenute non abbinate". Il totale era
  giusto, ma le righe "Cedole", "Dividendi" e i titoli erano lordi. Ora ogni ritenuta si toglie dalla sua rendita,
  anche quella degli interessi sulla liquidità, e l'abbinamento funziona anche con banche che scrivono i testi in altro
  modo (con il codice ISIN del titolo).
- **Le ritenute sui ratei sono a parte**: quelle che la banca restituisce quando compri un'obbligazione e trattiene
  quando la vendi ("Ritenuta Fiscale … Qta/Val.nom.") non riguardano le cedole, ma il prezzo della compravendita. Ora
  sono tra le "Altre tasse investimenti", nella categoria nuova "Ritenute sui ratei", e non toccano più il netto delle
  rendite.
- **Lo stesso titolo da due banche è una riga sola** in "Da dove" (per esempio "ENEL" di Fineco ed "Enel" di Trade
  Republic).
- La riga che resta per le ritenute senza la loro cedola nel periodo si chiama "Ritenute senza la loro rendita nel
  periodo" e non porta più a un elenco sbagliato.

L'aggiornamento sposta da solo le ritenute sui ratei che hai già, ma non quelle a cui hai scelto il tipo a mano.

## 1.2.6

- **Le ritenute hanno un tipo tutto loro, "Ritenute sulle rendite"**: prima erano una tassa con la categoria
  "Ritenute", e se rinominavi quella categoria Investimenti non le riconosceva più e mostrava le rendite più alte del
  vero. Ora il conto è giusto qualunque nome abbiano le tue categorie. Le altre tasse (bollo, capital gain, Tobin tax)
  si chiamano "Altre tasse investimenti".
- **Per vedere le ritenute pagate**: in Investimenti come prima (lordo, ritenute, netto), e in Movimenti con il filtro
  Tipo → "Ritenute sulle rendite".

L'aggiornamento sistema da solo le ritenute che hai già, anche quelle scelte a mano, e le regole; poi la categoria
"Ritenute" non serve più e sparisce (resta solo se ci avevi messo un budget).

## 1.2.5

(La 1.2.4 non è stata pubblicata: il rilascio si è fermato perché mancavano queste novità.)

- **Niente backup "vuoti"**: se il database non ha ancora le tabelle di WalletTracker (un'installazione appena creata),
  il backup non si fa, e l'app lo dice. Così un backup senza i tuoi dati non prende mai il posto di uno buono.
- **Sul telefono "Quest'anno" sta nel suo riquadro**: in Investimenti usciva dal pulsante, e sui telefoni più stretti
  anche "Settimana" in Analisi.

## 1.2.3

- **I backup non crescono più per sempre**: si tengono gli ultimi 14 automatici, 10 fatti a mano e 3 di prima di ogni
  aggiornamento o ripristino. In Impostazioni → Backup se ne vedono 5, con "Mostra tutti", e le date in colonna.

I backup in più si tolgono da soli, ognuno al primo backup nuovo del suo tipo dopo l'aggiornamento.

## 1.2.2

- **Riepilogo**: "Dove vanno" ora si chiama **"Spese per categoria"**; sul telefono la scheda sta subito sotto il
  grafico, e la liquidità dopo.

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
