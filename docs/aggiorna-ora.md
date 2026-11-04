# "Aggiorna ora": che cosa è arrivato, conto per conto

Fuori dalla numerazione, come `docs/grafici-tocco.md`. Nasce da una domanda dell'utente: alle 13 aveva fatto delle
spese con la carta, alle 15 non le vedeva nell'app, e premendo **Aggiorna ora** (pagina Conti) continuavano a non
esserci. L'app è rotta, o è la banca a non averle mandate?

## Che cosa fa "Aggiorna ora"

Legge **subito** tutti i conti collegati con Enable Banking: i movimenti, il saldo e, per le carte di credito, il
plafond (`docs/08`, `docs/saldi.md`). Siccome sei tu a premerlo, la richiesta porta gli header "l'utente è presente"
(PSU): la banca non la conta tra le circa 4 letture al giorno permesse in automatico.

L'app salva tutto quello che la banca le dà: i movimenti **contabilizzati** e quelli **in attesa** (`docs/06`, regola
4), che si sostituiscono a ogni lettura e diventano definitivi quando la banca li contabilizza.

## Perché le spese di oggi possono mancare

Un pagamento con carta, all'inizio, è solo un'**autorizzazione**: la banca "prenota" i soldi, e contabilizza il
movimento di solito 1–2 giorni lavorativi dopo. L'app della banca spesso mostra subito le autorizzazioni; il canale
aperto alle app esterne (PSD2, quello che usa Enable Banking) non sempre le passa. Se la banca non le dà, nessuna app
esterna può vederle prima: compariranno da sole alla prima lettura dopo la contabilizzazione.

Anche i conti **manuali** (Tinaba, un file CSV, `docs/25`) non si leggono con Aggiorna ora: i loro movimenti entrano
solo importando il file.

## Che cosa è cambiato

Prima, dopo Aggiorna ora, la pagina diceva solo "Aggiornati 3 conti": non si capiva se fosse arrivato qualcosa. Per
saperlo bisognava leggere il log del backend (`Conto corrente: 0 nuovi, 45 già presenti, 0 in attesa`).

Ora la pagina mostra, subito sotto il pulsante, **una riga per conto**:

- `Conto corrente: 2 movimenti nuovi, 1 in attesa`;
- `Carta di credito: nessun movimento nuovo`;
- `Conto titoli: non aggiornato (troppe richieste alla banca)`, con il motivo dell'errore.

Se nessun conto letto ha portato niente, né nuovo né in attesa, sotto c'è anche la spiegazione della parte precedente:
la banca non ha mandato niente, e le spese di oggi arriveranno quando saranno contabilizzate.

Le righe di "nuovi" contano i movimenti contabilizzati salvati per la prima volta; "in attesa" conta quelli in attesa
che la banca dà **adesso** (sono sostituiti a ogni lettura, quindi non sono per forza nuovi).

## Come è fatto

- **Backend.** Il riepilogo di un giro (`SyncService.Round`) aveva solo tre numeri: aggiornati, con errori, non
  ancora da leggere. Ora ha anche l'elenco `accounts`, un `AccountOutcome` per ogni conto letto: `accountId`,
  `inserted`, `pending` ed `error` (la stessa frase salvata nel conto e mostrata nella tabella). I numeri vengono da
  `ImportResult`, che li calcolava già per il log.
- **Frontend.** `POST /api/sync` (`SyncRound` in `models.ts`) porta l'elenco; la pagina Conti trasforma ogni esito in
  una frase (`outcomeLine` in `accounts-page.ts`), con il nome del conto come nella tabella.

## I test

- `SyncServiceTest`: in un giro con un conto che dà un errore e uno che dà un movimento contabilizzato e uno in
  attesa, l'esito di ognuno; nel giro dopo, niente di nuovo e lo stesso movimento ancora in attesa.
- `accounts-page.spec.ts`: le righe dopo Aggiorna ora (movimenti nuovi, in attesa, errore); la spiegazione quando non
  è arrivato niente, e solo allora; le frasi di `outcomeLine`.

Prova nel browser, a misura di telefono: con il backend di prova (Enable Banking non configurato) la riga dell'errore;
con due risposte finte, "nessun movimento nuovo" con la spiegazione, e "2 movimenti nuovi, 1 in attesa" senza.
