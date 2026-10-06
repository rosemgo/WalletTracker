# 3. Modello e regole: le decisioni prese

Questo documento raccoglie quello che abbiamo imparato nella Fase 0 dai dati reali e le decisioni
che ne derivano. È il riferimento per il codice delle fasi successive: se una regola cambia, si
aggiorna prima qui.

---

## 1. Conti e ruoli

Ogni conto ha un **ruolo**. Il ruolo decide come trattare i suoi movimenti quando nessuna regola
più specifica si applica.

Nel codice i ruoli sono l'enum `AccountRole`: `MAIN` (principale), `SPENDING` (spese),
`INVESTMENT` (investimenti), `EXCLUDED` (escluso), più `UNASSIGNED` per i conti appena collegati.

| Ruolo | Conti | Uscite, di default | Entrate, di default |
|---|---|---|---|
| **PRINCIPALE** | ING conto corrente (dove arriva lo stipendio) | spesa | entrata |
| **SPESE** | Revolut, ING carta di credito, BBVA (poco usato) | spesa | rimborso |
| **INVESTIMENTI** | Fineco conto corrente, Fineco conto trading, Trade Republic | costo/tassa | rendita |
| **ESCLUSO** | conti collegati solo per prova, conti vuoti (es. Revolut USD) | ignorata | ignorata |

## 2. Tipi di movimento

| Tipo | Esempi | Conta come spesa? |
|---|---|---|
| `ENTRATA` | stipendio | no |
| `SPESA` | supermercato, Amazon, bollette | **sì** |
| `RIMBORSO` | storno di un acquisto | riduce la spesa della sua categoria |
| `TRASFERIMENTO_INTERNO` | ING → Revolut, saldo mensile della carta di credito | no |
| `VERSAMENTO_INVESTIMENTI` | ING → Fineco / Trade Republic | no (sezione investimenti) |
| `PRELIEVO_INVESTIMENTI` | Fineco → ING | no (sezione investimenti) |
| `ACQUISTO_TITOLI` / `VENDITA_TITOLI` | `Compravendita Titoli` | no: il capitale cambia forma |
| `RENDITA` | cedole, dividendi, interessi | no (sezione investimenti) |
| `TASSA_INVESTIMENTI` | ritenute, imposta di bollo, capital gain, Tobin tax | no (sezione investimenti) |
| `IGNORATO` | movimenti da 0,00 € (verifiche della carta) | no |

## 3. Trasferimenti tra i propri conti

La regola guarda **da quale gruppo di conti a quale gruppo** si spostano i soldi:

| Da → A | Tipo |
|---|---|
| PRINCIPALE/SPESE → PRINCIPALE/SPESE | `TRASFERIMENTO_INTERNO` |
| PRINCIPALE/SPESE → INVESTIMENTI | `VERSAMENTO_INVESTIMENTI` |
| INVESTIMENTI → PRINCIPALE/SPESE | `PRELIEVO_INVESTIMENTI` |
| INVESTIMENTI → INVESTIMENTI | `TRASFERIMENTO_INTERNO` (non è un nuovo versamento) |

Un trasferimento si riconosce in due modi, da usare insieme:

1. **Abbinamento:** due conti tuoi hanno movimenti di **importo uguale e segno opposto**, a pochi
   giorni di distanza (es. Fineco c/c −450 e Fineco trading +450 il 27/03). È l'unico modo per
   Trade Republic, che non fornisce descrizioni.
2. **Causale:** "Trasferimento a mio conto", ordinante o beneficiario con il tuo nome, "Payment
   from" seguito dal tuo nome, ecc.

