-- V37: le ritenute sulle rendite diventano un TIPO di movimento, WITHHOLDING_TAX (docs/investimenti.md, parte T).
--
-- Fino a V36 una ritenuta era un movimento di tipo INVESTMENT_TAX con la categoria "Ritenute", e la pagina Investimenti
-- la riconosceva dal NOME della categoria. Ma il nome è dell'utente: rinominando "Ritenute" (o con l'app in un'altra
-- lingua) le ritenute finivano tra le altre tasse e il netto delle rendite era sbagliato, senza nessun avviso.
-- Ora è il tipo a dirlo, come per tutto il resto dell'app: il tipo decide, la categoria è solo un'etichetta.
--
--   WITHHOLDING_TAX   ritenute su cedole, dividendi, interessi: si tolgono dalle rendite (il netto)
--   INVESTMENT_TAX    le altre tasse (bollo, capital gain, Tobin tax): restano a parte
--
-- Con il tipo, la categoria "Ritenute" non serve più: si toglie dalle regole e dai movimenti, e si elimina.
-- Le colonne dei tipi sono testo (VARCHAR(30), V4) senza un elenco nel database: il tipo nuovo non le cambia.
-- Flyway esegue tutto il file in una transazione: se un passo fallisse, il database tornerebbe com'era.

-- 1. Quale era la categoria delle ritenute, una volta sola (è l'ultima volta che si guarda un nome). Prima quella che
--    si chiama "Ritenute" (maiuscole e minuscole non contano): proprio quella che il codice riconosceva fino a V36. Se
--    non c'è (l'utente l'ha rinominata), quella che assegnano le regole di partenza delle ritenute ("Rit.ced",
--    "Rit.div"... di V4): la stessa, con il nome nuovo. Se non c'è neanche quella, nessuna: non cambia niente, come
--    prima nessuna tassa si toglieva dalle rendite.
CREATE TEMPORARY TABLE withholding_category ON COMMIT DROP AS
SELECT candidate.id
FROM (
    SELECT c.id, 1 AS preference FROM category c WHERE lower(c.name) = 'ritenute'
    UNION ALL
    SELECT r.category_id, 2 FROM classification_rule r
    WHERE r.builtin AND r.result_type = 'INVESTMENT_TAX' AND r.pattern ILIKE 'Rit%' AND r.category_id IS NOT NULL
) AS candidate
ORDER BY candidate.preference, candidate.id
LIMIT 1;

-- 2. Le regole: quelle che davano "tassa + categoria delle ritenute" (le quattro di partenza e quelle dell'utente) ora
--    danno il tipo nuovo, senza categoria.
UPDATE classification_rule
SET result_type = 'WITHHOLDING_TAX', category_id = NULL
WHERE result_type = 'INVESTMENT_TAX' AND category_id IN (SELECT id FROM withholding_category);

-- 3. Le scelte fatte a mano: un tipo "tassa" scelto a mano, su un movimento con la categoria delle ritenute (scelta a
--    mano o data da una regola), voleva dire "è una ritenuta". Diventa il tipo nuovo. Prima di toccare category_id.
UPDATE bank_transaction
SET manual_type = 'WITHHOLDING_TAX'
WHERE manual_type = 'INVESTMENT_TAX'
  AND (category_id IN (SELECT id FROM withholding_category)
       OR manual_category_id IN (SELECT id FROM withholding_category));

-- 4. I movimenti già classificati come ritenute: il tipo nuovo, senza categoria. Così la pagina è giusta subito, senza
--    aspettare la prossima classificazione (che darebbe lo stesso risultato, con le regole del passo 2).
UPDATE bank_transaction
SET type = 'WITHHOLDING_TAX', category_id = NULL
WHERE type = 'INVESTMENT_TAX' AND category_id IN (SELECT id FROM withholding_category);

UPDATE bank_transaction
SET manual_category_id = NULL
WHERE manual_category_id IN (SELECT id FROM withholding_category);

-- 5. La categoria non serve più: si elimina. Non se c'è un budget su di lei (un budget non si perde senza che l'utente
--    lo decida: la pagina Categorie fa lo stesso). Movimenti e regole che la usassero ancora (con un altro tipo)
--    restano senza categoria: ON DELETE SET NULL di V13.
DELETE FROM category
WHERE id IN (SELECT id FROM withholding_category)
  AND NOT EXISTS (SELECT 1 FROM budget b WHERE b.category_id = category.id);
