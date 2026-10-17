-- V10: i budget mensili per categoria ("Spesa alimentare: al massimo 400 euro al mese").
--
-- Un budget vale per una categoria in una valuta: le valute non si sommano (vedi docs/18).
CREATE TABLE budget (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category       TEXT          NOT NULL,   -- la stessa categoria dei movimenti (testo libero, vedi docs/19)
    currency       VARCHAR(3)    NOT NULL,
    monthly_amount NUMERIC(19,2) NOT NULL CHECK (monthly_amount > 0),

    -- Un solo budget per categoria e valuta. Il backend lo controlla prima di salvare, per dare un messaggio
    -- chiaro; il vincolo è l'ultima difesa, come per i movimenti doppi (V3).
    CONSTRAINT uq_budget_category_currency UNIQUE (category, currency)
);
