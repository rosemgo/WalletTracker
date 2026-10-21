-- V19: il modello predefinito per Ollama diventa gemma3:4b (Fase 3, passo 7b.2).
--
-- In V18 era qwen2.5:3b, scelto prima di misurare. Le misure (docs/27, parte M) su 49 esercenti di prova:
--   gemma3:4b    73-82% di risposte giuste, ~3 GB di memoria mentre lavora, ~5 s a domanda;
--   qwen2.5:3b   circa la metà di risposte giuste.
-- V18 non si modifica (è già stata applicata): una migrazione nuova cambia il valore predefinito.
ALTER TABLE ai_settings ALTER COLUMN ollama_model SET DEFAULT 'gemma3:4b';

-- La riga esistente la cambiamo solo se è ancora quella iniziale: IA spenta e modello mai toccato.
-- Se l'utente ha già acceso l'IA o scelto un modello, la sua scelta resta.
UPDATE ai_settings SET ollama_model = 'gemma3:4b', updated_at = now()
WHERE provider = 'NONE' AND ollama_model = 'qwen2.5:3b';
