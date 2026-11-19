-- V45: il tipo del saldo (docs/saldi.md, parte P).
--
-- Con la V44 l'app tiene conto dei movimenti in attesa compresi in un saldo (Revolut). Ma per un saldo salvato prima non
-- sa se li comprendeva: la V44 l'ha lasciato come se fosse contabile, e un pagamento in attesa si è contato due volte
-- (Revolut −0,99 € invece di 17,67 €, finché la banca non ha ridato il saldo), e il controllo dei saldi poteva segnalare
-- una differenza che non c'era.
--
-- Da qui ogni saldo dice di che tipo è: il codice della banca (CLBD, ITBD, XPCD, ITAV...), o WRITTEN per uno scritto
-- dall'utente. Un saldo senza tipo (salvato prima) è "da rileggere": non gli si aggiungono i movimenti in attesa, non
-- entra nel controllo né nelle fotografie, e la sincronizzazione lo rilegge alla prima occasione (Account.isPendingKnown).
ALTER TABLE account ADD COLUMN balance_type VARCHAR(10);

-- I controlli e le fotografie nati tra la V44 e questa (poche ore): possono avere dentro l'errore. Valori calcolati: i
-- controlli ripartono dalla prossima lettura, le fotografie dalla prossima ora.
DELETE FROM balance_check;
DELETE FROM balance_snapshot;
