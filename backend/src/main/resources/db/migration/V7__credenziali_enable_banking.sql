-- Fase 2, passo 5: le credenziali di Enable Banking salvate dalla dashboard (configurazione guidata).
--
-- Una sola riga (id = 1): una installazione di WalletTracker usa una sola applicazione Enable Banking.
-- Il CHECK impedisce di inserirne una seconda per sbaglio.
--
-- ATTENZIONE: private_key_pem è la chiave privata, in chiaro. Chi può leggere il database (o un suo
-- backup) può usarla. Il database è raggiungibile solo da questo PC; tratta i backup come un segreto.
CREATE TABLE enable_banking_settings (
    id              INT          PRIMARY KEY CHECK (id = 1),
    application_id  TEXT         NOT NULL,
    private_key_pem TEXT         NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
