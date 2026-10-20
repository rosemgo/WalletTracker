-- V15: i suggerimenti di categoria rifiutati (Fase 3, passo 7a).
--
-- I suggerimenti "dalla tua storia" NON si salvano: si calcolano al momento dalle categorie che hai scelto a
-- mano (così seguono sempre le tue ultime scelte). Si salva solo il "no": per quell'esercente, quella
-- categoria non va più proposta. Se poi scegli a mano un'altra categoria per lo stesso esercente, il
-- suggerimento nuovo è diverso e viene proposto.
CREATE TABLE category_suggestion_dismissal (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    merchant_key TEXT        NOT NULL,   -- l'esercente (vedi MerchantKey)
    category_id  BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_suggestion_dismissal UNIQUE (merchant_key, category_id),
    -- Eliminando la categoria, il suo "no" non serve più: sparisce con lei.
    CONSTRAINT fk_suggestion_dismissal_category FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE CASCADE
);
