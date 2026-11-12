-- V36: il logo di ogni conto (docs/loghi.md), piccolo nell'angolo del cerchio dei movimenti.
--
-- Due provenienze:
--   BANK    per un conto collegato: il logo della banca, dall'elenco delle banche di Enable Banking (campo "logo"),
--           scaricato UNA volta dal backend e servito da qui. Il browser non lo chiede mai a un sito esterno: la pagina
--           accetta solo immagini sue (img-src 'self'), e così nessuno fuori sa quali banche usi;
--   UPLOAD  per un conto manuale: un'immagine caricata dall'utente (PNG, JPEG o WebP).
--
-- data NULL con source BANK: la banca è stata cercata (checked_at) ma il logo non c'era; si riprova dopo un po'.
CREATE TABLE account_logo (
    account_id   BIGINT PRIMARY KEY REFERENCES account (id) ON DELETE CASCADE,
    source       VARCHAR(10)  NOT NULL CHECK (source IN ('BANK', 'UPLOAD')),
    content_type VARCHAR(40),
    data         BYTEA,
    checked_at   TIMESTAMPTZ  NOT NULL
);
