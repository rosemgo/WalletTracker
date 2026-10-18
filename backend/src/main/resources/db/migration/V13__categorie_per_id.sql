-- V13: movimenti, regole e budget puntano alla categoria con il suo ID, non più con il nome.
--
-- In V12 il collegamento era il nome ("Spesa alimentare"), ripetuto in ogni riga che lo usava, con una chiave
-- esterna ON UPDATE CASCADE per tenerlo allineato. Ora è la soluzione "da manuale" (normalizzata): il nome sta
-- SOLO nella tabella category, e le altre tabelle tengono il numero (category_id). Rinominare una categoria
-- cambia una riga sola; il numero non cambia mai. Vedi docs/24.
--
-- Il procedimento, uguale per ogni tabella:
--   1. aggiungiamo la colonna nuova, vuota;
--   2. la riempiamo cercando l'id del nome (V12 garantisce che ogni nome usato sia nell'elenco);
--   3. togliamo la vecchia colonna: insieme spariscono la sua chiave esterna e i suoi vincoli;
--   4. aggiungiamo la nuova chiave esterna verso category(id).
-- Flyway esegue tutto il file in una transazione: se un passo fallisse, il database tornerebbe com'era.

-- 1-2. Le colonne nuove, riempite dai nomi.
ALTER TABLE bank_transaction ADD COLUMN category_id BIGINT;
ALTER TABLE bank_transaction ADD COLUMN manual_category_id BIGINT;
ALTER TABLE classification_rule ADD COLUMN category_id BIGINT;
ALTER TABLE budget ADD COLUMN category_id BIGINT;

UPDATE bank_transaction t SET category_id = c.id FROM category c WHERE c.name = t.category;
UPDATE bank_transaction t SET manual_category_id = c.id FROM category c WHERE c.name = t.manual_category;
UPDATE classification_rule r SET category_id = c.id FROM category c WHERE c.name = r.category;
UPDATE budget b SET category_id = c.id FROM category c WHERE c.name = b.category;

-- 3. Le colonne vecchie (con le chiavi esterne di V12 e il vincolo UNIQUE (category, currency) dei budget).
ALTER TABLE bank_transaction DROP COLUMN category;
ALTER TABLE bank_transaction DROP COLUMN manual_category;
ALTER TABLE classification_rule DROP COLUMN category;
ALTER TABLE budget DROP CONSTRAINT uq_budget_category_currency;
ALTER TABLE budget DROP COLUMN category;

-- 4. Le chiavi esterne nuove, con le stesse regole di V12:
--    ON DELETE SET NULL: eliminando una categoria, movimenti e regole restano senza categoria;
--    i budget no: una categoria con un budget non si elimina.
--    ON UPDATE non serve più: l'id di una categoria non cambia mai.
ALTER TABLE bank_transaction ADD CONSTRAINT fk_transaction_category
    FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE SET NULL;
ALTER TABLE bank_transaction ADD CONSTRAINT fk_transaction_manual_category
    FOREIGN KEY (manual_category_id) REFERENCES category (id) ON DELETE SET NULL;
ALTER TABLE classification_rule ADD CONSTRAINT fk_rule_category
    FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE SET NULL;
ALTER TABLE budget ALTER COLUMN category_id SET NOT NULL;
ALTER TABLE budget ADD CONSTRAINT fk_budget_category
    FOREIGN KEY (category_id) REFERENCES category (id);
ALTER TABLE budget ADD CONSTRAINT uq_budget_category_currency UNIQUE (category_id, currency);

-- Indici sulle chiavi esterne: PostgreSQL non li crea da solo. Servono a trovare in fretta le righe che
-- usano una categoria (ad esempio quando la si elimina, il database deve cercare chi la usa).
CREATE INDEX ix_bank_transaction_category ON bank_transaction (category_id);
CREATE INDEX ix_bank_transaction_manual_category ON bank_transaction (manual_category_id);
CREATE INDEX ix_classification_rule_category ON classification_rule (category_id);

-- Il nome resta unico (senza badare a maiuscole e minuscole, indice di V12). L'indice esatto sul nome
-- serviva solo alle chiavi esterne di V12: non serve più.
DROP INDEX uq_category_name;
