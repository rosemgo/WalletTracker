-- V14: i conti "manuali", i cui movimenti arrivano da un file CSV invece che da Enable Banking.
--
-- Esempio: Trade Republic, o una banca che Enable Banking non copre bene. Un conto manuale non ha un
-- collegamento (connection_id) né un uid di Enable Banking (provider_uid): per questo le due colonne
-- diventano facoltative, e una colonna "source" dice da dove arrivano i movimenti.
ALTER TABLE account ALTER COLUMN connection_id DROP NOT NULL;
ALTER TABLE account ALTER COLUMN provider_uid DROP NOT NULL;
ALTER TABLE account ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'ENABLE_BANKING';
-- Il nome dell'istituto per i conti manuali (es. "Trade Republic"); per gli altri c'è già nel collegamento.
ALTER TABLE account ADD COLUMN bank_name VARCHAR(100);

-- Le due forme ammesse, garantite dal database:
--   ENABLE_BANKING: con collegamento e uid;
--   MANUAL: senza collegamento, con il nome dell'istituto.
ALTER TABLE account ADD CONSTRAINT ck_account_source CHECK (
    (source = 'ENABLE_BANKING' AND connection_id IS NOT NULL AND provider_uid IS NOT NULL)
    OR (source = 'MANUAL' AND connection_id IS NULL AND bank_name IS NOT NULL)
);
