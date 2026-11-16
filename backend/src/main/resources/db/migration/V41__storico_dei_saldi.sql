-- V41: lo storico dei saldi (docs/saldi.md, parte L).
--
-- Una "fotografia" al giorno del saldo di ogni conto che conta nella liquidità (non le carte di credito né i conti
-- esclusi): il saldo di oggi come lo mostra la pagina Conti. Si salva ogni ora mentre l'app è accesa, e nello stesso
-- giorno l'ultima vince. Sono i valori veri del grafico della liquidità; i giorni senza fotografia si stimano partendo
-- dal valore vero successivo e togliendo i movimenti nel mezzo (BalanceHistoryService).
--
-- Eliminando un conto manuale se ne vanno anche le sue fotografie (ON DELETE CASCADE).
CREATE TABLE balance_snapshot (
    account_id   BIGINT         NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    balance_date DATE           NOT NULL,
    amount       NUMERIC(19, 2) NOT NULL,
    PRIMARY KEY (account_id, balance_date)
);
