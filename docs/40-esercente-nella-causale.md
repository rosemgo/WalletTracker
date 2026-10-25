# 40. L'esercente scritto nella causale

**Il difetto:** nella scheda di un bonifico ricevuto (un rimborso), la sezione "Lo stesso esercente" mostrava
altri bonifici di mittenti diversi, compresi gli stipendi. Il pannello dei suggerimenti proponeva di dare la
categoria del rimborso ("Rimborsi fiscali e bonus") a tutti. Il programma non aveva capito chi aveva fatto il
bonifico.

| Parte | Cosa |
|---|---|
| A | la diagnosi: la chiave dell'esercente tagliata a 60 caratteri |
| B | la correzione: il nome dopo un'etichetta |
| C | l'espressione regolare, pezzo per pezzo |
| D | le chiavi già salvate: la migrazione V28 |
| E | il nome da mostrare: elenco e scheda |
| F | un regalo per la privacy: il debitore non va più all'IA |
| G | i test, e la prova nel browser |
| H | esercizi guidati |

---

## Parte A: la diagnosi

"Lo stesso esercente" lo decide la **chiave dell'esercente** (`MerchantKey`, `docs/22` e `docs/26`, sezione 6):

1. il testo: la controparte, se c'è e non è il titolare; altrimenti la **causale**;
2. maiuscole, solo lettere e cifre;
3. via le parole che contengono cifre;
4. al massimo **60 caratteri**.

Alcune banche, per i bonifici in arrivo, non riempiono la controparte: chi paga è scritto **dentro la causale**,
in fondo. Una causale così (inventata, ma con la stessa forma di quelle vere):

```
Bonifico N. 00000000000000000000000001IT BIC Ordinante ALFAITMMXXX Codifica Ordinante
IT00X0000000000000000000001 Anagrafica Ordinante ACME SRL Note: Rimborso
```

Seguiamo la regola: via il numero del bonifico e l'IBAN (contengono cifre), e restano le parole

```
BONIFICO N BIC ORDINANTE ALFAITMMXXX CODIFICA ORDINANTE ANAGRAFICA ORDINANTE ACME SRL NOTE RIMBORSO
```

che con il taglio a 60 caratteri diventano

```
BONIFICO N BIC ORDINANTE ALFAITMMXXX CODIFICA ORDINANTE ANAG
```

Il nome del mittente sta **oltre il taglio**. Rimane solo il codice BIC della **banca** di chi paga: tutti i
bonifici che arrivano da clienti della stessa banca hanno la stessa chiave, qualunque sia il mittente. Era
proprio il testo che si vedeva nella ricerca dopo "Tutti i movimenti di questo esercente": 60 caratteri esatti.

Gli addebiti diretti SEPA (SDD) hanno lo stesso schema:

```
Addebito SDD CORE Scad. 05/10/2026 Imp. 45,10 Creditor id. IT00ZZZ0000000000 GAMMA LUCE E GAS S.P.A.
Id Mandato 0000000001 Debitore MARIO ROSSI
```

Lì il nome del creditore entrava ancora nei 60 caratteri, ma per caso: un creditore dal nome lungo avrebbe avuto lo
stesso problema.

**La lezione:** il taglio a 60 caratteri presume che la parte che distingue un esercente stia **all'inizio** del
testo. Per i pagamenti con carta è vero ("PAGAMENTO CARTA DEL ALLE PRESSO NETFLIX COM"); per queste causali no.

---

## Parte B: la correzione

Quando la causale scrive il nome dopo un'**etichetta**, prendiamo solo quel nome. La regola di `MerchantKey`
diventa:

1. la controparte, se c'è e non è un titolare;
2. **altrimenti il nome scritto nella causale dopo un'etichetta, se c'è;**
3. altrimenti la causale intera.

```java
public static String merchantText(String counterparty, String description, List<Set<String>> ownerNames) {
    if (counterparty != null && !counterparty.isBlank() && !ownerNames.contains(words(counterparty))) {
        return counterparty;
    }
    if (description == null || description.isBlank()) {
        return null;
    }
    String name = nameInDescription(description);
    return name != null ? name : description;
}
```

Le etichette che conosciamo:

| Movimento | Etichetta | Fino a |
|---|---|---|
| bonifico ricevuto | `Anagrafica Ordinante` | `Note:` o la fine |
| bonifico fatto | `Anagrafica Beneficiario` | `Note:` o la fine |
| addebito diretto (SDD) | `Creditor id.` + il codice del creditore | `Id Mandato` |

Con la correzione il rimborso ha la chiave `ACME SRL`, lo stipendio `BETA SPA`, la bolletta `GAMMA LUCE E GAS S P A`.

