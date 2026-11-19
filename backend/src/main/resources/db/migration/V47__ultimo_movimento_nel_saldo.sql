-- V47: l'ultimo movimento compreso nel saldo (docs/saldi.md, parte Q).
--
-- Alcune banche aggiornano il saldo prima di mostrare il movimento nell'elenco che Enable Banking scarica: il movimento
-- arriva dopo la lettura del saldo, con lo stesso giorno, e l'app lo credeva fuori dal saldo (contato due volte, e un
-- avviso falso del controllo). Enable Banking, insieme al saldo, può dare il riferimento dell'ultimo movimento compreso
-- (last_committed_transaction): quel movimento è dentro il saldo, quando arriva. Null se la banca non lo dà.
ALTER TABLE account ADD COLUMN balance_last_entry TEXT;

-- Lo stesso per i due saldi di un controllo (parte N).
ALTER TABLE balance_check ADD COLUMN from_last_entry TEXT;
ALTER TABLE balance_check ADD COLUMN to_last_entry TEXT;
