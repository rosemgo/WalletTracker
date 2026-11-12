# Enable Banking in modalità ristretta: un conto nuovo va prima nella lista

Fuori dalla numerazione, dopo la 1.2.7 (nella 1.3.0). Spiega perché un conto autorizzato in banca può non arrivare
nell'app, che cosa fare, e che cosa l'app ora dice e controlla.

---

## Parte A: il caso, con i conti veri

Un conto nuovo di Fineco (un "Credit Lombard", aperto dopo gli altri) non compariva nell'app:

| Prova | Che cosa è successo |
|---|---|
| dall'app, conto principale + Credit Lombard autorizzati in Fineco | "FinecoBank collegata: **1 conto**": solo il principale |
| dall'app, solo il Credit Lombard | "**0 conti**" |
| dal pannello di Enable Banking, "Link accounts" con il Credit Lombard | collegato |
| poi, di nuovo dall'app, tutti e due | "FinecoBank collegata: **2 conti**", il Credit Lombard con i suoi movimenti |

L'app non aveva sbagliato niente: nel database nessun conto era stato fuso o sovrascritto. La causa è in come funziona
l'applicazione Enable Banking che la guida fa creare.

## Parte B: che cos'è la modalità ristretta

Un'applicazione Enable Banking "Production" si attiva in due modi (documentazione di Enable Banking, *Control Panel* →
*Activation of Production Applications*):

| Modo | Come | Che cosa legge |
|---|---|---|
| **senza restrizioni** (*Unrestricted*) | contratto con Enable Banking, verifica dell'azienda (KYB), revisione manuale, costi | i conti di chiunque autorizzi l'applicazione |
| **ristretta** (*Restricted, account linking*) | colleghi i **tuoi** conti dal pannello ("Link accounts"); gratuita, per uso personale | **solo i conti della lista** dell'applicazione |

WalletTracker è self-hosted: ognuno crea **la sua** applicazione, in modalità ristretta (`docs/02`, passo 3).

