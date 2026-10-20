-- V17: un nome per il conto scelto dall'utente (es. "Conto spese", "Carta").
--
-- Il nome che manda la banca (colonna name) spesso è il nome del titolare: con più conti della stessa persona
-- sono tutti uguali. Se l'utente dà un nome, la dashboard mostra quello; altrimenti banca e ultime cifre
-- dell'IBAN (vedi Account.label). Il nome della banca resta com'è: una nuova sincronizzazione lo aggiorna,
-- quello scelto dall'utente no.
ALTER TABLE account ADD COLUMN display_name VARCHAR(100);
