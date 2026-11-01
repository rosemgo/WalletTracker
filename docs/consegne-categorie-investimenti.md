# Passaggio di consegne: categorie dei movimenti di investimento e cashback di Tinaba

File per continuare in una sessione nuova la discussione nata dopo `docs/tinaba.md`. Leggere prima:
`docs/investimenti.md` (il "versato", i ruoli dei conti, parte H sulle priorità delle regole), `docs/tinaba.md`
(modo senza segno, regole `V31`), `docs/19` (il rimborso come spesa positiva), `docs/25` (importazione CSV).

**Regole che restano:** niente dati personali nel repository (solo dati inventati: Banca Alfa, MARIO ROSSI,
IBAN IT00X…); non modificare migrazioni già applicate (`V31` compresa: si cambia con una migrazione nuova);
prima di scegliere il numero della migrazione, guardare **tutti** i rami remoti (la sessione della Fase 4 aggiunge
migrazioni in parallelo); test, prova nel browser e guida in italiano in `docs/`.

> **Decisioni prese (sessione successiva).** Domanda A: il principio è confermato, nessun cambio nel codice.
> Domanda B: né l'opzione 2 né la 3. Il cashback è un bonus della promozione, quindi diventa **Entrata** in
> "Rimborsi fiscali e bonus" (categoria che c'era già); la ricarica Power resta una spesa in "Costi bancari".
> Fatto con `V33` (la `V32` era già stata usata per le regole "tutte le parole"). Tutto in `docs/tinaba.md`, parte J.
> Il resto del file è la discussione com'era prima della decisione.

---

## 1. Dove siamo

L'importazione del CSV di Tinaba funziona (commit `b984b62`). Oggi i movimenti del conto Tinaba (ruolo
**Investimenti**) diventano:

| Movimento | Tipo | Categoria | Da dove |
|---|---|---|---|
| bonifico ricevuto dal conto corrente | Versamento investimenti (giroconto accoppiato) | nessuna | accoppiamento |
| Nuovo versamento Robo advisor | Acquisto titoli | nessuna | regola `V31` |
| Ricarica effettuata Tinaba Power | Spesa | Costi bancari | regola `V31` |
| Tinaba Cashback | Spesa **positiva** (rimborso) | Costi bancari | regola `V31` |

Le parole "ricevuto, ricevuta" sono una costante (`ColumnGuesser.TINABA_INCOME_WORDS`), scelte leggendo le
descrizioni del file; non sono estratte dal file. La pagina Importa le propone e si possono cambiare.

---

## 2. Domanda A: le categorie servono anche fuori da entrate e spese?

**Com'è oggi.** `transactions-page.ts`, `metaOf()`: "senza categoria" compare **solo** per Spesa ed Entrata; per gli
altri tipi la riga mostra il tipo ("acquisto titoli", "versamento investimenti", "trasferimento interno"). Le
categorie di tipo `INVESTMENT` esistono (`V12`: Cedole, Dividendi, Interessi, Ritenute, Capital gain, Tobin tax,
Imposta di bollo) e si usano per rendite e tasse (regole `V25`, `V26`; "Per tipo" nella pagina Investimenti).

