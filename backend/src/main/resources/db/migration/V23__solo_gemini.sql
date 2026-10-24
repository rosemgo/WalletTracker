-- V23: nel cloud resta solo Google Gemini (Fase 3, passo 7c, docs/30 parte O).
--
-- Mistral AI non dà più chiavi API con il piano gratuito: per usarle serve un piano a pagamento. Un servizio
-- cloud gratuito (Gemini) e uno locale (Ollama) bastano, quindi togliamo Mistral.
-- V22 non si modifica (è già applicata): qui ne annulliamo la parte di Mistral.

-- Chi aveva scelto Mistral torna a "spento" (prima di cambiare i vincoli, che non ammetterebbero più MISTRAL).
UPDATE ai_settings SET provider = 'NONE' WHERE provider = 'MISTRAL';

-- Il vincolo "chiave e consenso" di V22 nomina le colonne di Mistral: lo rifacciamo solo per Gemini.
ALTER TABLE ai_settings DROP CONSTRAINT ck_ai_settings_cloud_ready;
ALTER TABLE ai_settings ADD CONSTRAINT ck_ai_settings_cloud_ready CHECK (
    provider <> 'GEMINI' OR (gemini_api_key IS NOT NULL AND gemini_consent_at IS NOT NULL)
);

ALTER TABLE ai_settings DROP CONSTRAINT ai_settings_provider_check;
ALTER TABLE ai_settings ADD CONSTRAINT ai_settings_provider_check CHECK (provider IN ('NONE', 'OLLAMA', 'GEMINI'));

-- Insieme alle colonne se ne va anche l'eventuale chiave di Mistral salvata (cifrata): non serve più tenerla.
ALTER TABLE ai_settings DROP COLUMN mistral_api_key;
ALTER TABLE ai_settings DROP COLUMN mistral_model;
ALTER TABLE ai_settings DROP COLUMN mistral_consent_at;
