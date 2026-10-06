-- Fase 1, passo 5: lo stato della sincronizzazione di ogni conto.
--
-- last_sync_at:    quando è stata tentata l'ultima importazione (riuscita o no).
--                  La sincronizzazione automatica la usa per non superare il limite di letture
--                  giornaliere della banca, anche se l'applicazione viene riavviata più volte.
-- last_sync_error: il motivo dell'ultimo errore (es. "HTTP 429 ..."), NULL se l'ultima è andata bene.
--                  Nella Fase 2 la dashboard lo mostrerà accanto al conto.

ALTER TABLE account
    ADD COLUMN last_sync_at    TIMESTAMPTZ,
    ADD COLUMN last_sync_error TEXT;
