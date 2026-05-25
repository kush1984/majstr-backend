CREATE TABLE catalog_items (
    id              UUID            PRIMARY KEY,
    owner_id        UUID            NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    type            VARCHAR(20)     NOT NULL,
    unit            VARCHAR(20)     NOT NULL,
    default_price   NUMERIC(15, 2)  NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT catalog_items_owner_fk
        FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT catalog_items_type_check
        CHECK (type IN ('WORK', 'MATERIAL')),
    CONSTRAINT catalog_items_unit_check
        CHECK (unit IN ('M2', 'M', 'PIECE', 'KG', 'HOUR', 'SET')),
    CONSTRAINT catalog_items_price_check
        CHECK (default_price > 0)
);

CREATE INDEX idx_catalog_items_owner_id ON catalog_items (owner_id);
CREATE INDEX idx_catalog_items_type     ON catalog_items (type);
