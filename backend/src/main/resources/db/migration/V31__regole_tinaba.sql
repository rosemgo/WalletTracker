-- V31: regole di partenza per il file dei movimenti di Tinaba (docs/tinaba.md).
--
-- Tinaba è un wallet: riceve i bonifici dal conto corrente e da lì versa nel Robo advisor (il PAC), compra il credito
-- "Power" con cui paga le commissioni, e riceve il cashback che le rimborsa. Il suo file CSV non ha il segno degli
-- importi: l'importazione lo ricava dalla descrizione (parole "ricevuto"). Queste regole danno a ogni riga il tipo
-- giusto; il testo su cui guardano è "descrizione + controparte" (ClassificationService.textOf).
--
-- * Il versamento nel Robo advisor: soldi che dal wallet diventano investimento. Acquisto titoli, non una spesa;
--   sul conto Tinaba (ruolo Investimenti) non si somma una seconda volta al versato (docs/investimenti.md, parte B).
-- * L'acquisto del credito Power: una spesa, il costo delle commissioni (Costi bancari).
-- * Il cashback delle commissioni: un RIMBORSO di quella spesa. Nell'app un rimborso è una spesa con l'importo
--   positivo, nella stessa categoria: la riduce. Così "Costi bancari" mostra solo quello che resta da pagare.
--
-- Il bonifico che arriva dal conto corrente ("Ricevuto da ...") non ha bisogno di regole: si abbina all'uscita dal
-- conto corrente (stesso importo, stesso giorno), e il ruolo del conto Tinaba ne fa un versamento.
INSERT INTO classification_rule (name, priority, match_mode, pattern, amount_sign, result_type, category_id, builtin)
SELECT r.name, r.priority, r.match_mode, r.pattern, r.amount_sign, r.result_type, c.id, TRUE
FROM (VALUES
    ('Versamento nel Robo advisor (Tinaba)', 20, 'CONTAINS', 'versamento Robo advisor',          'NEGATIVE', 'SECURITIES_BUY', NULL),
    ('Credito Power (Tinaba)',               20, 'CONTAINS', 'Ricarica effettuata Tinaba Power', 'NEGATIVE', 'EXPENSE',        'Costi bancari'),
    ('Cashback delle commissioni (Tinaba)',  20, 'CONTAINS', 'Tinaba Cashback',                  'POSITIVE', 'EXPENSE',        'Costi bancari')
) AS r (name, priority, match_mode, pattern, amount_sign, result_type, category)
LEFT JOIN category c ON lower(c.name) = lower(r.category);
