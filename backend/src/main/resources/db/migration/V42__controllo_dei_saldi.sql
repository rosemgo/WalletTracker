-- V42: il controllo dei saldi (docs/saldi.md, parte N).
--
-- Ogni volta che arriva un saldo nuovo (dalla banca, o scritto dall'utente) lo si confronta con quello di prima più i
-- movimenti arrivati in mezzo. Se tornano, tutti i movimenti di quel periodo ci sono e non si salva niente. Se non
-- tornano, si salva la coppia di saldi: l'avviso "i movimenti non tornano" si ricalcola da qui a ogni apertura, e
-- sparisce da solo appena arriva il movimento che mancava (dalla sincronizzazione o da un file).
CREATE TABLE balance_check (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id  BIGINT         NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    source      VARCHAR(10)    NOT NULL CHECK (source IN ('BANK', 'MANUAL')),
    from_date   DATE           NOT NULL,  -- il saldo di prima: il suo giorno...
    from_at     TIMESTAMPTZ,              -- ...quando è stato letto o scritto (null per un saldo salvato prima di V34)
    from_amount NUMERIC(19, 2) NOT NULL,  -- ...e l'importo
    to_date     DATE           NOT NULL,  -- il saldo nuovo
    to_at       TIMESTAMPTZ    NOT NULL,
    to_amount   NUMERIC(19, 2) NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL
);
