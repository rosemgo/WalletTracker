-- V37: il RUOLO di una categoria, per le poche categorie che il codice deve riconoscere (docs/investimenti.md, parte T).
--
-- Fino a V36 la pagina Investimenti riconosceva le ritenute dal NOME della categoria: una tassa con la categoria
-- "Ritenute" si toglieva dalle rendite. Ma il nome è dell'utente: rinominando "Ritenute" (o con un'app in un'altra
-- lingua) il netto delle rendite diventava sbagliato, senza nessun avviso. Ora il codice guarda il ruolo, che non
-- cambia mai; il nome resta libero, come per ogni categoria.
--
--   role = 'WITHHOLDING'   la categoria delle ritenute sulle rendite (enum CategoryRole)
--   role = NULL            tutte le altre: per il codice sono solo un nome
ALTER TABLE category ADD COLUMN role VARCHAR(20) CHECK (role IN ('WITHHOLDING'));

-- Un ruolo, una categoria sola. Le righe con NULL non si contano: in PostgreSQL due NULL non sono mai "uguali".
ALTER TABLE category ADD CONSTRAINT uq_category_role UNIQUE (role);

-- Quale categoria riceve il ruolo. Prima quella che oggi si chiama "Ritenute" (maiuscole e minuscole non contano): è
-- proprio quella che il codice riconosceva fino a ieri, quindi per chi non l'ha toccata non cambia niente. Se non c'è
-- (l'utente l'ha rinominata), quella che le regole di partenza delle ritenute ("Rit.ced", "Rit.div"... di V4)
-- assegnano: è la stessa categoria, con il nome nuovo. Se non c'è neanche quella, nessuna: come oggi, nessuna tassa si
-- toglie dalle rendite.
UPDATE category SET role = 'WITHHOLDING'
WHERE id = (
    SELECT candidate.id
    FROM (
        SELECT c.id, 1 AS preference FROM category c WHERE lower(c.name) = 'ritenute'
        UNION ALL
        SELECT r.category_id, 2 FROM classification_rule r
        WHERE r.builtin AND r.result_type = 'INVESTMENT_TAX' AND r.pattern ILIKE 'Rit.%' AND r.category_id IS NOT NULL
    ) AS candidate
    ORDER BY candidate.preference, candidate.id
    LIMIT 1
);
