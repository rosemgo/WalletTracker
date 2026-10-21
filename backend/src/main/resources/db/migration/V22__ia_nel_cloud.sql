-- V22: l'IA nel cloud, con Mistral AI e Google Gemini (Fase 3, passo 7c).
--
-- Oltre a "spento" e "Ollama" (V18), l'utente può scegliere un servizio cloud. Per ognuno servono:
--   *_api_key     la chiave del servizio, inserita dall'utente e CIFRATA (SecretCipher, come la chiave di
--                 Enable Banking in V8): nel database c'è "v1:...", mai la chiave in chiaro;
--   *_model       il modello da usare (si può cambiare dalla pagina Impostazioni);
--   *_consent_at  quando l'utente ha accettato che i testi degli esercenti siano inviati a quel servizio.
--                 Il consenso è per servizio: dire sì a Mistral non vuol dire dire sì a Google.
-- Il database garantisce che un servizio cloud si possa scegliere solo con chiave e consenso.

-- Il vincolo di V18 ammette solo NONE e OLLAMA: lo sostituiamo (V18 non si modifica, è già applicata).
ALTER TABLE ai_settings DROP CONSTRAINT ai_settings_provider_check;
ALTER TABLE ai_settings ADD CONSTRAINT ai_settings_provider_check
    CHECK (provider IN ('NONE', 'OLLAMA', 'MISTRAL', 'GEMINI'));

ALTER TABLE ai_settings ADD COLUMN mistral_api_key    TEXT;
ALTER TABLE ai_settings ADD COLUMN mistral_model      VARCHAR(100) NOT NULL DEFAULT 'mistral-small-latest';
ALTER TABLE ai_settings ADD COLUMN mistral_consent_at TIMESTAMPTZ;
ALTER TABLE ai_settings ADD COLUMN gemini_api_key     TEXT;
ALTER TABLE ai_settings ADD COLUMN gemini_model       VARCHAR(100) NOT NULL DEFAULT 'gemini-flash-latest';
ALTER TABLE ai_settings ADD COLUMN gemini_consent_at  TIMESTAMPTZ;

ALTER TABLE ai_settings ADD CONSTRAINT ck_ai_settings_cloud_ready CHECK (
    (provider <> 'MISTRAL' OR (mistral_api_key IS NOT NULL AND mistral_consent_at IS NOT NULL))
    AND (provider <> 'GEMINI' OR (gemini_api_key IS NOT NULL AND gemini_consent_at IS NOT NULL))
);
