# I collegamenti tra le pagine: da un riepilogo ai suoi movimenti

Fuori dalla numerazione, come `docs/saldi.md`. Nasce da una segnalazione dell'utente: nel Riepilogo, toccando "Senza
categoria" in "Dove vanno", non succedeva niente. Le altre categorie portano alla loro pagina; "Senza categoria" non è
una categoria (non ha un id) e la riga restava un testo.

## Il principio

Ogni elenco che **riassume dei movimenti** (una categoria, un esercente, un conto, un titolo) deve portare a **quei
movimenti**. Il posto per guardarli è Movimenti, che li prende dall'indirizzo: `type`, `text`, `categoryId`,
`uncategorized`, `from`, `to` (`docs/37`, `docs/43`) e, da qui, anche **`accountId`**.

## Che cosa porta dove

| Pagina | Che cosa si tocca | Dove porta |
|---|---|---|
| Riepilogo | **"Senza categoria"** in "Dove vanno" | Movimenti: spese senza categoria del mese (fino a oggi) |
| Riepilogo | "In arrivo nei prossimi 7 giorni" | Abbonamenti |
| Analisi | una riga **per esercente** | Movimenti: quell'esercente, nel periodo della barra (spese o entrate) |
| Analisi | una riga **per conto** | Movimenti: quel conto, nel periodo della barra |
| Categoria | un esercente di "Dove" | Movimenti: quell'esercente in quella categoria, nel mese |
| Investimenti | una riga di "Per tipo" (Cedole, Dividendi...) | Movimenti: rendite di quel tipo nel periodo; "Altre rendite": quelle senza categoria |
| Investimenti | una riga di "Da dove" (un titolo) | Movimenti: quel titolo nel periodo, rendite **e** ritenute |
| Investimenti | una riga di "Da dove" che è un conto | Movimenti: quelle rendite di quel conto (vedi sotto) |
| Investimenti | una riga di "Altre tasse" | Movimenti: tasse di quella categoria nel periodo |
| Abbonamenti | il nome di un abbonamento | Movimenti: i suoi pagamenti (le parole dell'esercente) |
| Conti | "vedi i movimenti" | Movimenti: quel conto |
| Categorie | "42 movimenti" | Movimenti: quella categoria |

Le righe che c'erano già (le categorie del Riepilogo e di Analisi, "Senza categoria" di Analisi, i budget, i
movimenti dei vari elenchi) non cambiano.

## Alcuni dettagli

- **Le parole di un esercente.** `merchantSearch` (ora in `frontend/src/app/transactions/merchant-search.ts`, prima
  dentro la scheda del movimento) trasforma la chiave "NETFLIX COM" in "netflix com": la ricerca vuole tutte le parole,
  in qualunque punto del testo, al massimo 10.
- **"Per tipo" cerca il nome della categoria**: la ricerca di Movimenti guarda anche la categoria (`TransactionSearch`),
  quindi "cedole" con il tipo Cedole, dividendi e interessi dà proprio le cedole.
- **"Da dove" di un titolo non filtra il tipo**: così si vedono la cedola e la sua ritenuta, cioè il netto della riga.
- **Una riga "conto" di "Da dove"** (le rendite senza il nome di un titolo, `docs/investimenti.md` parte L, come gli
  interessi di Trade Republic). Con il solo conto si vedevano anche le altre rendite del conto (un dividendo ACME, che
  ha la sua riga). Ora l'API dà, per queste righe, anche le parole fisse del testo (`Investments.searchWords`:
  "interest payment for payout collection", senza il codice che cambia ogni mese), e il link cerca il conto **e**
  quelle parole.
- **`accountId` nell'indirizzo di Movimenti**: imposta il filtro del conto, come il menu.

## I test, e la prova nel browser

Frontend: un test per ogni collegamento nuovo (Riepilogo, Analisi per esercente, pagina di una categoria,
Investimenti, Abbonamenti, Conti, Categorie) e per `accountId` in Movimenti. Backend:
`InvestmentServiceTest.fromWhereUsesTheWholeNameOfTheBondOrTheAccount` (la riga del conto ha conto e parole),
`InvestmentsTest.theSearchWordsAreTheFixedPartOfTheText`.

Nel browser, con dati inventati, toccando davvero e guardando che cosa mostra Movimenti:

| Si tocca | Movimenti mostra |
|---|---|
| Riepilogo, "Senza categoria" | le due spese di novembre senza categoria |
| Investimenti, "Romania-7OT45 6,5%" | la cedola e la sua ritenuta |
| Investimenti, "Trade Republic · Conto titoli" | i due interessi (prima anche il dividendo ACME: corretto) |
| Investimenti, "Cedole" | le 5 cedole |
| Conti, Trade Republic, "vedi i movimenti" | i 9 movimenti del conto |
| Analisi per esercente, "Pizzeria da Gino" | la pizzeria della settimana |
