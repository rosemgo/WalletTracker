# Investimenti: quanto hai versato, e quanto ti hanno reso

**Obiettivo:** una scheda **"Investito"** nel Riepilogo e una pagina **Investimenti** con due grafici:
- quanto hai messo negli investimenti (un PAC, un broker, i titoli comprati dalla banca);
- quanto ti hanno reso: cedole, dividendi, interessi.

> Questa guida non ha un numero: è stata scritta mentre un'altra sessione lavorava alla Fase 4 (le guide 44-48 e
> le successive). Senza numero non si scontra con le loro. Il lavoro parte dalla Fase 4 al passo 5 (PIN, nginx,
> backup) e la usa: l'API è protetta dal PIN come tutte le altre.

| Parte | Cosa |
|---|---|
| A | prima di tutto: i movimenti devono avere il tipo giusto |
| B | i calcoli: contare una volta sola |
| C | l'API e la scheda del Riepilogo |
| D | la pagina e i suoi grafici |
| E | che cosa **non** è: il valore di oggi |
| F | i test, e la prova nel browser |
| G | esercizi guidati |

---

## Parte A: prima di tutto, il tipo giusto

L'app conosce già i tipi degli investimenti (`docs/03`). Nessuno conta come spesa o come entrata: sono "tenuti a
parte".

| Tipo | Esempio |
|---|---|
| Versamento investimenti | il bonifico mensile verso il conto del PAC, o verso il broker |
| Prelievo investimenti | soldi che tornano dal conto investimenti al conto corrente |
| Acquisto / Vendita titoli | un ETF comprato su Trade Republic; un BTP comprato dal dossier titoli della banca |
| Cedole e dividendi (`INVESTMENT_INCOME`) | categorie Cedole, Dividendi, Interessi; le regole per ING ("Ced.su", "Div.su") e Trade Republic ci sono già |
| Tasse investimenti | ritenute, imposta di bollo |

Perché un bonifico verso il conto del PAC conti, deve avere il tipo **Versamento investimenti**. Tre modi:

1. **Il conto con il ruolo Investimenti.** In Conti, quello del broker (collegato), oppure un **conto manuale** con il
   suo IBAN se la banca non si può collegare, come Tinaba. Il bonifico che cita quell'IBAN diventa da solo un
   versamento (`ClassificationService`, passo 5: "il movimento cita l'IBAN di un altro tuo conto").
2. **Una regola**: "se il testo contiene TINABA, allora il tipo è Versamento investimenti". Vale per tutti i
   bonifici, passati e futuri, qualunque sia l'importo.
3. **Per un movimento solo**: nella sua scheda, "Conta come".

Se non c'è ancora niente, la pagina Investimenti mostra proprio questi tre modi.

---

## Parte B: i calcoli, contare una volta sola

**Il problema.** 200 € che vanno da ING a Trade Republic e poi in un ETF compaiono **tre volte**:

1. l'uscita da ING (versamento, −200);
2. l'entrata su Trade Republic, l'altra metà dello stesso trasferimento (anche lei "versamento", +200);
3. l'acquisto dell'ETF su Trade Republic (−200).

Sommare tutto darebbe 0, o 400, o 600, secondo come si contano i segni. Sono sempre gli stessi 200 €.

**La regola.** Contano solo i movimenti dei **conti normali**, cioè non quelli con il ruolo Investimenti:

```java
public static BigDecimal contribution(Movement m) {
    if (m.onInvestmentAccount()) {
        return BigDecimal.ZERO;
    }
    return switch (m.type()) {
        case INVESTMENT_DEPOSIT, INVESTMENT_WITHDRAWAL, SECURITIES_BUY, SECURITIES_SELL -> m.amount().negate();
        default -> BigDecimal.ZERO;
    };
}
```

- Un'uscita di −200 verso gli investimenti è **+200** di versato (`negate()`); un rientro di +50 è −50.
- Di quei tre movimenti conta solo il primo: gli altri due sono su un conto di investimento.
- Funziona anche per un conto che **non si può collegare** (Tinaba): lì esiste solo l'uscita da ING, ed è
  esattamente quella che si conta.