**Che cosa succede ai conti fuori dalla lista** (FAQ di Enable Banking, *"Why does my restricted application receive an
empty list of accounts after successful authorisation?"*, in sintesi): chiunque può fare l'autorizzazione con qualunque
banca, ma Enable Banking **confronta i conti ricevuti dalla banca con quelli della lista** e restituisce **solo quelli
presenti**; gli altri vengono **tolti dalla risposta**. Se nessuno è nella lista, la risposta è **vuota**. Un errore
comune, scrive la FAQ, è pensare che collegare un conto attivi l'applicazione per tutti i conti.

La pagina *Whitelisting own accounts* aggiunge che il collegamento dal pannello **non** sostituisce l'autorizzazione
dall'app ("Access needs to be authorized through API"), anche per lo stesso conto: sono due passaggi.

Per questo il messaggio era "1 conto" e poi "0 conti": il Credit Lombard non era nella lista, ed Enable Banking lo
toglieva dalla risposta.

## Parte C: come aggiungere un conto nuovo

Una volta sola per ogni conto:

1. apri il pannello di Enable Banking (`enablebanking.com/cp/applications`) e accedi;
2. nella tua applicazione premi **Link accounts**;
3. scegli il paese e la banca, accedi alla banca e seleziona i conti da aggiungere;
4. torna in WalletTracker, **Collega una banca**, la stessa banca, e seleziona **gli stessi conti**.

Dopo, tutto il resto lo fa l'app: aggiornamenti, rinnovo del consenso, saldi, classificazione.

## Parte D: che cosa fa l'app adesso

**Lo dice prima.**

- *Impostazioni → Enable Banking*: il passo dell'attivazione dice di collegare **tutti i conti** che si vogliono leggere,
  e di ripeterlo per un conto nuovo.
- *Collega una banca*: un primo punto "La prima volta per un conto: aggiungilo alla tua applicazione Enable Banking", e
  una sezione da aprire con i quattro passi (il componente `link/LinkedAccountsSteps`, con il link al pannello).

**Lo dice dopo.**

- *La pagina di ritorno* con **0 conti**: non più "collegata: 0 conti", ma un riquadro "**nessun conto passato da
  Enable Banking**", con la spiegazione e i passi. In questo caso il backend **non salva** un collegamento vuoto
  (`BankLinkService.complete`): non servirebbe a niente.
- con dei conti collegati, una riga da aprire "**Manca un conto che hai autorizzato?**" con gli stessi passi. L'app non
  può sapere quanti conti hai selezionato in banca: Enable Banking toglie quelli fuori dalla lista prima di risponderle.

**Lo scrive nei log** (`ConnectionService.saveSession`): a ogni collegamento, quanti conti ha passato Enable Banking e,
per ognuno, **solo le ultime quattro cifre** dell'IBAN, la valuta e se c'è l'impronta del conto. Per esempio
`FinecoBank: Enable Banking ha passato 2 conti: ··0002 EUR (impronta), ··0003 EUR (impronta)`. Basta per capire un
collegamento andato storto, senza dati completi (`docs/17`, n. 54).

**Non fonde mai due conti diversi.** Ogni conto ha una chiave stabile, per riconoscerlo anche dopo un nuovo consenso:
prima l'impronta di Enable Banking (`identification_hash`), poi banca + IBAN + valuta. Se due conti avessero la stessa
impronta, il secondo veniva preso per il primo e ne **sovrascriveva l'identificativo**: da quel momento il primo conto
avrebbe scaricato i movimenti del secondo. Nel caso vero non è successo, ma la protezione costa poco:

- se l'impronta è già di **un altro conto della stessa sessione**,
- oppure è di **un conto salvato con un altro IBAN**,

la chiave diventa banca + IBAN + valuta (e, se anche quella fosse già usata, banca + identificativo della sessione), e
il conto si tratta come nuovo. Due IBAN si confrontano senza spazi e senza badare alle maiuscole; se uno dei due manca
(una carta), non si può dire che siano diversi, e vale l'impronta come prima.

## Parte E: l'app può sapere se l'applicazione è in modalità ristretta?

No, non direttamente. Enable Banking ha un servizio che descrive l'applicazione (`GET /application`), ma dice solo il
nome, l'ambiente (sandbox o produzione), gli indirizzi di ritorno, i paesi e i servizi, e se è **attiva**; **non** se è
ristretta. Un'applicazione ristretta e una senza restrizioni sono tutte e due "attive".

Si vede solo dal risultato: zero conti dopo un consenso riuscito. Per questo l'app mostra i passi **sempre** (in una
sezione da aprire, che non disturba) e l'avviso forte **solo** quando i conti sono zero.

## Parte F: e senza restrizioni?

Cambierebbe il modo di pensare l'app, non solo un'impostazione. Con un'applicazione senza restrizioni ha senso un
**servizio unico su un server**, a cui più persone accedono con il loro account: ognuno autorizza le proprie banche e
non deve creare niente su Enable Banking. Ma servirebbero:

- **un contratto** con Enable Banking, la verifica dell'azienda (KYB) e i costi;
- **gli account degli utenti**: registrazione, accesso sicuro, recupero della password, la separazione dei dati di
  ognuno in tutto il backend;
- **gli obblighi verso gli utenti**: termini del servizio, informativa sulla privacy, il titolare del trattamento dei
  dati (GDPR), la custodia dei dati finanziari di altre persone;
- **un server sempre acceso** e la sua sicurezza.

WalletTracker è pensato al contrario: ognuno lo installa sul **proprio** computer, con la **propria** applicazione
Enable Banking, e i dati non escono da casa. La modalità ristretta è proprio quella giusta per questo uso; il prezzo è
il passaggio della Parte C, una volta per conto.

## Parte G: le prove

- `BankLinkServiceTest`: con zero conti da Enable Banking il risultato è 0 e non si salva nessun collegamento.
- `ConnectionServiceTest`: due conti con la stessa impronta e IBAN diversi restano due conti; un conto salvato non
  viene preso da un altro IBAN con la sua impronta, e resta com'era; il log mostra solo le ultime quattro cifre.
- `callback-page.spec.ts`: con zero conti il riquadro, la modalità ristretta, i quattro passi e il link al pannello, e
  niente "collegata" né "ruolo dei nuovi conti"; con dei conti, la riga "Manca un conto?".
- `link-page.spec.ts`: il primo punto e la sezione con i passi.
- Nel browser: le due pagine su PC e telefono, tema scuro.
