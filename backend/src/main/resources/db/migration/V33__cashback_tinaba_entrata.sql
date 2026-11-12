-- V33: il cashback di Tinaba è un'entrata, non il rimborso di una spesa (docs/tinaba.md, parte J).
--
-- V31 lo classificava come spesa con il più in "Costi bancari", per ridurre il costo della ricarica Power. Ma il
-- cashback è un bonus di una promozione della banca, e restituisce più di quanto costano le ricariche: "Costi
-- bancari", una categoria di spesa, andava in attivo (−50 € di ricarica, +167 € di cashback in un anno). Da qui è
-- un'entrata, nella categoria di partenza "Rimborsi fiscali e bonus" (V12). La ricarica Power resta una spesa in
-- "Costi bancari": è un costo vero, nel momento in cui si fa.
--
-- Si cambia solo la regola ancora come l'avevano lasciata V31 e V32 (stesso nome e testo, spesa in "Costi
-- bancari"): se l'utente l'ha modificata, resta la sua. La categoria si cerca per nome, come in V31: se
-- "Rimborsi fiscali e bonus" è stata rinominata, la regola diventa un'entrata senza categoria.
--
-- I movimenti già classificati da questa regola si aggiornano qui sotto, subito: l'app riclassifica solo a una
-- sincronizzazione, un'importazione o una modifica, non all'avvio. Le correzioni a mano vincono e restano.
UPDATE classification_rule
SET result_type = 'INCOME',
    category_id = (SELECT id FROM category WHERE lower(name) = lower('Rimborsi fiscali e bonus'))
WHERE builtin = TRUE
  AND name = 'Cashback delle commissioni (Tinaba)'
  AND pattern = 'Tinaba Cashback'
  AND result_type = 'EXPENSE'
  AND category_id IS NOT DISTINCT FROM (SELECT id FROM category WHERE lower(name) = lower('Costi bancari'));

-- I movimenti già importati che questa regola aveva classificato: lo stesso calcolo di classifyAll(). Un tipo scelto
-- a mano (manual_type) li tiene fuori; una categoria scelta a mano resta la loro (BankTransaction.classify).
UPDATE bank_transaction t
SET type        = r.result_type,
    category_id = COALESCE(t.manual_category_id, r.category_id)
FROM classification_rule r
WHERE t.rule_id = r.id
  AND t.manual_type IS NULL
  AND r.builtin = TRUE
  AND r.name = 'Cashback delle commissioni (Tinaba)'
  AND r.result_type = 'INCOME';
