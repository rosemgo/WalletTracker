-- V40: un collegamento si può rimuovere tenendo la storia (docs/rimuovere-un-collegamento.md).
--
-- "Rimuovi collegamento" chiude il consenso su Enable Banking e segna qui il momento: da allora l'app non legge più
-- i conti di quel collegamento e non avvisa della scadenza, ma i conti e i loro movimenti restano. Se la banca viene
-- ricollegata, i conti si riconoscono (ConnectionService.saveSession) e passano al collegamento nuovo; quello rimosso,
-- rimasto senza conti, si cancella.
--
-- NULL = collegamento attivo (o scaduto, se valid_until è passato).
ALTER TABLE bank_connection ADD COLUMN removed_at TIMESTAMPTZ;
