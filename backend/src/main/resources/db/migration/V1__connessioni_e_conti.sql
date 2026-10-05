-- V1: le prime due tabelle.
--
-- Flyway esegue i file di questa cartella in ordine di versione (V1, V2, ...) e si ricorda
-- quali ha già eseguito nella tabella "flyway_schema_history". Un file già eseguito NON va
-- più modificato: per cambiare lo schema si aggiunge un nuovo file (V2__..., V3__...).

-- Un collegamento a una banca: il consenso dato su Enable Banking (una "sessione").
CREATE TABLE bank_connection (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,  -- numero progressivo assegnato dal database
    aspsp_name    VARCHAR(100) NOT NULL,                            -- nome della banca, es. "ING"
    aspsp_country VARCHAR(2)   NOT NULL,                            -- paese, es. "IT"
    session_id    VARCHAR(100) NOT NULL UNIQUE,                     -- id della sessione su Enable Banking
    valid_until   TIMESTAMPTZ  NOT NULL,                            -- scadenza del consenso
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Un conto bancario. Più conti possono appartenere allo stesso collegamento (es. ING: conto + carte).
CREATE TABLE account (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    connection_id BIGINT       NOT NULL REFERENCES bank_connection (id),  -- chiave esterna: a quale collegamento appartiene
    external_key  VARCHAR(200) NOT NULL UNIQUE,  -- identificativo STABILE del conto, uguale anche dopo un nuovo consenso
    provider_uid  VARCHAR(100) NOT NULL,         -- uid di Enable Banking per la sessione corrente (cambia a ogni consenso)
    iban          VARCHAR(34),                   -- può mancare (es. carte di credito)
    name          VARCHAR(200),
    currency      VARCHAR(3)   NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'UNASSIGNED',  -- MAIN, SPENDING, INVESTMENT, EXCLUDED (vedi AccountRole)
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