- Un BTP comprato **direttamente dal conto corrente** (il dossier titoli della banca) conta anche lui: sono soldi
  che escono dal conto normale verso un investimento.

Il risultato si chiama **versato netto**: quello che è andato, meno quello che è tornato.

**Le rendite** sono più semplici: cedole, dividendi e interessi si sommano così come sono, su qualsiasi conto (un
dividendo annullato è negativo e si toglie da solo). Le tasse sugli investimenti si sommano a parte.

**La linea parte dalla storia.** Il grafico mostra il versato **in tutto** mese per mese, non solo nel periodo
guardato. Il primo mese della linea comprende tutto quello che hai versato prima:

```java
BigDecimal cumulative = movements.stream()
        .filter(m -> m.date().isBefore(first.atDay(1)))
        .map(Investments::contribution)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
```

`reduce` è la "somma" degli stream: parte da zero e aggiunge ogni valore. È lo stesso schema di `Explorer` per
Analisi (`docs/38`): i calcoli in una classe di funzioni pure (`Investments`), che ricevono i movimenti e
restituiscono i numeri. Si provano con test semplici, senza database.

---

## Parte C: l'API e la scheda del Riepilogo

```
GET /api/investments?period=YEAR          (YEAR = 12 mesi, TWO_YEARS = 24, ALL = tutta la storia, max 10 anni)
```

Risponde con:
- il versato: in tutto, quest'anno, questo mese, nel periodo;
- le rendite: quest'anno e nel periodo, più le tasse del periodo;
- un elemento per mese: `contributed`, `cumulative`, `income`, `taxes`;
- le rendite raggruppate per tipo (Cedole, Dividendi…) e per titolo;
- gli ultimi 8 versamenti.

I movimenti di investimento sono pochi (uno o due al mese), quindi la query li legge **tutti**, con conto e categoria
già caricati (`JOIN FETCH`): una query sola, niente query per ogni movimento.

Come per le altre API dalla Fase 4 (`docs/46`), serve la sessione del PIN. Nei test di MockMvc la dà
`IntegrationTestConfiguration`, che parte "come chi ha già inserito il PIN".

**La scheda "Investito"** (`InvestmentsCard`) sta nel Riepilogo, sotto "In arrivo":
- **Versato quest'anno** e **Rendite quest'anno**;
- un piccolo grafico del versato degli ultimi 12 mesi;
- tutta la scheda è un link alla pagina.

Si carica da sola, perché parla dell'anno e non del mese che stai guardando. **Non compare** se non c'è niente: chi
non investe non deve vedere una scheda vuota. Se la richiesta fallisce, il Riepilogo funziona lo stesso senza la
scheda.

Il piccolo grafico usa la stessa curva del grafico grande (`smoothPath`). Due dettagli SVG:
- `preserveAspectRatio="none"` lo stira su tutta la larghezza della scheda;
- `vector-effect: non-scaling-stroke` tiene la linea dello stesso spessore anche se il disegno è stirato.

---

## Parte D: la pagina e i suoi grafici

La pagina `/investimenti` si apre dalla scheda, e anche dall'elenco "Altro" (sul PC, dalla colonna).

**In alto**: 12 mesi · 2 anni · Tutto.

**La scheda grande: il versato.**
- La cifra grande dice il **versato in totale**, con "3.000 € quest'anno · 200 € questo mese".
- Sotto, la **linea** che cresce mese per mese (`TrendChart`). È la sorella del grafico del Riepilogo (`docs/41`,
  parte G): stessa curva, stessa sfumatura, stessa linea che si disegna, stesso scorrimento con il dito, il mouse o
  le frecce. Scorrendo, la cifra grande diventa "Versato fino a febbraio 2026".
- Cambia la scala: un punto per **mese** invece che per giorno, e sotto cinque mesi distribuiti ("nov … ott").
- Gli stili sono gli stessi (le classi `month-chart…`): nessuno stile nuovo per la linea.

**La scheda delle rendite.**
- La cifra del periodo, e le tasse dello stesso periodo.
- Le **barre** mese per mese, con il componente di Analisi (`BarChart`). In evidenza c'è l'**ultima rendita
  incassata**, non il mese in corso: quasi sempre il mese in corso è a zero, e nessuna barra risaltava.
