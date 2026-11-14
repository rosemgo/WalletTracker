-- V41: la lingua dell'app (docs/lingue-2-inglese.md).
--
-- 1. La lingua è un'impostazione dell'installazione: una sola riga (id = 1), come per l'IA (V18).
--    language NULL = non ancora scelta: succede solo in un'installazione nuova, finché non si passa dalla prima
--    schermata. Chi usa già l'app (ha fatto la scelta del PIN, o ha conti o movimenti) resta in italiano.
CREATE TABLE app_settings (
    id       INT        PRIMARY KEY CHECK (id = 1),
    language VARCHAR(5)
);

INSERT INTO app_settings (id, language)
SELECT 1,
       CASE
           WHEN EXISTS (SELECT 1 FROM app_pin)
                OR EXISTS (SELECT 1 FROM account)
                OR EXISTS (SELECT 1 FROM bank_transaction) THEN 'it'
       END;

-- 2. Le categorie di partenza (V12) hanno una chiave fissa: dice QUALE categoria di partenza è, in ogni lingua.
--    Il nome resta un dato dell'utente, che lo può cambiare. Cambiando lingua, l'app rinomina solo quelle che hanno
--    ancora il nome di partenza (BuiltinCategory nel backend): "Spesa alimentare" diventa "Groceries" e viceversa.
--    La chiave si assegna dalla posizione, che l'utente non può cambiare: così la riceve anche una categoria di
--    partenza già rinominata (che, avendo un nome suo, non verrà toccata).
ALTER TABLE category ADD COLUMN builtin_key VARCHAR(40);

UPDATE category
SET builtin_key = CASE position
        WHEN 10 THEN 'GROCERIES'
        WHEN 20 THEN 'EATING_OUT'
        WHEN 30 THEN 'BILLS'
        WHEN 40 THEN 'TRANSPORT'
        WHEN 50 THEN 'TRAVEL'
        WHEN 60 THEN 'SHOPPING'
        WHEN 70 THEN 'ELECTRONICS'
        WHEN 80 THEN 'LEISURE'
        WHEN 90 THEN 'WELLBEING'
        WHEN 100 THEN 'HEALTHCARE'
        WHEN 110 THEN 'FAMILY'
        WHEN 120 THEN 'EDUCATION'
        WHEN 130 THEN 'INSURANCE'
        WHEN 140 THEN 'TAXES'
        WHEN 150 THEN 'WORK'
        WHEN 160 THEN 'CASH'
        WHEN 170 THEN 'BANK_FEES'
        WHEN 180 THEN 'OTHER_EXPENSES'
        WHEN 210 THEN 'SALARY'
        WHEN 220 THEN 'GIFTS'
        WHEN 230 THEN 'TAX_REFUNDS'
        WHEN 240 THEN 'OTHER_INCOME'
        WHEN 310 THEN 'COUPONS'
        WHEN 320 THEN 'DIVIDENDS'
        WHEN 330 THEN 'INTEREST'
        WHEN 350 THEN 'CAPITAL_GAIN'
        WHEN 360 THEN 'TOBIN_TAX'
        WHEN 370 THEN 'STAMP_DUTY'
        WHEN 410 THEN 'CARD_SETTLEMENT'
    END
WHERE builtin;

-- Una chiave, una categoria (le categorie dell'utente non ne hanno: più NULL sono ammessi).
CREATE UNIQUE INDEX uq_category_builtin_key ON category (builtin_key);
