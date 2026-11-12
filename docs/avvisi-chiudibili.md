# Gli avvisi si chiudono

Fuori dalla numerazione, dopo la 1.3.0. Ogni avviso dell'app ha una **crocetta** a destra: chi l'ha letto lo chiude.

## Quali avvisi

| Avviso | Dove | Chiuso fino a quando |
|---|---|---|
| consenso in scadenza, consenso scaduto, aggiornamento non riuscito, budget superati | Riepilogo (`AlertsPanel`) | la situazione cambia (sotto) |
| "si vedono solo i bonifici" | Investimenti | si aggiunge o cambia un conto nell'elenco |
| il backup ha un problema | striscia in cima | il problema cambia (un altro testo) |
| Enable Banking non è configurato | striscia in cima | sempre (le Impostazioni lo dicono comunque) |
| senza rete, versione pronta da caricare | striscia in cima | la prossima apertura dell'app (sono stati passeggeri) |
| versione nuova pubblicata | striscia in cima | la prossima apertura dell'app (come prima: docs/50, parte J) |

I riquadri che spiegano come fare una cosa dentro una pagina (le istruzioni di Collega una banca, l'avviso dopo aver
salvato una regola) non sono avvisi: restano.

## Come si ricorda un avviso chiuso

`ui/dismissed-notices.ts` (`DismissedNotices`) tiene le **chiavi** degli avvisi chiusi, nel `localStorage` del browser
(vale per quel dispositivo, come la modalità discreta). La chiave **descrive la situazione**, non l'avviso in generale:

- consenso in scadenza: banca, data di scadenza e "fase". Gli ultimi 3 giorni sono una fase nuova: un promemoria chiuso
  due settimane prima ricompare a ridosso della scadenza;
- consenso scaduto: banca e data (ricollegando, la data cambia);
- aggiornamento non riuscito: conto e motivo;
- budget superati: mese e categorie (una categoria in più, o il mese dopo, è un avviso nuovo);
- "solo bonifici": i nomi dei conti;
- backup: il testo dell'avviso.

Così chiudere un avviso non nasconde **per sempre** un problema nuovo. Si ricordano al massimo 200 chiavi; se il
browser non permette di ricordare (navigazione privata), l'avviso resta chiuso fino alla prossima apertura.

Perché nel browser e non nel database: chiudere un avviso è una comodità di chi guarda, come la modalità discreta. Sul
telefono si chiude a parte.

## La crocetta

`ui/close-button.ts` (`app-close-button`): un pulsante "×" di 32 px, con un nome per i lettori di schermo che dice
quale avviso chiude ("Chiudi l'avviso dei backup"). Il riquadro ha la classe `dismissible`: testo a sinistra, crocetta
a destra, anche sul telefono.

## Le prove

- `dismissed-notices.spec.ts`: ricordato dopo la riapertura, "per ora" dimenticato, al massimo 200 chiavi, un valore
  rovinato ignorato.
- `alerts-panel.spec.ts`: un avviso si chiude e resta chiuso, una categoria in più lo fa ricomparire; le chiavi
  (le fasi del consenso, il mese del budget).
- `investments.spec.ts`: l'avviso dei soli bonifici si chiude, resta chiuso, ricompare con un conto in più.
- `app.spec.ts`: le strisce dei backup e della configurazione si chiudono e restano chiuse; un altro problema dei backup
  ricompare.
- Nel browser (dati finti): Riepilogo e Investimenti su PC e telefono, tema scuro; chiusi e ricaricati, restano chiusi
  solo quelli chiusi; nessuna pagina più larga dello schermo.
