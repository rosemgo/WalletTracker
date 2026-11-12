-- V29: il PIN per aprire l'app (Fase 4, passo 3: docs/46).
--
-- Al massimo una riga (id = 1). Nessuna riga = il PIN non c'è ancora: l'app chiede di crearlo, solo dal PC.
-- Per azzerarlo (PIN dimenticato) si cancella la riga: lo fa lo script di installazione (-ReimpostaPin).
--
--   pin_hash         il PIN NON in chiaro: il suo hash bcrypt (es. "{bcrypt}$2a$12$..."), un calcolo lento fatto
--                    apposta, che non si può invertire. Il prefisso tra graffe dice quale algoritmo è stato usato;
--   failed_attempts  i PIN sbagliati di fila, dall'ultimo giusto: dopo 5 si comincia ad aspettare;
--   locked_until     fino a quando i tentativi sono bloccati (null = non bloccati).
-- Il conteggio sta nel database, e non in memoria, perché un riavvio del backend non lo azzeri.
CREATE TABLE app_pin (
    id              INT         PRIMARY KEY CHECK (id = 1),
    pin_hash        TEXT        NOT NULL,
    failed_attempts INT         NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
