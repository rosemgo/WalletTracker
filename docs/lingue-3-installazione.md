# Lingue, terza consegna: l'installazione

Dopo la seconda consegna (docs/lingue-2-inglese.md) l'app si usava in italiano o in inglese, ma per arrivarci bisognava
leggere l'italiano: lo script di installazione, la guida, le novità. Questa consegna chiude il cerchio: **chi installa
sceglie la lingua alla prima domanda, e da lì in poi vede una lingua sola**, nello script e nell'app.

Per chi usa già WalletTracker non cambia niente: lo script continua a parlare italiano senza chiedere nulla.

## Parte A: la domanda, una volta sola

Al primo avvio, prima di qualsiasi altra cosa, lo script scrive:

```
Lingua / Language
  1) Italiano
  2) English
Scrivi 1 o 2 e premi Invio / Type 1 or 2 and press Enter [1]:
```

È l'unico punto in cui compaiono le due lingue insieme: lo script non sa ancora quale capisci. Invio da solo sceglie
quella proposta tra parentesi quadre, che è quella del sistema operativo. La risposta finisce nel `.env`
(`WALLETTRACKER_LANGUAGE=it` oppure `en`) e da quel momento vale per:

- **lo script** (`installa.sh`, `installa.ps1`): ogni riga, le domande, gli errori, l'aiuto;
- **il container dei backup**: le righe che scrive nel terminale e nei log;
- **l'app, in un'installazione nuova**: la prima schermata si apre in quella lingua (parte D).

| Situazione | Che lingua parla lo script |
|---|---|
| Prima installazione, da una finestra vera | chiede, e usa la risposta |
| Prima installazione senza tastiera (la CI, un altro programma) | italiano, senza chiedere: è il comportamento di sempre |
| Il `.env` c'è e ha `WALLETTRACKER_LANGUAGE` | quella |
| Il `.env` c'è ma la riga manca (chi aggiorna da una versione di prima) | italiano, senza chiedere e senza scrivere niente |
| `--lingua it\|en` (su Windows `-Lingua`) | quella, subito, e la scrive nel `.env` |

`--lingua` serve a scegliere senza domanda e a **cambiare dopo**. Cambia la lingua dello script, non quella dell'app
già in uso: quella si cambia dall'app (Impostazioni → Lingua), e lo script lo dice.

**Perché due posti (il `.env` e il database)?** La lingua dell'app è un dato dell'app, sta nel database e si cambia
dall'app, anche dal telefono (consegna 2). Lo script gira prima che l'app esista, e anche quando l'app è spenta o non
parte: gli serve una lingua che non dipenda dal database. Di solito coincidono, perché la seconda nasce dalla prima.

## Parte B: come sono scritti gli script

Le frasi non stanno in file separati, uno per lingua: sono **nello script, una accanto all'altra**, prima in italiano e
poi in inglese.

```bash
dici "Docker è avviato." "Docker is running."
errore "Non trovo il file $FILE_RIPRISTINO." "I cannot find the file $FILE_RIPRISTINO."
```

```powershell
Dici 'Docker è avviato.' 'Docker is running.'
```

`t`, `dici`, `passo`, `errore` (in PowerShell `T`, `Dici`, `Passo`, `Errore`) scelgono la frase da mostrare. A schermo
la lingua è sempre una sola.

Perché così, e non un file di frasi per lingua (come nel backend e nella dashboard):

- lo script deve funzionare **da solo**: è il file che "Aggiorna" sostituisce mentre gira, e che riparte da uno ZIP
  appena scaricato. Un file di frasi mancante, o della versione sbagliata, lo lascerebbe muto proprio quando serve;
- chi cambia una frase ha davanti l'altra: non si può dimenticare la traduzione senza vederlo.

**Le opzioni** hanno due nomi, e valgono tutti e due in qualsiasi lingua:

