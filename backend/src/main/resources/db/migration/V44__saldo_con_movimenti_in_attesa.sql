-- V44: i saldi della banca che comprendono i movimenti in attesa (docs/saldi.md, parte O).
--
-- Alcune banche (Revolut) non danno il saldo contabile, solo quello "previsto" (XPCD) o "disponibile" (ITAV, CLAV,
-- FWAV), che comprende anche i pagamenti in attesa. L'app lavora con il saldo contabile: alla lettura si salva quanto
-- era in attesa in quel momento, e il saldo contabile è il saldo letto meno quel totale. Così un pagamento in attesa si
-- conta una volta sola, quando viene contabilizzato. Null: il saldo è contabile (o scritto dall'utente).
ALTER TABLE account ADD COLUMN balance_pending NUMERIC(19, 2);

-- I controlli dei saldi fatti finora confrontavano un saldo con i movimenti in attesa con i soli movimenti
-- contabilizzati: per Revolut segnalavano quasi ogni giorno una differenza che non c'era. Si rifanno dal prossimo saldo.
DELETE FROM balance_check;

-- Le fotografie dei saldi dei giorni scorsi (dal 16 novembre 2026): per un conto come Revolut avevano dentro i movimenti
-- in attesa, mentre da ora sono contabili. Sono valori calcolati, non dati dell'utente: quei giorni tornano stimati dai
-- movimenti, e le fotografie ripartono, omogenee, dalla prossima ora.
DELETE FROM balance_snapshot;
