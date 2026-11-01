-- V34: il saldo dei conti (docs/saldi.md).
--
-- Due provenienze, mai mescolate:
--   BANK    il saldo che la banca dà con Enable Banking (GET /accounts/{uid}/balances), letto a ogni
--           sincronizzazione: balance è il saldo, balance_date il giorno a cui si riferisce;
--   MANUAL  per un conto manuale (file CSV): il saldo che l'utente ha scritto dopo aver controllato l'ultimo movimento
--           importato. balance e balance_date sono il punto di partenza; il saldo di oggi è quel valore più i
--           movimenti importati dopo (BalanceService). Esatto, perché parte da un valore vero.
--
-- Un saldo non si calcola MAI dai soli movimenti: la storia di un conto nell'app non è mai completa (la banca dà un
-- anno al massimo, un file parte da quando lo si scarica), e una somma di movimenti sarebbe un numero sbagliato.
-- Se la banca non dà il saldo e l'utente non lo scrive, il saldo resta vuoto.
ALTER TABLE account ADD COLUMN balance            NUMERIC(19, 2);
ALTER TABLE account ADD COLUMN balance_date       DATE;
ALTER TABLE account ADD COLUMN balance_source     VARCHAR(10) CHECK (balance_source IN ('BANK', 'MANUAL'));
-- Quando è stato salvato: per un saldo scritto a mano separa i movimenti del giorno di partenza già contati da quelli
-- arrivati dopo con la stessa data.
ALTER TABLE account ADD COLUMN balance_updated_at TIMESTAMPTZ;
