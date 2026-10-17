-- V12: l'elenco delle categorie.
--
-- Fino a V11 le categorie erano testo libero: "Spesa", "spesa" e "Spesa alimentare" diventavano tre categorie
-- diverse. Da qui c'è una tabella con un elenco di partenza (come le regole di partenza di V4): l'utente lo
-- può rinominare, ampliare e accorciare. Il motore resta uguale per tutti, la configurazione sta qui.
--
-- Ogni categoria ha un GRUPPO, che dice a quali movimenti si addice:
--   EXPENSE    spese (e rimborsi di spese)       es. Spesa alimentare
--   INCOME     entrate                           es. Stipendio e pensione
--   INVESTMENT investimenti                      es. Dividendi, Ritenute
--   OTHER      il resto (trasferimenti, carte)    es. Saldo carta
CREATE TABLE category (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name     TEXT        NOT NULL,
    kind     VARCHAR(12) NOT NULL CHECK (kind IN ('EXPENSE', 'INCOME', 'INVESTMENT', 'OTHER')),
    position INT         NOT NULL DEFAULT 1000,  -- l'ordine nei menu (le categorie dell'utente vanno in fondo)
    builtin  BOOLEAN     NOT NULL DEFAULT FALSE  -- TRUE = categoria di partenza fornita dal progetto
);

-- Il nome è unico. Il primo indice serve alle chiavi esterne qui sotto; il secondo impedisce i quasi-doppioni
-- che differiscono solo per maiuscole e minuscole ("Spesa" e "spesa").
CREATE UNIQUE INDEX uq_category_name ON category (name);
CREATE UNIQUE INDEX uq_category_name_lower ON category (lower(name));

-- 1. L'elenco di partenza.
INSERT INTO category (name, kind, position, builtin) VALUES
    ('Spesa alimentare',               'EXPENSE',    10, TRUE),
    ('Bar e ristoranti',               'EXPENSE',    20, TRUE),
    ('Spese fisse e utenze',           'EXPENSE',    30, TRUE),
    ('Trasporti e auto',               'EXPENSE',    40, TRUE),
    ('Viaggi e vacanze',               'EXPENSE',    50, TRUE),
    ('Shopping',                       'EXPENSE',    60, TRUE),
    ('Multimedia ed elettronica',      'EXPENSE',    70, TRUE),
    ('Intrattenimento e tempo libero', 'EXPENSE',    80, TRUE),
    ('Salute e benessere',             'EXPENSE',    90, TRUE),
    ('Cure sanitarie e farmacia',      'EXPENSE',   100, TRUE),
    ('Famiglia e amici',               'EXPENSE',   110, TRUE),
    ('Istruzione e formazione',        'EXPENSE',   120, TRUE),
    ('Assicurazioni e finanza',        'EXPENSE',   130, TRUE),
    ('Tasse e multe',                  'EXPENSE',   140, TRUE),
    ('Spese di lavoro',                'EXPENSE',   150, TRUE),
    ('Contanti e prelievi',            'EXPENSE',   160, TRUE),
    ('Costi bancari',                  'EXPENSE',   170, TRUE),
    ('Altre spese',                    'EXPENSE',   180, TRUE),
    ('Stipendio e pensione',           'INCOME',    210, TRUE),
    ('Regali e altre persone',         'INCOME',    220, TRUE),
    ('Rimborsi fiscali e bonus',       'INCOME',    230, TRUE),
    ('Altre entrate',                  'INCOME',    240, TRUE),
    ('Cedole',                         'INVESTMENT', 310, TRUE),
    ('Dividendi',                      'INVESTMENT', 320, TRUE),
    ('Interessi',                      'INVESTMENT', 330, TRUE),
    ('Ritenute',                       'INVESTMENT', 340, TRUE),
    ('Capital gain',                   'INVESTMENT', 350, TRUE),
    ('Tobin tax',                      'INVESTMENT', 360, TRUE),
    ('Imposta di bollo',               'INVESTMENT', 370, TRUE),
    ('Saldo carta',                    'OTHER',     410, TRUE);

-- 2. Le categorie che l'utente aveva già scritto (passi precedenti) non si perdono: entrano nell'elenco.
--    Il gruppo si ricava dal tipo della regola che le usa; quelle usate solo nei movimenti o nei budget
--    diventano spese. DISTINCT ON (lower(...)) ne tiene una sola tra "Viaggi" e "viaggi": la prima secondo ORDER BY.
INSERT INTO category (name, kind)
SELECT DISTINCT ON (lower(used.name)) used.name, used.kind
FROM (
    SELECT category AS name,
           CASE
               WHEN result_type = 'INCOME' THEN 'INCOME'
               WHEN result_type = 'EXPENSE' THEN 'EXPENSE'
               WHEN result_type LIKE 'INVESTMENT%' OR result_type LIKE 'SECURITIES%' THEN 'INVESTMENT'
               ELSE 'OTHER'
           END AS kind,
           1 AS priority
    FROM classification_rule
    UNION ALL SELECT manual_category, 'EXPENSE', 2 FROM bank_transaction
    UNION ALL SELECT category, 'EXPENSE', 3 FROM bank_transaction
    UNION ALL SELECT category, 'EXPENSE', 4 FROM budget
) AS used
WHERE used.name IS NOT NULL
  AND lower(used.name) NOT IN (SELECT lower(name) FROM category)
-- a parità, meglio la scritta con l'iniziale maiuscola ("Abbonamenti" invece di "abbonamenti")
ORDER BY lower(used.name), used.priority, (left(used.name, 1) = upper(left(used.name, 1))) DESC;

-- 3. Le scritte che differiscono solo per maiuscole/minuscole ("spesa" se l'elenco ha "Spesa") prendono il nome
--    dell'elenco, così le chiavi esterne qui sotto trovano sempre la loro categoria.
UPDATE classification_rule r SET category = c.name FROM category c
WHERE lower(r.category) = lower(c.name) AND r.category <> c.name;
UPDATE bank_transaction t SET manual_category = c.name FROM category c
WHERE lower(t.manual_category) = lower(c.name) AND t.manual_category <> c.name;
UPDATE bank_transaction t SET category = c.name FROM category c
WHERE lower(t.category) = lower(c.name) AND t.category <> c.name;
UPDATE budget b SET category = c.name FROM category c
WHERE lower(b.category) = lower(c.name) AND b.category <> c.name;

-- 4. Le chiavi esterne: una categoria usata da movimenti, regole o budget deve esistere nell'elenco.
--    ON UPDATE CASCADE: rinominando una categoria, il database aggiorna da solo tutte le righe che la usano.
--    ON DELETE SET NULL: eliminandola, movimenti e regole restano senza categoria.
--    Per i budget nessuna regola ON DELETE: una categoria con un budget non si elimina (prima va tolto il budget).
ALTER TABLE classification_rule ADD CONSTRAINT fk_rule_category
    FOREIGN KEY (category) REFERENCES category (name) ON UPDATE CASCADE ON DELETE SET NULL;
ALTER TABLE bank_transaction ADD CONSTRAINT fk_transaction_category
    FOREIGN KEY (category) REFERENCES category (name) ON UPDATE CASCADE ON DELETE SET NULL;
ALTER TABLE bank_transaction ADD CONSTRAINT fk_transaction_manual_category
    FOREIGN KEY (manual_category) REFERENCES category (name) ON UPDATE CASCADE ON DELETE SET NULL;
ALTER TABLE budget ADD CONSTRAINT fk_budget_category
    FOREIGN KEY (category) REFERENCES category (name) ON UPDATE CASCADE;
