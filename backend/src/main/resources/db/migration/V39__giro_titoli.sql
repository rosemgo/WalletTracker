-- V39: le "Ritenuta Fiscale … Qta/Val.nom." sono il GIRO TITOLI tra i tuoi conti, non tasse (docs/investimenti.md,
-- parte W). Corregge la V38.
--
-- La V38 le aveva fatte "Altre tasse investimenti", categoria "Ritenute sui ratei", pensando alle ritenute sul rateo di
-- una compravendita. Ma Fineco mette il rateo e la sua ritenuta DENTRO l'importo della compravendita (le contabili lo
-- mostrano: prezzo + rateo lordo − ritenuta sul rateo = totale). Queste righe nascono quando un titolo passa da un tuo
-- dossier a un altro (o cambia codice): la banca tassa il rateo maturato sul conto che cede e accredita la stessa cifra
-- sul conto che riceve. Per chi è titolare di tutti e due il saldo è zero: è un trasferimento tra i tuoi conti
-- (INTERNAL_TRANSFER), che non conta in nessun totale. Anche quando l'app vede un lato solo (lo storico di un conto
-- comincia dopo), non nasce un costo che non c'è.
-- Flyway esegue tutto il file in una transazione: se un passo fallisse, il database tornerebbe com'era.

-- 1. La regola di partenza della V38: trasferimento interno, senza categoria. Solo se è ancora com'era: se l'utente
--    l'ha cambiata, la sua scelta resta.
UPDATE classification_rule
SET name = 'Ritenuta sul rateo nel giro titoli tra i tuoi conti', result_type = 'INTERNAL_TRANSFER', category_id = NULL
WHERE builtin AND pattern = 'Ritenuta Fiscale Qta/Val.nom' AND match_mode = 'ALL_WORDS'
  AND result_type = 'INVESTMENT_TAX';

-- 2. I movimenti classificati da quella regola: trasferimento interno, subito (la prossima classificazione darebbe lo
--    stesso risultato). Non quelli con il tipo scelto a mano. Una categoria scelta a mano resta.
UPDATE bank_transaction t
SET type = 'INTERNAL_TRANSFER', category_id = t.manual_category_id
FROM classification_rule r
WHERE t.rule_id = r.id
  AND r.builtin AND r.pattern = 'Ritenuta Fiscale Qta/Val.nom' AND r.result_type = 'INTERNAL_TRANSFER'
  AND t.manual_type IS NULL
  AND t.type = 'INVESTMENT_TAX';

-- 3. La categoria "Ritenute sui ratei" non serve più: si elimina, ma solo se è quella creata dalla V38 (builtin), se
--    non la usa più niente e se non ha un budget. Una categoria con lo stesso nome creata dall'utente resta.
DELETE FROM category c
WHERE c.builtin AND lower(c.name) = 'ritenute sui ratei'
  AND NOT EXISTS (SELECT 1 FROM budget b WHERE b.category_id = c.id)
  AND NOT EXISTS (SELECT 1 FROM classification_rule r WHERE r.category_id = c.id)
  AND NOT EXISTS (SELECT 1 FROM bank_transaction t WHERE t.category_id = c.id OR t.manual_category_id = c.id);
