-- V4: la classificazione dei movimenti.
--
-- Principio (docs/03): il MOTORE è nel codice ed è uguale per tutti; la CONFIGURAZIONE (regole,
-- correzioni manuali, conti) sta nel database ed è di ogni utente. Le regole qui sotto sono solo
-- "regole di partenza": ogni utente potrà disattivarle, modificarle o aggiungerne di sue.

-- 1. Il risultato della classificazione, su ogni movimento.
ALTER TABLE bank_transaction ADD COLUMN type             VARCHAR(30);  -- es. EXPENSE, INTERNAL_TRANSFER (enum TransactionType)
ALTER TABLE bank_transaction ADD COLUMN category         TEXT;         -- es. "Cedole", facoltativa
ALTER TABLE bank_transaction ADD COLUMN rule_id          BIGINT;       -- la regola che ha deciso, se c'è
ALTER TABLE bank_transaction ADD COLUMN transfer_peer_id BIGINT;       -- l'altro lato di un trasferimento tra conti propri
ALTER TABLE bank_transaction ADD COLUMN manual_type      VARCHAR(30);  -- correzione manuale: se c'è, vince sempre

-- 2. Le regole: "se il testo del movimento contiene X, allora è di tipo Y".
CREATE TABLE classification_rule (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        TEXT        NOT NULL,                  -- nome leggibile, mostrato nella dashboard
    priority    INT         NOT NULL DEFAULT 100,      -- le regole si provano in ordine di priorità crescente
    enabled     BOOLEAN     NOT NULL DEFAULT TRUE,
    match_mode  VARCHAR(20) NOT NULL,                  -- CONTAINS (contiene) o STARTS_WITH (inizia con)
    pattern     TEXT        NOT NULL,                  -- il testo da cercare (maiuscole/minuscole non contano)
    amount_sign VARCHAR(10) NOT NULL DEFAULT 'ANY',    -- ANY, NEGATIVE (uscite), POSITIVE (entrate)
    result_type VARCHAR(30) NOT NULL,                  -- il tipo da assegnare
    category    TEXT,                                  -- la categoria da assegnare, facoltativa
    builtin     BOOLEAN     NOT NULL DEFAULT FALSE     -- TRUE = regola di partenza fornita dal progetto
);

-- 3. Regole di partenza. Sono scritte per le causali che conosciamo (Fineco, ING): valgono su
--    QUALSIASI conto, perché guardano il testo del movimento e non la banca.
INSERT INTO classification_rule (name, priority, match_mode, pattern, amount_sign, result_type, category, builtin) VALUES
    ('Cedola',                         10, 'STARTS_WITH', 'Ced.su',                         'POSITIVE', 'INVESTMENT_INCOME', 'Cedole',            TRUE),
    ('Dividendo',                      10, 'STARTS_WITH', 'Div.su',                         'POSITIVE', 'INVESTMENT_INCOME', 'Dividendi',         TRUE),
    ('Interessi sulla liquidità',      10, 'STARTS_WITH', 'Interessi Portaf',               'POSITIVE', 'INVESTMENT_INCOME', 'Interessi',         TRUE),
    ('Ritenuta su cedola',             10, 'STARTS_WITH', 'Rit.ced',                        'ANY',      'INVESTMENT_TAX',    'Ritenute',          TRUE),
    ('Ritenuta su dividendo',          10, 'STARTS_WITH', 'Rit.div',                        'ANY',      'INVESTMENT_TAX',    'Ritenute',          TRUE),
    ('Ritenuta fiscale',               10, 'CONTAINS',    'Ritenuta Fiscale',               'ANY',      'INVESTMENT_TAX',    'Ritenute',          TRUE),
    ('Ritenuta su interessi',          10, 'STARTS_WITH', 'Rit. Fisc',                      'ANY',      'INVESTMENT_TAX',    'Ritenute',          TRUE),
    ('Imposta sul capital gain',       10, 'CONTAINS',    'Imposta Sostitutiva',            'ANY',      'INVESTMENT_TAX',    'Capital gain',      TRUE),
    ('Tobin tax',                      10, 'CONTAINS',    'Tobin Tax',                      'ANY',      'INVESTMENT_TAX',    'Tobin tax',         TRUE),
    ('Imposta di bollo sul dossier',   10, 'CONTAINS',    'imposta di bollo Dossier',       'ANY',      'INVESTMENT_TAX',    'Imposta di bollo',  TRUE),
    ('Acquisto titoli',                20, 'CONTAINS',    'Compravendita Titoli',           'NEGATIVE', 'SECURITIES_BUY',    NULL,                TRUE),
    ('Vendita titoli',                 20, 'CONTAINS',    'Compravendita Titoli',           'POSITIVE', 'SECURITIES_SELL',   NULL,                TRUE),
    ('Saldo carta di credito',         30, 'CONTAINS',    'Estratto conto carta di credito','NEGATIVE', 'INTERNAL_TRANSFER', 'Saldo carta',       TRUE),
    ('Trasferimento a un proprio conto',40,'CONTAINS',    'Trasferimento a mio conto',      'ANY',      'INTERNAL_TRANSFER', NULL,                TRUE),
    ('Imposta di bollo sul conto',     50, 'CONTAINS',    'Imposta di bollo',               'ANY',      'EXPENSE',           'Costi bancari',     TRUE),
    ('Canone del conto',               50, 'CONTAINS',    'Canone Mensile',                 'ANY',      'EXPENSE',           'Costi bancari',     TRUE);
