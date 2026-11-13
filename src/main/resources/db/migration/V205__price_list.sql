-- «Поділитися прайсом» (webinar 2026-11-11: «а які у вас ціни?»). The master hands a client the prices of
-- the works HE ticks in his catalog, as a tokenised page and a PDF. A price list is the catalog's own rows,
-- never a copy: the page reads the live price, so a change in the catalog reaches the client at once.
--
-- Which rows go is a flag on the row itself. A deleted position leaves the price list with it, and a new
-- one stays out until the master ticks it.
ALTER TABLE catalog_items ADD COLUMN in_price_list BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN catalog_items.in_price_list IS
    'The master ticked this position for the price list he shares with clients (V205).';

-- One live link per master. «Закрити посилання» revokes it; the next share mints a new token. A revoked row
-- stays: it is also what tells the picker that the master has chosen once (his ticks are then his own).
CREATE TABLE price_list_link (
    id         UUID         PRIMARY KEY,
    owner_id   UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token      VARCHAR(128) NOT NULL UNIQUE,
    revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_price_list_link_owner_live ON price_list_link (owner_id) WHERE revoked = FALSE;

COMMENT ON TABLE price_list_link IS
    'The tokenised link to a master''s price list (V205); at most one live per owner.';
