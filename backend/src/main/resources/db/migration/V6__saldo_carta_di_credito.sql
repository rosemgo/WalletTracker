-- Il nuovo tipo CARD_SETTLEMENT (pagamento del saldo di una carta di credito) e il nuovo ruolo di conto
-- CREDIT_CARD non richiedono colonne nuove: tipi e ruoli sono salvati come testo.
--
-- Aggiorniamo solo la regola di partenza del saldo della carta, che finora dava INTERNAL_TRANSFER.
-- La aggiorniamo solo se è ancora com'era all'origine (builtin e con il risultato originale):
-- se l'utente l'ha modificata, la lasciamo stare.
UPDATE classification_rule
SET result_type = 'CARD_SETTLEMENT'
WHERE builtin = TRUE
  AND pattern = 'Estratto conto carta di credito'
  AND result_type = 'INTERNAL_TRANSFER';
