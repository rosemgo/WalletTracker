-- La chiave privata di Enable Banking non si salva più in chiaro: d'ora in poi è cifrata con AES-256-GCM
-- (vedi SecretCipher). La colonna cambia nome, così è chiaro cosa contiene.
--
-- Eventuali chiavi salvate in chiaro con la versione precedente vengono CANCELLATE (non si possono cifrare
-- con SQL): se ne avevi salvata una dalla pagina Impostazioni, caricala di nuovo. Chi usa il file .env non
-- deve fare nulla.
DELETE FROM enable_banking_settings;

ALTER TABLE enable_banking_settings RENAME COLUMN private_key_pem TO private_key_encrypted;
