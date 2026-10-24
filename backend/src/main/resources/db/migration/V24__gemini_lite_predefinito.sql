-- V24: il modello Gemini predefinito diventa gemini-flash-lite-latest (Fase 3, passo 7c, docs/30 parte O7).
--
-- Con il piano gratuito, gemini-flash-latest può aspettare minuti in coda da Google (misurati 81 e 366 secondi per
-- una domanda), mentre il Lite risponde in pochi secondi e sui tuoi esercenti ha indovinato di più di gemma3:4b.
-- Chi aveva ancora il vecchio predefinito passa al nuovo; chi aveva scritto un altro nome lo tiene.
ALTER TABLE ai_settings ALTER COLUMN gemini_model SET DEFAULT 'gemini-flash-lite-latest';
UPDATE ai_settings SET gemini_model = 'gemini-flash-lite-latest' WHERE gemini_model = 'gemini-flash-latest';
