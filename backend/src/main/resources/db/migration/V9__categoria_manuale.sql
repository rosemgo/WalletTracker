-- V9: la categoria scelta a mano dall'utente.
--
-- Come manual_type (V4): se c'è, vince su quella decisa dalle regole. La colonna "category" resta il
-- risultato finale, cioè quello che la dashboard mostra e il riepilogo somma.
ALTER TABLE bank_transaction ADD COLUMN manual_category TEXT;
