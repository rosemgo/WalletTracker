-- V30: il PIN diventa facoltativo dal PC, e arriva il codice di recupero (Fase 4, passo 3: docs/46).
--
-- La riga di app_pin (V29) ora ha tre significati:
--   nessuna riga                l'app non è ancora stata aperta dal PC: alla prima apertura si sceglie;
--   riga con pin_hash NULL      "senza PIN": dal PC si entra subito, da un altro dispositivo non si entra;
--   riga con pin_hash           il PIN c'è: si chiede su ogni dispositivo, PC compreso.
--
--   recovery_hash  l'hash bcrypt del codice di recupero (es. K7QM-2XPA-9RWD-4TNE), mostrato una sola volta quando
--                  si crea il PIN. Con il codice si sceglie un PIN nuovo; poi il codice è consumato e se ne crea un
--                  altro. NULL quando non c'è un PIN;
--   generation     un contatore che cresce a ogni cambio del PIN (creato, recuperato, tolto). Ogni sessione ricorda
--                  il numero di quando è stata aperta: se non coincide più, la sessione non vale (un telefono perso
--                  resta fuori dopo il recupero).
ALTER TABLE app_pin ALTER COLUMN pin_hash DROP NOT NULL;
ALTER TABLE app_pin ADD COLUMN recovery_hash TEXT;
ALTER TABLE app_pin ADD COLUMN generation INT NOT NULL DEFAULT 1 CHECK (generation >= 1);