**Il principio proposto (da confermare con l'utente):** la categoria risponde a "per che cosa?". Serve dove c'è
una risposta che il tipo non dà già:

| Tipo | Categoria | Perché |
|---|---|---|
| Spesa, Entrata | sì, sempre | budget, Analisi, abbonamenti |
| Rendite e tasse degli investimenti | sì (Cedole, Dividendi, Imposta di bollo…) | "Per tipo" nella pagina Investimenti |
| Giroconti, versamenti, prelievi, acquisti e vendite di titoli | no | il tipo dice già tutto; "da dove/verso dove" lo dice il conto |

Quindi i movimenti Tinaba "senza categoria" sono **giusti** quando sono versamenti o acquisti. Nello screenshot
dell'utente l'unico vero "senza categoria" era un bonifico ricevuto il 28 ottobre rimasto **Entrata**: non si è
accoppiato perché il bonifico in uscita del conto corrente non era ancora arrivato. Sincronizzazione e importazione
rifanno `classifyAll()`, quindi dovrebbe accoppiarsi da solo all'arrivo dell'altro: **da verificare** con un test
(prima il CSV di Tinaba, poi il movimento del conto corrente).

Eventuale ritocco UI (piccolo): per chiarezza, nell'elenco degli investimenti dire "versamento" e non lasciare dubbi;
oggi è già così. Nessun cambio necessario se l'utente conferma il principio.

---

## 3. Domanda B: il cashback in "Costi bancari" ha senso?

**Il problema della scelta attuale.** Un rimborso ha senso se rimborsa una spesa **che si vede**. Qui:

- le commissioni del Robo advisor (circa l'1% l'anno sul capitale, secondo le recensioni) sono prese **dentro** il
  portafoglio: nel CSV del conto non compaiono;
- il cashback ("Cashback Power commissioni servizio investimenti") restituisce quelle commissioni, usando il credito
  Power (si compra con le ricariche; secondo le recensioni una ricarica copre commissioni per il doppio del suo
  valore — da confermare sul sito di Tinaba);
- risultato: in un anno, con dati simili a quelli dell'utente, "Costi bancari" ha −50 € di ricarica e +167 € di
  rimborsi: **una categoria di spesa che va in attivo**. Nel Budget e in Analisi è strano (spese negative) e mescola
  i costi del conto corrente con quelli dell'investimento.

**Opzioni**

| | Ricarica Power | Cashback | Pro | Contro |
|---|---|---|---|---|
| 1. com'è | Spesa, Costi bancari | rimborso, Costi bancari | nessun lavoro | categoria in attivo, costi mescolati |
| 2. categoria propria | Spesa, nuova categoria **"Costi degli investimenti"** (EXPENSE) | rimborso, stessa categoria | costi del conto corrente puliti; si vede il netto del servizio | la categoria resta in attivo |
| 3. **(proposta)** | Spesa, "Costi degli investimenti" | Rendita degli investimenti (`INVESTMENT_INCOME`), nuova categoria `INVESTMENT` **"Rimborso commissioni"** | economicamente giusto: le commissioni escono dal portafoglio, il rimborso torna da lì; nessuna spesa negativa | l'utente aveva detto "non è una rendita": va mostrato **separato** da cedole e dividendi |

**Perché la 3.** Il cashback non è un guadagno del mercato, ma è denaro che l'investimento restituisce: alza il
rendimento netto del Robo advisor. Nella pagina Investimenti può stare in una riga a parte ("Rimborsi delle
commissioni"), non sommato a "Cedole, dividendi e interessi". La ricarica Power invece è un costo vero, pagato dal
conto: resta una spesa.

**Da chiedere all'utente prima di scrivere codice:** opzione 2 o 3? Se 3: la riga dei rimborsi va nella cifra delle
rendite o solo mostrata accanto?

**Lavoro, se si sceglie la 3**

1. Migrazione nuova (numero libero su tutti i rami, oggi almeno `V32`):
   - categorie "Costi degli investimenti" (EXPENSE) e "Rimborso commissioni" (INVESTMENT), `builtin`, con un
     `sort_order` vicino alle altre;
   - aggiornare le due regole predefinite di `V31` per nome (`'Credito Power (Tinaba)'`,
     `'Cashback delle commissioni (Tinaba)'`): tipo e categoria nuovi. Solo se l'utente non le ha modificate?
     Valutare (per esempio aggiornare solo se tipo e categoria sono ancora quelli di `V31`).
   - i movimenti già classificati si riallineano con `classifyAll()` al prossimo avvio o import; verificare che
     un movimento con categoria scelta a mano non venga toccato.
2. `InvestmentService` / `Investments`: separare i rimborsi delle commissioni dalle rendite (per categoria o per una
   regola chiara), con test in `InvestmentsTest`.
3. Pagina Investimenti: la riga "Rimborsi delle commissioni"; spec.
4. Test end-to-end in `CsvImportControllerTest.theTinabaFileGetsTheSignsAndTheTypesRight` aggiornato.
5. Guida: una parte nuova in `docs/tinaba.md` (o un file nuovo non numerato) e il principio della Domanda A.

---

## 4. Cose notate da tenere d'occhio

- **Parole delle entrate.** Se Tinaba introduce un'entrata scritta senza "ricevut…" (per esempio interessi di un
  conto deposito), con il modo senza segno diventa un'uscita. Il controllo pratico: confrontare il saldo con quello
  del PDF mensile. Valutare se aggiungere parole o un avviso quando il saldo calcolato va sotto zero.
- **Esercente dei giroconti verso sé stessi** (piano già descritto nella conversazione precedente, dopo la Fase 4):
  chiave per conto o IBAN di destinazione quando il beneficiario è il proprietario.