- Due elenchi: **per tipo** (Cedole, Dividendi, Interessi) e **da dove** (il titolo o l'emittente).

**Gli ultimi versamenti**, con la stessa riga dei Movimenti (iniziale colorata, nome leggibile), e il link al
dettaglio.

**La modalità discreta** (`docs/42`) vale anche qui: le cifre passano dalla pipe `money` e da `privacy.hide()`, e
anche le scritte sull'asse della linea sono nascoste.

Un dettaglio trovato provando: "CED.SU BTP ITALIA" diventava "Ced.su Btp Italia". Ora `readable` tiene maiuscole
anche le sigle dei titoli: BTP, BOT, CCT, CTZ, ETF, ETC.

---

## Parte E: che cosa **non** è, il valore di oggi

"Versato" sono **i soldi messi**, non quanto valgono adesso. Se il PAC è salito del 10%, la pagina non lo sa:
- per saperlo servirebbero le **quotazioni** dei titoli (un servizio di prezzi);
- e le **quantità** possedute (le posizioni del conto titoli).

L'app non ha né le une né le altre. La pagina lo dice sotto il grafico: "non quanto valgono oggi". Un grafico che
sembrasse un rendimento sarebbe un dato falso. È un possibile progetto futuro, non un difetto.

---

## Parte F: i test, e la prova nel browser

Backend, 363 test (compresi quelli della Fase 4):

- `InvestmentsTest` (puri):
  - i tre movimenti di un versamento verso il broker contano **una volta**;
  - un prelievo toglie; un acquisto dal conto corrente conta; una spesa e una cedola non sono versamenti;
  - la linea parte dalla storia prima del periodo;
  - le rendite raggruppate dalla più alta, con "Altre rendite" per quelle senza categoria.
- `InvestmentServiceTest` (database vero):
  - un PAC su un conto non collegato, un versamento verso il broker con le sue due metà e l'acquisto dell'ETF, un
    prelievo, cedole, un dividendo, il bollo;
  - il totale (3.100 €), l'anno, il mese, i 12 punti della linea, i periodi "Tutto" e "2 anni";
  - l'API che rifiuta una valuta o un periodo sbagliati.

Frontend, 217 test: la pagina (cifre, linea, barre, elenchi, l'ultimo versamento che porta al dettaglio), i
periodi, lo scorrimento della linea con la tastiera, la pagina vuota che spiega che cosa fare, la modalità discreta,
la scheda del Riepilogo che compare e che sparisce.

Nel browser (telefono, PC, notte), con un PAC inventato da 200 € al mese per 20 mesi, un ETF da 1.000 € comprato
dal conto corrente, due cedole di un BTP, un dividendo e il bollo:

- **i numeri, controllati anche a mano**: versato 5.000 € (20 × 200 + 1.000), quest'anno 3.000 €, rendite
  dell'anno 49,65 € (31,25 + 18,40);
- la scheda nel Riepilogo porta alla pagina; scorrendo la linea, "Versato fino a febbraio 2026: 3.400 €";
- nessuna pagina più larga dello schermo.

Tre ritocchi trovati così:
1. l'etichetta "Cedole, dividendi, interessi" nella scheda andava su due righe e disallineava le cifre: ora è
   "Rendite quest'anno";
2. la barra in evidenza era quella del mese in corso, a zero: ora è l'ultima rendita;
3. le sigle dei titoli (Parte D).

---

## Parte G: esercizi guidati

1. **Il PAC di Tinaba.** Crea il conto manuale "Tinaba" con il ruolo Investimenti e il suo IBAN, oppure la regola
   "TINABA". Apri un bonifico verso Tinaba: che tipo ha adesso? E la scheda "Investito"?
2. **Senza contare due volte.** In `InvestmentsTest`, togli il controllo `onInvestmentAccount()` da `contribution`.
   Quale test fallisce, e con quale numero? Perché?
3. **Il rendimento delle cedole.** Aggiungi alla pagina "rendite / versato" in percentuale, per l'anno. Perché non è
   il rendimento vero del portafoglio (pensa ai soldi versati a dicembre)?
