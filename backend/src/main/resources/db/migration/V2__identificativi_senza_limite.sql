-- V2: gli identificativi che arrivano dalla banca non hanno una lunghezza massima garantita.
--
-- Con ING l'impronta del conto superava i 200 caratteri previsti in V1 e l'inserimento falliva
-- ("value too long for type character varying(200)").
--
-- Perché un nuovo file e non una modifica a V1? V1 è già stato eseguito sui database esistenti
-- (il tuo compreso): Flyway non lo rieseguirebbe e segnalerebbe che il file è cambiato.
-- Le modifiche allo schema si fanno sempre aggiungendo una nuova versione.
--
-- In PostgreSQL TEXT è una stringa senza limite di lunghezza, con le stesse prestazioni di VARCHAR.
-- La usiamo per i valori che decide la banca; i limiti restano dove li conosciamo (IBAN, valuta, paese).

ALTER TABLE bank_connection ALTER COLUMN session_id   TYPE TEXT;
ALTER TABLE bank_connection ALTER COLUMN aspsp_name   TYPE TEXT;

ALTER TABLE account ALTER COLUMN external_key TYPE TEXT;
ALTER TABLE account ALTER COLUMN provider_uid TYPE TEXT;
ALTER TABLE account ALTER COLUMN name         TYPE TEXT;
