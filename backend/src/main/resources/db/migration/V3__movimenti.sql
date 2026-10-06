-- V3: i movimenti bancari.
--
-- Il nome "transaction" è una parola riservata in SQL, per questo la tabella si chiama bank_transaction.

CREATE TABLE bank_transaction (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id   BIGINT        NOT NULL REFERENCES account (id),
    dedup_key    TEXT          NOT NULL,   -- "impronta" del movimento, per riconoscere i doppioni (vedi TransactionImportService)
    status       VARCHAR(10)   NOT NULL,   -- BOOKED (contabilizzato) o PENDING (in attesa)
    booking_date DATE          NOT NULL,   -- la data del movimento
    value_date   DATE,                     -- data valuta, se la banca la fornisce
    amount       NUMERIC(19,2) NOT NULL,   -- con segno: negativo = uscita. NUMERIC = decimale esatto, mai float per i soldi
    currency     VARCHAR(3)    NOT NULL,
    counterparty TEXT,                     -- chi riceve (uscite) o chi invia (entrate)
    description  TEXT,                     -- la causale
    raw_json     JSONB         NOT NULL,   -- il JSON originale della banca, così com'è
    imported_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- Vincolo di unicità: lo stesso conto non può avere due movimenti con la stessa impronta.
    -- È l'ultima difesa contro i doppioni: anche se il codice sbagliasse, il database rifiuterebbe l'inserimento.
    CONSTRAINT uq_bank_transaction_account_dedup UNIQUE (account_id, dedup_key)
);

-- Indice: rende veloci le ricerche "movimenti di un conto ordinati per data", che faremo spesso.
CREATE INDEX ix_bank_transaction_account_date ON bank_transaction (account_id, booking_date);
