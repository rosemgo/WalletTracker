# I loghi delle banche accanto ai movimenti

Fuori dalla numerazione, come `docs/saldi.md`. Nasce da un'idea dell'utente: nell'elenco dei movimenti si capisce
meglio da quale conto viene un movimento se si vede il logo della banca. I loghi degli **esercenti** sarebbero belli,
ma non c'è una fonte affidabile (andrebbero indovinati dal testo della causale); quelli delle **banche** sì.

| Parte | Cosa |
|---|---|
| A | l'idea: il logo piccolo nell'angolo del cerchio |
| B | i conti collegati: il logo dato da Enable Banking, scaricato una volta |
| C | i conti manuali: un'immagine caricata da te, e perché si controlla |
| D | il nome del conto accanto al movimento: breve o per esteso |
| E | i test, e la prova nel browser |
| F | esercizi guidati |

---

## Parte A: l'idea

Ogni movimento ha già un **cerchio** con l'iniziale dell'esercente e un colore che dipende dal nome (`avatarHue` in
`frontend/src/app/transactions/day-groups.ts`): lo stesso esercente ha sempre lo stesso colore. Il cerchio resta
com'è; il logo della banca si aggiunge **piccolo, nell'angolo in basso a destra**, con un bordo del colore della
scheda, che lo stacca dal cerchio. Se un conto non ha un logo, il cerchio è come prima.

Il cerchio è diventato un piccolo componente, `Avatar` (`frontend/src/app/transactions/avatar.ts`), usato in quattro
posti: l'elenco dei movimenti, la scheda del movimento (il cerchio grande), Analisi e gli elenchi della pagina
Investimenti. Prima lo stesso pezzo di HTML era ripetuto quattro volte.

Il logo arriva **dal nostro server**, sempre (`GET /api/accounts/{id}/logo`), mai da un sito esterno:

- la regola di sicurezza delle pagine (`img-src 'self' data:`, `docs/17-sicurezza.md`) non cambia;
- nessun sito esterno sa quando apri l'app;
- l'app funziona anche se il sito della banca è irraggiungibile.

Nell'indirizzo c'è un numero, `?v=…` (`logoVersion` nel conto): cambia quando cambia il logo. Così il browser può
tenere l'immagine in memoria per un giorno, e quando carichi un logo nuovo lo vede subito, perché l'indirizzo è nuovo.

## Parte B: i conti collegati

L'elenco delle banche di Enable Banking (`GET /aspsps`, `docs/13`) dà per ogni banca anche un campo `logo`:
l'indirizzo di un'immagine. Il backend lo usa così (`LogoService.refreshBankLogos`):

1. cerca i conti collegati **senza logo**;
2. chiede l'elenco delle banche **una volta per Paese** (non una volta per conto);
3. trova la banca per nome (senza badare a maiuscole e minuscole);
4. scarica l'immagine (`LogoDownloader`) e la salva nella tabella `account_logo` (migrazione `V36`).

Succede subito dopo aver collegato una banca e alla fine di ogni sincronizzazione. Di solito non c'è niente da fare: un
logo già salvato non si scarica di nuovo. Se la banca non ha un logo, o lo scaricamento non riesce, l'app lo segna e
**riprova dopo una settimana**, non a ogni sincronizzazione. Un errore viene scritto nel log e non ferma la
sincronizzazione dei movimenti.

Lo scaricamento è prudente:

- solo indirizzi `https`;
- al massimo 10 secondi;
- al massimo 256 KB (si legge un byte in più per accorgersi di un file più grande, e poi ci si ferma);
- il file deve essere davvero un'immagine (parte C). Per le banche è ammesso anche l'SVG, perché molti loghi sono SVG.

**Da verificare con la tua banca vera.** Il campo `logo` c'è nell'esempio di risposta usato dai test del client
(`EnableBankingClientTest`), ma non è stato provato con il servizio vero. Dopo "Aggiorna ora", nel log del backend
(`docs/45`) trovi una di queste righe:

- `logo della banca scaricato`: tutto bene, il logo compare nei movimenti;
- `logo della banca non trovato`: Enable Banking non dà un logo per quella banca; l'app riprova tra una settimana;
- `logo della banca non scaricato: …`: l'indirizzo c'era ma lo scaricamento non è riuscito (il motivo segue i due
  punti); anche qui si riprova tra una settimana.

## Parte C: i conti manuali

