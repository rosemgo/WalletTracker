-- V35: il plafond disponibile delle carte di credito (docs/saldi.md, parte I).
--
-- Per una carta la banca manda spesso solo il saldo "disponibile" (ITAV): quanto si può ancora spendere, cioè il
-- plafond meno le spese. Non è un saldo (V34 e la parte H non lo usano come saldo), ma è utile: si salva a parte, con
-- il suo nome, solo per i conti con il ruolo Carta di credito.
ALTER TABLE account ADD COLUMN available_credit      NUMERIC(19, 2);
ALTER TABLE account ADD COLUMN available_credit_date DATE;