Anche il **saldo mensile della carta di credito** sul conto corrente ING ("Estratto conto carta di
credito") è un trasferimento interno: le singole spese sono già contate sul conto della carta.

## 4. Investimenti: causali di Fineco

| Causale (inizio) | Tipo | Note |
|---|---|---|
| `Ced.su` | `RENDITA` (cedola) | contiene quantità e titolo, es. `BTP-1OT53 4,5%` |
| `Div.su` | `RENDITA` (dividendo) | contiene quantità e titolo, es. `ENI` |
| `Interessi Portaf. Remun.` | `RENDITA` (interessi sulla liquidità) | |
| `Rit.ced.su`, `Rit.div.su`, `Rit. Fisc.`, `Ritenuta Fiscale` | `TASSA_INVESTIMENTI` | le ritenute sul rateo arrivano spesso in coppie ± |
| `Imposta Sostitutiva Capit.GAIN` | `TASSA_INVESTIMENTI` | |
| `Tobin Tax` | `TASSA_INVESTIMENTI` | |
| `Addebito imposta di bollo`, `Imposta di bollo` | `TASSA_INVESTIMENTI` | |
| `Canone Mensile` / `Sconto Canone` | costo del conto | si annullano |
| `Compravendita Titoli` | `ACQUISTO_TITOLI` (−) / `VENDITA_TITOLI` (+) | |

Nella dashboard, per ogni titolo: lordo incassato, tasse, netto.

Il **valore del portafoglio** (titoli posseduti, prezzi) non è disponibile via PSD2: la PSD2 copre
solo la liquidità. Si potrà aggiungere in futuro con un'altra fonte (es. import dei report dei broker).

## 5. Particolarità di ogni banca (dalla Fase 0)

| Banca | Consenso | Particolarità |
|---|---|---|
| **ING** | 180 giorni | 4 conti esposti (conto corrente, conto deposito, carte); le carte non hanno IBAN; le descrizioni della carta hanno colonne a larghezza fissa (esercente, città, paese) |
| **Revolut** | 180 giorni | un conto per valuta con lo **stesso IBAN**; movimenti da 0,00 €; controparte e causale spesso identiche (da non ripetere) |
| **Fineco c/c** | 180 giorni | storico dalla data di apertura (marzo 2026); causali molto strutturate |
| **Fineco trading** | 180 giorni | include acquisti/vendite di titoli; il saldo può essere leggermente negativo |
| **BBVA** | 180 giorni | conto poco usato; descrizioni nello stile spagnolo (`TRANSFERENCIAS // TRANSFERENCIA RECIBIDA // …`); nessun header PSU richiesto |
| **Trade Republic** | **90 giorni** | letture in background quasi assenti: servono gli header PSU; **pagine ripetute** (ogni movimento arriva due volte); **nessuna descrizione**; ignora `date_from`; importi con 6 decimali |

Conseguenze per il modello:

- un conto si identifica con l'**`uid` di Enable Banking**, non con l'IBAN;
- di ogni movimento salviamo anche il **JSON originale**, per poterlo rianalizzare quando
  cambiamo le regole;
- gli importi vanno salvati con **2 decimali**, come `NUMERIC(19,2)`.

## 6. Eliminazione dei doppioni

Implementata in `TransactionImportService` (dettagli in `docs/06-fase-1-passo-3-movimenti.md`):

1. **copie identiche** nella stessa risposta (stesso JSON, es. pagine ripetute di Trade Republic):
   se ne tiene una;
2. **impronta**: `ref:<entry_reference>` se la banca lo fornisce, altrimenti `fp:<SHA-256 di data,
   importo, valuta, controparte, causale>`;
3. **movimenti uguali ma distinti** (JSON diversi, stessa impronta, es. due caffè): numero
   progressivo `#2`, `#3`...;
4. **movimenti in attesa**: sostituiti a ogni importazione;
5. **vincolo `UNIQUE (account_id, dedup_key)`** nel database come rete di sicurezza.

Ogni importazione riparte dall'ultimo movimento contabilizzato meno 10 giorni (la prima: un anno).

## 7. Sincronizzazione

| Modalità | Quando | Header PSU | Limite |
|---|---|---|---|
| automatica | ogni 6 ore (Trade Republic: 1 volta al giorno) | no | ~4 al giorno per conto |
| "Aggiorna ora" | quando premi il pulsante nella dashboard | sì | nessun limite giornaliero |

- Un errore 429 non blocca tutto: si salta quel conto e si riprova al giro successivo.
- Ogni sincronizzazione scarica solo dall'ultima data letta, meno qualche giorno di margine.
- Scadenza del consenso: avviso qualche giorno prima (Trade Republic ogni 90 giorni, le altre ogni 180).

## 8. Classificazione manuale

Le operazioni rare (per esempio un prestito ricevuto da un familiare e le sue restituzioni) non
meritano regole automatiche. Dalla dashboard si potrà cambiare a mano il tipo di un singolo
movimento, ad esempio in `TRASFERIMENTO_INTERNO` o `IGNORATO`, così non pesa su entrate e spese.
La scelta manuale ha sempre la precedenza sulle regole.

## 9. Domande aperte

- **Trade Republic:** cosa contiene il JSON originale (lo vedremo quando lo salveremo nel database).
