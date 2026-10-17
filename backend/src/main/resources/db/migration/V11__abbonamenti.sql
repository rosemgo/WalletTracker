-- V11: gli abbonamenti ricorrenti (Netflix, palestra, assicurazione...).
--
-- Il programma SUGGERISCE gli abbonamenti (vedi RecurringDetector), ma è l'utente a decidere. Questa tabella
-- ricorda le sue decisioni:
--   CONFIRMED = "sì, è un abbonamento": compare nella pagina Abbonamenti;
--   DISMISSED = "no, non è un abbonamento": non viene più suggerito.
-- Un abbonamento si riconosce dalla "chiave" dell'esercente (vedi MerchantKey) e dalla valuta.
CREATE TABLE subscription (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    merchant_key    TEXT          NOT NULL,
    currency        VARCHAR(3)    NOT NULL,
    status          VARCHAR(10)   NOT NULL,   -- CONFIRMED o DISMISSED
    name            TEXT,                     -- il nome scelto dall'utente (solo CONFIRMED)
    frequency       VARCHAR(10),              -- MONTHLY o YEARLY (solo CONFIRMED)
    expected_amount NUMERIC(19,2),            -- l'importo al momento della conferma, per accorgersi dei rincari
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_subscription_key_currency UNIQUE (merchant_key, currency),
    -- Un abbonamento confermato deve avere nome, frequenza e importo: il database lo garantisce.
    CONSTRAINT ck_subscription_confirmed CHECK (
        status = 'DISMISSED'
        OR (name IS NOT NULL AND frequency IS NOT NULL AND expected_amount > 0)
    )
);