Perché non togliere semplicemente il limite dei 60 caratteri? Perché allora la chiave conterrebbe anche le
**note** ("Stipendio settembre", "Stipendio ottobre"): ogni bonifico avrebbe una chiave sua, e lo stesso mittente non
sarebbe più riconosciuto. Il nome dopo l'etichetta è proprio la parte che resta uguale.

Se nessuna etichetta c'è, non cambia niente: per i pagamenti con carta e per tutte le banche che riempiono la
controparte la chiave è quella di prima.

---

## Parte C: l'espressione regolare, pezzo per pezzo

```java
private static final Pattern NAME_IN_DESCRIPTION = Pattern.compile(
        "\\b(?:Anagrafica\\s+(?:Ordinante|Beneficiario)|Creditor\\s+id\\.?\\s+\\S+)\\s+"
                + "(?!Note\\s*:|Id\\s+Mandato\\b)(.+?)"
                + "(?=\\s+Note\\s*:|\\s+Id\\s+Mandato\\b|\\s*$)",
        Pattern.CASE_INSENSITIVE);
```

In Java ogni `\` dell'espressione si scrive `\\` dentro la stringa. Letta senza i doppi:

| Pezzo | Significato |
|---|---|
| `\b` | inizio di una parola: "XAnagrafica" non conta |
| `(?:A\|B)` | A oppure B; `?:` vuol dire "gruppo che non mi serve ricordare" |
| `Anagrafica\s+(?:Ordinante\|Beneficiario)` | l'etichetta dei bonifici; `\s+` è uno o più spazi |
| `Creditor\s+id\.?\s+\S+` | l'etichetta degli SDD: `\.?` il punto facoltativo, `\S+` il codice (una parola senza spazi) |
| `(?!Note\s*:\|Id\s+Mandato\b)` | **lookahead negativo**: "qui non deve cominciare un'altra etichetta". Senza, in "Anagrafica Ordinante Note: x" il nome sarebbe "Note: x" |
| `(.+?)` | **il nome**, il gruppo 1. Il `?` lo rende **pigro**: il più corto possibile |
| `(?=\s+Note\s*:\|\s+Id\s+Mandato\b\|\s*$)` | **lookahead**: "dopo deve venire Note:, o Id Mandato, o la fine". Controlla senza consumare |
| `CASE_INSENSITIVE` | maiuscole e minuscole non contano |

Perché **pigro**? Con `(.+)` (avido) il motore prenderebbe tutto fino in fondo, e lì la chiusura "la fine del testo"
va bene: il nome sarebbe "ACME SRL Note: Rimborso". Con `(.+?)` prova prima il nome più corto e si ferma alla prima
etichetta che chiude.

`nameInDescription` controlla anche che il nome abbia almeno una **lettera**: "Anagrafica Ordinante 12345" non è un
nome, e si torna alla causale intera.

---

## Parte D: le chiavi già salvate, la migrazione V28

La chiave è salvata nel database in tre tabelle: gli abbonamenti (`subscription`), i "no" ai suggerimenti
(`category_suggestion_dismissal`) e le risposte dell'IA (`ai_category_answer`, V20). Cambiando la regola, quelle
righe vanno ricalcolate: è lo stesso lavoro della V16 (`docs/26`, sezione 6), e la **V28** è scritta nello stesso
modo, in Java (`db/migration/V28__nome_nella_causale.java`):

1. per ogni movimento, la chiave vecchia e quella nuova: così sa in quali chiavi nuove si trasforma ogni vecchia;
2. una chiave vecchia che diventa **una sola** nuova: la riga viene aggiornata. È il caso degli SDD: la bolletta
   confermata come abbonamento passa da "ADDEBITO SDD CORE SCAD IMP CREDITOR ID GAMMA…" a "GAMMA LUCE E GAS S P A",
   e resta confermata;
3. una chiave vecchia che diventa **più** chiavi nuove: la riga resta com'è e non corrisponde più a niente. È il
   caso dei bonifici, ed è giusto così: un "no" o una risposta dell'IA su "tutti i bonifici dalla banca ALFA"
   non valevano per nessuno dei mittenti. L'IA, se accesa, potrà essere interrogata di nuovo su ognuno;
4. due righe che finiscono sulla stessa chiave: ne resta una (per gli abbonamenti vince la conferma; per l'IA
   la risposta che la chiave nuova aveva già).

Due differenze rispetto alla V16, perché lo schema nel frattempo è cambiato:

- un abbonamento è unico per **esercente, valuta e importo** (V21). Per cercare "c'è già?" serve confrontare
  anche l'importo, che per i "no" vecchi può essere `NULL`. In SQL `NULL = NULL` non è vero ("non so"): si usa
  `expected_amount IS NOT DISTINCT FROM ?`, che tratta due `NULL` come uguali;
- la tabella delle risposte dell'IA, arrivata con la V20.

Come sempre le regole, la vecchia e la nuova, sono **copiate dentro la migrazione**: una migrazione deve fare
sempre la stessa cosa, anche se un giorno `MerchantKey` cambierà di nuovo. E una migrazione già applicata non si
modifica mai: per questo la V16 resta com'era, e la regola nuova è una migrazione nuova.

---

## Parte E: il nome da mostrare

Nell'elenco dei movimenti e in cima alla scheda si vedeva la causale intera ("Bonifico N. 0000… BIC Ordinante…").
Ora il movimento ha un campo in più, `name`, calcolato dal backend:

```java
static String nameOf(String counterparty, String description) {
    if (counterparty != null && !counterparty.isBlank()) {
        return counterparty;
    }
    if (description == null || description.isBlank()) {
        return null;
    }
    String name = MerchantKey.nameInDescription(description);
    return name != null ? name : description;
}
```

e la pagina lo preferisce (`nameOf` in `day-groups.ts`): l'elenco mostra "ACME SRL", la scheda pure. La causale
intera resta nei **Dettagli** della scheda, e la **ricerca** guarda sempre la causale intera: "acme" trova il
bonifico, "rimborso" anche.

Perché il nome lo calcola il backend e non la pagina? Perché l'espressione regolare sta già in `MerchantKey`:
riscriverla in TypeScript vorrebbe dire due copie da tenere uguali, e prima o poi una delle due resterebbe
indietro. Una regola, in un posto solo.

Il campo nella pagina è **facoltativo** (`name?: string | null`): i dati di prova dei test più vecchi non lo
hanno, e `nameOf` ricade sulla controparte e sulla causale come prima.

---

## Parte F: un regalo per la privacy

All'intelligenza artificiale mandiamo il testo dell'esercente, senza le parole con cifre e mai il nome del
titolare quando è la controparte (`docs/28`). Ma per un SDD il testo era la causale intera, che finisce con
"**Debitore** NOME COGNOME": il nome del titolare arrivava al modello, e con l'IA nel cloud usciva dal computer.

Ora all'IA arriva solo "GAMMA LUCE E GAS S.P.A.". Il test `ofADirectDebitOnlyTheCreditorIsSentNotTheDebtor` lo
verifica. Il miglioramento è nella tabella della sicurezza (`docs/17`, riga 24).

---

## Parte G: i test, e la prova nel browser

Backend (332 test):

- `MerchantKeyTest`: due bonifici dalla stessa banca hanno chiavi diverse (`ACME SRL`, `BETA SPA`), anche con il
  titolare come controparte; il bonifico in uscita; l'SDD prende il creditore e non il debitore; senza un nome
  dopo l'etichetta resta la causale;
- `V28NomeNellaCausaleTest`: un database vero alla V27, abbonamenti, "no" e risposte dell'IA con le chiavi
  vecchie, poi la V28: la bolletta confermata cambia chiave e vince sul "no"; il "no" sulla chiave dei bonifici
  resta com'è; la risposta dell'IA che trova la chiave nuova già occupata sparisce;
- `TransactionControllerTest`: il campo `name` e la ricerca;
- `EvaluationCasesTest`: all'IA solo il creditore.

Frontend: `nameOf` preferisce il `name` del backend.

Nel browser, con un anno di movimenti inventati più due stipendi di BETA SPA, un rimborso di ACME SRL e tre
bollette SDD, tutti nel formato della Parte A:

- l'elenco mostra "ACME SRL" e "BETA SPA" invece di "Bonifico N. …";
- la scheda del rimborso: "Lo stesso esercente: 1 movimento"; quella dello stipendio: 2 movimenti, solo gli
  stipendi;
- scelta a mano la categoria del rimborso, il pannello non propone più di darla agli stipendi;
- tra i suggerimenti di abbonamento, "GAMMA LUCE E GAS S.P.A." invece di "Addebito SDD CORE Scad. Imp. …".

---

## Parte H: esercizi guidati

1. **Un'altra banca.** Se la tua banca scrive "Ordinante: ACME SRL - Causale: rimborso", che etichetta
   aggiungeresti all'espressione? E quale etichetta chiude? Scrivi prima il caso in `MerchantKeyTest`. Ricorda che
   cambiare la regola vuol dire una migrazione nuova (V29), non modificare la V28.
2. **Il pagamento con carta.** "PAGAMENTO CARTA DEL ALLE PRESSO NETFLIX COM" funziona, ma il nome vero è dopo
   "presso". Che cosa succederebbe agli abbonamenti già confermati se si prendesse solo quello? Che cosa farebbe la
   migrazione?
3. **Avido o pigro.** In un test, togli il `?` da `(.+?)` e prova con
   "Anagrafica Ordinante ACME SRL Note: rimborso". Che nome viene fuori? Perché (Parte C)?