| Italiano | Inglese | Windows (italiano / inglese) |
|---|---|---|
| `--ia` | `--ai` | `-Ia` / `-Ai` |
| `--cambia-password-db` | `--change-db-password` | `-CambiaPasswordDb` / `-ChangeDbPassword` |
| `--reimposta-pin` | `--reset-pin` | `-ReimpostaPin` / `-ResetPin` |
| `--tailscale` | `--tailscale` | `-Tailscale` |
| `--backup` | `--backup` | `-Backup` |
| `--ripristina FILE` | `--restore FILE` | `-Ripristina` / `-Restore` |
| `--cartella-backup` | `--backup-folder` | `-CartellaBackup` / `-BackupFolder` |
| `--aggiorna` | `--update` | `-Aggiorna` / `-Update` |
| `--lingua it\|en` | `--language it\|en` | `-Lingua` / `-Language` |
| `--senza-browser` | `--no-browser` | `-SenzaBrowser` / `-NoBrowser` |
| `--aiuto` | `--help` | `-Aiuto` / `-Help` |

I nomi italiani restano quelli "di base": li usano i file da doppio clic italiani e l'aggiornamento, quando lancia lo
script della versione nuova. Non si possono togliere: lo script di ogni versione futura deve capire quello che gli
scrive lo script di una versione vecchia.

**Le conferme** seguono la lingua: `[s/N]` e `[y/N]` (ma "s" e "y" valgono sempre tutti e due: chi ha l'abitudine
dell'altra lingua non si trova l'aggiornamento annullato); per il ripristino si scrive `RIPRISTINA` in italiano e
`RESTORE` in inglese.

**Quello che non si traduce**: i nomi dei file dei backup (`wallettracker-manuale-…`, `…-prima-dell-aggiornamento-…`), i
comandi interni del container (`crea`, `ripristina`), i nomi delle cartelle (`.aggiornamento/precedente`). Sono nomi,
non frasi: li leggono i programmi.

## Parte C: il container dei backup

`docker/backup/wallettracker-backup.sh` usa lo stesso sistema: `log "italiano" "inglese"`, con la lingua che riceve da
Docker Compose (`WALLETTRACKER_LANGUAGE`).

Due cose passavano per una frase, e ora no:

- **l'errore dell'ultimo backup, mostrato nella dashboard.** Il container scriveva la frase italiana in `stato.txt`, e
  il backend la passava com'era. Ora scrive un **codice** (`errore-codice=password-mancante`, `database-vuoto`,
  `non-riuscito`) e la frase la sceglie il backend, dal catalogo, nella lingua dell'app (`BackupService.lastError`,
  `backup.error.*` in `messages.properties`). È la regola della prima consegna, codici al posto delle frasi, arrivata
  anche qui. Così un'app in inglese non mostra un errore in italiano solo perché lo script era stato installato in
  italiano;
- **il nome del backup fatto prima di un aggiornamento.** Lo script lo leggeva dalla riga "Backup creato: …". Ora cerca
  il nome del file, non la frase: tra i nomi scritti dal container prende l'ultimo in ordine alfabetico, che per come
  sono fatti i nomi (data e ora) è il più nuovo.

## Parte D: l'app si apre nella lingua scelta installando

Il backend riceve `WALLETTRACKER_LANGUAGE` (`wallettracker.installer-language`) e, **finché nessuno ha scelto la lingua
dall'app**, la dice alla dashboard: `GET /api/language` risponde `{"language": null, "suggested": "en", …}`.

L'avvio della dashboard (`language.ts`, `startLanguage`) aveva tre passi; ora sono quattro:

1. la lingua ricordata da questo browser;
2. quella dell'installazione, se qualcuno l'ha scelta;
3. **quella scelta installando** (`suggested`);
4. quella del browser.

È una proposta, non una scelta: non viene ricordata, e nella prima schermata la si può ancora cambiare. Diventa la
lingua dell'installazione quando si entra, come nella seconda consegna (e allora le categorie di partenza prendono i
nomi in quella lingua). Senza la variabile (chi sviluppa con `./mvnw`) resta tutto com'era: decide il browser.

Tre file fissi, che Angular non traduce, hanno la loro versione:

- il **manifest** dell'app installabile: `manifest.webmanifest` (italiano) e `manifest.en.webmanifest`. `index.html`
  indica il primo; in inglese `main.ts` cambia il collegamento prima di avviare l'app. nginx serve tutti e due con il
  tipo giusto;
- la **descrizione della pagina** (`<meta name="description">`): la riscrive `App`, con una frase tradotta come le altre;
- la riga per chi ha **JavaScript spento**: nelle due lingue insieme, perché senza JavaScript non si può sapere quale
  è stata scelta.

