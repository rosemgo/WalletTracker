-- V38: le ritenute sul RATEO di un'obbligazione comprata o venduta escono dalle rendite (docs/investimenti.md, parte U).
--
-- Fineco le scrive "Ritenuta Fiscale <titolo> Qta/Val.nom. <quantità>". Quando compri un'obbligazione paghi al
-- venditore, dentro il prezzo, gli interessi maturati fino a quel giorno (il rateo), e la banca ti restituisce la
-- ritenuta su quel rateo (un importo positivo). Quando vendi, incassi il rateo dentro il prezzo e la banca trattiene la
-- ritenuta (negativo). Il rateo sta nella compravendita, non tra le rendite: la sua ritenuta va con lui. Toglierla dalle
-- cedole darebbe un netto che non è né i soldi arrivati come cedola né l'interesse vero.
--
-- Fino a V37 la regola di partenza "Ritenuta fiscale" (V4, contiene "Ritenuta Fiscale") le faceva ritenute sulle
-- rendite (WITHHOLDING_TAX). Ora una regola nuova, provata prima, le fa "Altre tasse investimenti" (INVESTMENT_TAX) con
-- la categoria nuova "Ritenute sui ratei". La regola di V4 resta per gli altri testi con "Ritenuta Fiscale".
-- Flyway esegue tutto il file in una transazione: se un passo fallisse, il database tornerebbe com'era.

-- 1. La categoria, tra quelle degli investimenti (dopo Imposta di bollo, 370). Se l'utente ne ha già una con questo
--    nome (maiuscole e minuscole non contano: indice di V12), si usa quella.
INSERT INTO category (name, kind, position, builtin)
SELECT 'Ritenute sui ratei', 'INVESTMENT', 375, TRUE
WHERE NOT EXISTS (SELECT 1 FROM category WHERE lower(name) = 'ritenute sui ratei');

-- 2. La regola: "contiene tutte le parole" Ritenuta, Fiscale, Qta, Val, nom (in qualsiasi ordine). La priorità 9 la
--    prova prima delle regole di partenza delle ritenute (10): vince sulla "Ritenuta fiscale" di V4.
INSERT INTO classification_rule (name, priority, match_mode, pattern, amount_sign, result_type, category_id, builtin)
SELECT 'Ritenuta sul rateo (compravendita di obbligazioni)', 9, 'ALL_WORDS', 'Ritenuta Fiscale Qta/Val.nom', 'ANY',
       'INVESTMENT_TAX', c.id, TRUE
FROM category c WHERE lower(c.name) = 'ritenute sui ratei';

-- 3. I movimenti che la regola di V4 aveva fatto ritenute sulle rendite e che ora sono della regola nuova: il tipo e la
--    categoria nuovi, subito (la prossima classificazione darebbe lo stesso risultato). Non quelli con il tipo scelto a
--    mano: la scelta dell'utente vince sempre. Una categoria scelta a mano resta.
UPDATE bank_transaction t
SET type = 'INVESTMENT_TAX',
    rule_id = new_rule.id,
    category_id = COALESCE(t.manual_category_id, new_rule.category_id)
FROM classification_rule new_rule, classification_rule old_rule
WHERE new_rule.builtin AND new_rule.pattern = 'Ritenuta Fiscale Qta/Val.nom'
  AND old_rule.builtin AND old_rule.pattern = 'Ritenuta Fiscale'
  AND t.rule_id = old_rule.id
  AND t.manual_type IS NULL
  AND t.type = 'WITHHOLDING_TAX'
  AND t.description ILIKE '%ritenuta fiscale%'
  AND t.description ILIKE '%qta/val.nom%';
