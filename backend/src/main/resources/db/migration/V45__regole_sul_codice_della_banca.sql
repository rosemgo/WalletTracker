-- V45: le regole possono guardare il codice dell'operazione mandato dalla banca (docs/codice-della-banca.md).
--
-- Con Enable Banking alcune banche mandano, oltre alla causale, un codice che dice che operazione è (il campo
-- bank_transaction_code del JSON originale): Fineco "I18-Interessi Passivi", ING "Accredito Stipendio/Pensione",
-- Revolut "CARD_PAYMENT". A volte la causale non lo dice: lo stipendio di ING arriva come "Bonifico N. … Note:
-- STIPENDIO MESE …", e una regola sul testo lo trova solo se chi paga scrive sempre la stessa nota.

-- 1. Dove cerca una regola: TEXT (causale + controparte, come finora) o BANK_CODE (il codice della banca). Le regole
--    che ci sono già guardano il testo.
ALTER TABLE classification_rule ADD COLUMN match_field VARCHAR(20) NOT NULL DEFAULT 'TEXT';
ALTER TABLE classification_rule ADD CONSTRAINT ck_classification_rule_match_field
    CHECK (match_field IN ('TEXT', 'BANK_CODE'));

-- 2. Regole di partenza sul codice della banca, per i codici che danno la categoria e che il testo spesso non dice:
--    lo stipendio, il prelievo con la carta, i bonus di una promozione. E una sul testo, per gli interessi che paghi
--    alla banca (uno scoperto, una linea di credito come il Credit Lombard di Fineco), che nessuna regola prendeva:
--    restavano spese senza categoria.
--
--    La categoria si cerca per chiave (builtin_key, V43): vale in italiano e in inglese, anche se l'utente l'ha
--    rinominata. Se l'ha eliminata, la regola c'è lo stesso, senza categoria, e decide solo il tipo.
--
--    I movimenti già importati prendono queste regole alla prossima classificazione: la sincronizzazione (al massimo
--    ogni 6 ore, o subito con "Aggiorna ora"), un'importazione o una modifica di una regola.
INSERT INTO classification_rule (name, priority, match_field, match_mode, pattern, amount_sign, result_type,
                                 category_id, builtin)
SELECT r.name, r.priority, r.match_field, r.match_mode, r.pattern, r.amount_sign, r.result_type, c.id, TRUE
FROM (VALUES
    ('Stipendio e pensione (codice della banca)', 50, 'BANK_CODE', 'CONTAINS',  'Accredito Stipendio', 'POSITIVE', 'INCOME',  'SALARY'),
    ('Prelievo con la carta (codice della banca)', 50, 'BANK_CODE', 'CONTAINS',  'Prelievo Carta',      'NEGATIVE', 'EXPENSE', 'CASH'),
    ('Bonus della banca (codice della banca)',     50, 'BANK_CODE', 'CONTAINS',  'Bonus Promozione',    'POSITIVE', 'INCOME',  'TAX_REFUNDS'),
    ('Premio Revolut (codice della banca)',        50, 'BANK_CODE', 'ALL_WORDS', 'REWARD',              'POSITIVE', 'INCOME',  'TAX_REFUNDS'),
    ('Interessi passivi',                          50, 'TEXT',      'CONTAINS',  'Interessi Passivi',   'NEGATIVE', 'EXPENSE', 'BANK_FEES')
) AS r (name, priority, match_field, match_mode, pattern, amount_sign, result_type, category_key)
LEFT JOIN category c ON c.builtin_key = r.category_key;