Il collegamento alla guida (`GUIDE_URL`) porta a `GUIDA.md` o a `GUIDE.md`.

## Parte E: lo ZIP, la guida, le novità

**Uno ZIP solo**, come prima, con lo stesso nome (`WalletTracker-X.Y.Z.zip`): è quello che "Aggiorna" di ogni versione
già installata cerca. In più contiene:

```
LEGGIMI.txt                       (in cima: "English? Open the English folder")
Avvia / Aggiorna / Backup WalletTracker    .cmd e .command
NOVITA.md        CHANGELOG.md
English/
   README.txt                     il LEGGIMI in inglese
   Start / Update / Backup WalletTracker   .cmd e .command
```

I file della cartella `English` lanciano lo stesso script della cartella sopra, con le stesse opzioni: cambia solo il
nome, e la riga che scrivono alla fine ("Press Enter to close this window."). La lingua dello script non dipende da
quale file si è aperto, ma dalla risposta alla domanda.

| Italiano | Inglese | Note |
|---|---|---|
| `GUIDA.md` | `GUIDE.md` | stessi capitoli, stessi numeri: i messaggi dicono "capitolo 8" / "chapter 8" |
| `NOVITA.md` | `CHANGELOG.md` | stesse sezioni `## X.Y.Z`. "Aggiorna" mostra quello della lingua dello script |
| `LEGGIMI.txt` | `English/README.txt` | solo ASCII, fini riga di Windows (si aprono con il Blocco note) |

**Pubblicando una versione** le novità si scrivono in tutti e due i file. `rilascio.yml` si ferma prima di pubblicare
se la sezione manca in uno dei due, e la descrizione della release le porta entrambe (prima l'italiano).

Le guide per chi sviluppa (`docs/`) e il `README.md` restano in italiano: il `README.md` e `SECURITY.md` hanno in
cima qualche riga in inglese che dice che cos'è il progetto e dove andare.

## Parte F: le prove

- **`installa.sh`, sul computer di sviluppo**, con un finto `docker`: l'installazione, l'aggiornamento riuscito e
  quello che fallisce, il ripristino, l'aiuto e gli errori, in italiano e in inglese; la domanda da un terminale finto
  (Invio, una risposta sbagliata, "English"); un `.env` di prima delle lingue; `--lingua` nei due versi; e **lo script
  della versione di prima che aggiorna a questo**, che resta in italiano e non tocca il `.env`. 64 controlli.
- **Le frasi italiane non sono cambiate**: confrontate una per una con quelle di prima, in tutti e due gli script (le
  uniche differenze sono volute: la parola del ripristino, lo ZIP nominato nell'errore).
- **La CI, con Docker vero**: il job dell'installazione passa all'inglese e torna all'italiano, controlla il container
  dei backup in inglese, la lingua proposta dal backend e il manifest inglese; il job dell'aggiornamento prova la
  domanda sulla lingua, la cartella `English` nello ZIP e un'installazione che passa all'inglese (caso 6).
- **`installa.ps1`, per la prima volta in una prova**: un job nuovo su una macchina Windows lo fa leggere a Windows
  PowerShell 5.1 e a PowerShell 7, poi lo esegue dall'inizio alla fine con un finto `docker`, in italiano e in inglese
  (`.github/ci/prova-windows.ps1`). Prima di questa consegna nessuna prova lo eseguiva.
- **Backend**: `BackupServiceTest` (i codici del container, la frase di un container vecchio), `InstallerLanguageTest`
  (la lingua proposta), `MessageCatalogTest` (le due frasi nuove ci sono in tutte e due le lingue).
- **Dashboard**: `language.spec.ts` (la lingua scelta installando vince sul browser, perde con quella scelta dall'app).

## Parte G: tornare indietro

Vale quello che dice la seconda consegna (docs/lingue-2-inglese.md, parte G). Questa consegna non aggiunge niente al
database. Nel `.env` può restare la riga `WALLETTRACKER_LANGUAGE`: una versione di prima la ignora.

Un'installazione fatta in inglese che torna a una versione di prima delle lingue vedrebbe lo script in italiano (quello
vecchio non sa altro) e le categorie con i nomi inglesi: prima di tornare indietro, rimetti la lingua dell'app in
italiano da Impostazioni.
