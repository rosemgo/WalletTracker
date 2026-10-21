-- V21: più abbonamenti dallo stesso esercente, distinti dall'importo.
--
-- Lo stesso creditore può addebitare cose diverse (Fastweb: la SIM da 7,99 € e la linea di casa da 23,95 €).
-- Finora un abbonamento era "esercente + valuta" (V11): uno solo per esercente. Ora conta anche l'importo:
--   CONFIRMED: expected_amount c'era già (l'importo al momento della conferma);
--   DISMISSED: ora si salva anche l'importo della serie rifiutata, così "no" alla bolletta non nasconde la SIM.
--              Le righe DISMISSED di prima hanno expected_amount NULL: valgono per tutti gli importi, come prima.
-- Quando una serie di pagamenti "è" una decisione lo decide il codice (importi entro il 50%, vedi
-- SubscriptionService): una regola così non si scrive con un vincolo. Il vincolo nuovo impedisce solo i
-- doppioni esatti (due clic veloci sullo stesso suggerimento).
ALTER TABLE subscription DROP CONSTRAINT uq_subscription_key_currency;
ALTER TABLE subscription ADD CONSTRAINT uq_subscription_key_currency_amount
    UNIQUE (merchant_key, currency, expected_amount);
