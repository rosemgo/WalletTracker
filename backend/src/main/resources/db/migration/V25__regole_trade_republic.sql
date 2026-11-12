-- V25: regole di partenza per le causali di Trade Republic (Fase 3, docs/31).
--
-- Con Enable Banking i movimenti di Trade Republic arrivano senza causale. Dal file "Esportazione transazioni"
-- (docs/31) ora la riceviamo, in inglese: queste regole la riconoscono, come quelle di V4 fanno per Fineco e ING.
-- Come tutte le regole di partenza, si possono disattivare o modificare dalla pagina Regole.
--
-- La categoria si cerca per nome (quelle di partenza di V12): se l'utente l'ha rinominata o eliminata, la regola
-- c'è lo stesso, senza categoria (category_id NULL), e decide solo il tipo.
INSERT INTO classification_rule (name, priority, match_mode, pattern, amount_sign, result_type, category_id, builtin)
SELECT r.name, r.priority, r.match_mode, r.pattern, r.amount_sign, r.result_type, c.id, TRUE
FROM (VALUES
    ('Interessi (Trade Republic)',              10, 'STARTS_WITH', 'Interest payment',           'POSITIVE', 'INVESTMENT_INCOME', 'Interessi'),
    ('Dividendo (Trade Republic)',              10, 'STARTS_WITH', 'Cash Dividend',              'POSITIVE', 'INVESTMENT_INCOME', 'Dividendi'),
    ('Dividendo annullato (Trade Republic)',    10, 'STARTS_WITH', 'Cancellation Cash Dividend', 'NEGATIVE', 'INVESTMENT_INCOME', 'Dividendi'),
    ('Imposta di bollo (Trade Republic)',       10, 'STARTS_WITH', 'Stamp Duty Tax',             'ANY',      'INVESTMENT_TAX',    'Imposta di bollo')
) AS r (name, priority, match_mode, pattern, amount_sign, result_type, category)
LEFT JOIN category c ON lower(c.name) = lower(r.category);