Un conto manuale (Tinaba, un CSV, `docs/25`) non ha una banca da cui prendere il logo. Nella pagina **Conti**, accanto
a un conto manuale, c'è **"carica il logo"**: scegli un'immagine dal telefono e si carica subito. Poi diventa
"cambia il logo", e accanto c'è "togli il logo".

Un file caricato potrebbe essere qualunque cosa, quindi il backend non si fida del nome né del tipo dichiarato dal
browser, e guarda i **primi byte** del file (`LogoImage.typeOf`):

| Tipo | Come inizia il file |
|---|---|
| PNG | `89 50 4E 47 0D 0A 1A 0A` |
| JPEG | `FF D8 FF` |
| WebP | `RIFF`, quattro byte, poi `WEBP` |

Altro non si accetta (errore 400: "Il logo deve essere un'immagine PNG, JPEG o WebP, al massimo di 256 KB."). In
particolare **non** si accetta l'SVG caricato: un SVG è un testo che può contenere script. Anche quando il logo viene
servito, la risposta dice al browser di non indovinare il tipo (`X-Content-Type-Options: nosniff`) e di non eseguire
niente (`Content-Security-Policy: default-src 'none'; …; sandbox`): così anche un SVG di una banca resta solo
un'immagine.

Un conto collegato non accetta un logo caricato (errore 409): il suo logo è quello della banca. La pagina controlla la
dimensione già prima di inviare il file ("Il logo è troppo grande: al massimo 256 KB…"), così non si aspetta per niente.

## Parte D: il nome del conto accanto al movimento

Sotto ogni movimento c'è il nome del conto. Con il logo, il nome lungo è spesso ripetitivo; ma se una banca ha più
conti, il nome lungo serve a distinguerli. La regola (`shortAccountName` in
`frontend/src/app/accounts/accounts-store.ts`):

1. se hai dato un nome al conto (nella pagina Conti), si usa **quello**, sempre;
2. se il conto è **l'unico della sua banca**, si scrive solo la banca: "Tinaba" invece di "Tinaba · Wallet";
3. se la banca ha **più conti**, si scrive il nome per esteso: "Banca Alfa · Conto corrente" e
   "Banca Alfa · Carta di credito", per non confonderli.

I conti si caricano una volta e si condividono tra le pagine (`AccountsStore`); quando cambi qualcosa nella pagina
Conti, i movimenti lo vedono subito.

## Parte E: i test, e la prova nel browser

Backend:

- `LogoImageTest`: il tipo di un'immagine dai primi byte, e l'SVG accettato solo per le banche;
- `LogoServiceTest`, con un database vero: il logo della banca scaricato una volta e servito; una banca senza logo
  riprovata solo dopo una settimana; caricare, rifiutare un file che non è un'immagine, togliere; un conto collegato
  rifiuta il caricamento (409), un conto che non c'è dà 404.

Frontend:

- `accounts-store.spec.ts`: le tre regole della parte D e il logo nell'angolo del cerchio;
- `accounts-page.spec.ts`: il logo caricato per un conto manuale e mostrato accanto al nome; il file troppo grande
  fermato prima dell'invio.

Prova nel browser (dati inventati, telefono): un logo inserito per il conto collegato di "Banca Alfa", un logo
caricato per Tinaba dalla pagina Conti. Nei movimenti il logo compare nell'angolo del cerchio, anche nel cerchio
grande della scheda; accanto ai movimenti di Tinaba c'è solo "Tinaba".

## Parte F: esercizi guidati

1. **Leggi i primi byte.** Prendi un'immagine PNG e guarda i primi byte con
   `python3 -c "print(open('logo.png','rb').read(8).hex(' '))"`. Ritrovi `89 50 4e 47 …` della tabella della parte C?
   Rinomina un file di testo in `.png` e caricalo: che cosa risponde l'app, e perché?
2. **La versione nell'indirizzo.** Carica un logo, apri gli strumenti per sviluppatori del browser (scheda Rete) e
   guarda l'indirizzo dell'immagine. Cambia il logo: l'indirizzo cambia? Che cosa succederebbe senza `?v=`?
3. **Il nome breve.** Aggiungi un secondo conto manuale con la stessa banca di Tinaba. Che cosa diventa il nome
   accanto ai movimenti di Tinaba? Dai un nome al conto nella pagina Conti: che cosa cambia?
