CREATE TABLE catalog_templates (
    id              UUID            PRIMARY KEY,
    trade           VARCHAR(50)     NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    type            VARCHAR(20)     NOT NULL,
    unit            VARCHAR(20)     NOT NULL,
    suggested_price NUMERIC(15, 2)  NOT NULL,
    CONSTRAINT catalog_templates_trade_check
        CHECK (trade IN ('ELECTRICAL', 'PLUMBING', 'TILING', 'GENERAL', 'OTHER')),
    CONSTRAINT catalog_templates_type_check
        CHECK (type IN ('WORK', 'MATERIAL')),
    CONSTRAINT catalog_templates_unit_check
        CHECK (unit IN ('M2', 'M', 'PIECE', 'KG', 'HOUR', 'SET')),
    CONSTRAINT catalog_templates_price_check
        CHECK (suggested_price > 0)
);

CREATE INDEX idx_catalog_templates_trade ON catalog_templates (trade);
