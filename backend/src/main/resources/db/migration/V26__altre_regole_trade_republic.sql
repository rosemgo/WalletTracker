-- V26: altre regole di partenza per le causali di Trade Republic (Fase 3, docs/31 parte L).
--
-- Un file di Trade Republic più lungo (da gennaio) ha mostrato causali che la V25 non conosceva: gli acquisti e le
-- vendite di titoli, le distribuzioni di capitale e il bollo annullato. La V25 è già applicata e non si modifica:
-- queste regole arrivano con una migrazione nuova, nello stesso modo.
--
-- "Capital Distribution": un provento come il dividendo (rimborso di capitale ai soci); come per i dividendi, a volte
-- viene annullato e ripagato, quindi vale con qualsiasi segno.
-- "Cancellation Stamp Duty Tax": il bollo restituito, con il segno più; la regola "Stamp Duty Tax" di V25 non lo
-- prende, perché quella guarda come INIZIA il testo.
INSERT INTO classification_rule (name, priority, match_mode, pattern, amount_sign, result_type, category_id, builtin)
SELECT r.name, r.priority, r.match_mode, r.pattern, r.amount_sign, r.result_type, c.id, TRUE
FROM (VALUES
    ('Acquisto titoli (Trade Republic)',            20, 'STARTS_WITH', 'Buy trade',                   'NEGATIVE', 'SECURITIES_BUY',    NULL),
    ('Vendita titoli (Trade Republic)',             20, 'STARTS_WITH', 'Sell trade',                  'POSITIVE', 'SECURITIES_SELL',   NULL),
    ('Distribuzione di capitale (Trade Republic)',  10, 'STARTS_WITH', 'Capital Distribution',        'ANY',      'INVESTMENT_INCOME', 'Dividendi'),
    ('Imposta di bollo annullata (Trade Republic)', 10, 'STARTS_WITH', 'Cancellation Stamp Duty Tax', 'ANY',      'INVESTMENT_TAX',    'Imposta di bollo')
) AS r (name, priority, match_mode, pattern, amount_sign, result_type, category)
LEFT JOIN category c ON lower(c.name) = lower(r.category);
