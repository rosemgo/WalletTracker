-- V18: le impostazioni dei suggerimenti con l'intelligenza artificiale (Fase 3, passo 7b).
--
-- Una sola riga (id = 1), come per Enable Banking (V7). La scelta è dell'utente, dalla pagina Impostazioni:
--   provider     NONE = spenti (predefinito), OLLAMA = un modello sul proprio computer;
--   ollama_model il modello da usare con Ollama (es. "qwen2.5:3b"), si può cambiare.
-- L'indirizzo di Ollama invece è configurazione dell'installazione: sta in application.yml.
CREATE TABLE ai_settings (
    id           INT          PRIMARY KEY CHECK (id = 1),
    provider     VARCHAR(20)  NOT NULL DEFAULT 'NONE' CHECK (provider IN ('NONE', 'OLLAMA')),
    ollama_model VARCHAR(100) NOT NULL DEFAULT 'qwen2.5:3b',
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- La riga c'è sempre: all'inizio l'IA è spenta.
INSERT INTO ai_settings (id) VALUES (1);
