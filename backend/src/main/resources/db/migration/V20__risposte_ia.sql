-- V20: le risposte dell'IA sulla categoria degli esercenti (Fase 3, passo 7b.3).
--
-- Una riga per esercente (la sua MerchantKey, come in V15): la risposta del modello a "di che categoria è?".
-- Le salviamo perché ogni risposta costa qualche secondo di calcolo: un esercente si chiede una volta sola.
--   category_id  la categoria suggerita; NULL = il modello ha risposto "non so" (salvato anche questo,
--                così non lo richiediamo ogni volta);
--   model        il modello che ha risposto (es. gemma3:4b), da mostrare accanto al suggerimento.
-- È solo un suggerimento: la categoria vera di un movimento la decide sempre l'utente ("accetta").
CREATE TABLE ai_category_answer (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    merchant_key TEXT         NOT NULL,
    category_id  BIGINT,
    model        VARCHAR(100) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_ai_category_answer UNIQUE (merchant_key),
    -- Eliminando una categoria spariscono le risposte che la indicano: quegli esercenti si potranno richiedere.
    CONSTRAINT fk_ai_category_answer_category FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE CASCADE
);
