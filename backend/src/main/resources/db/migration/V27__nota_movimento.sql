-- V27: la nota di un movimento, scritta dall'utente (docs/34).
--
-- Solo per l'utente: la sincronizzazione con la banca e la classificazione non la toccano mai. Facoltativa (NULL =
-- nessuna nota) e corta: il limite è controllato dal backend, e qui di nuovo, così nemmeno un errore nel codice può
-- salvare un testo enorme.
ALTER TABLE bank_transaction ADD COLUMN note TEXT;
ALTER TABLE bank_transaction ADD CONSTRAINT ck_bank_transaction_note_length CHECK (char_length(note) <= 300);
