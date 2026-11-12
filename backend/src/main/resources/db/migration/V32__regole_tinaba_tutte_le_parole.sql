-- V32: le regole di Tinaba (V31) cercano tutte le parole, in qualsiasi punto (docs/tinaba.md, parte I).
--
-- Con V31 cercavano una frase attaccata ("contiene"). Il testo su cui guardano è "causale + controparte", e da quando
-- l'importazione aggiunge alla causale una seconda colonna (parte H) una frase che scavalca il punto in cui causale e
-- controparte si toccano può non trovarsi più: "Ricarica effettuata · <messaggio> Tinaba Power" non contiene
-- "Ricarica effettuata Tinaba Power". Con "tutte le parole" l'ordine e le parole in mezzo non contano.
--
-- Si cambiano solo le regole ancora come le aveva create V31: se l'utente ne ha modificata una, resta la sua.
UPDATE classification_rule
SET match_mode = 'ALL_WORDS'
WHERE builtin = TRUE
  AND match_mode = 'CONTAINS'
  AND (name, pattern) IN (
      ('Versamento nel Robo advisor (Tinaba)', 'versamento Robo advisor'),
      ('Credito Power (Tinaba)',               'Ricarica effettuata Tinaba Power'),
      ('Cashback delle commissioni (Tinaba)',  'Tinaba Cashback'));
