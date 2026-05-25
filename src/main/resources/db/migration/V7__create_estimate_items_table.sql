CREATE TABLE estimate_items (
    id          UUID            PRIMARY KEY,
    estimate_id UUID            NOT NULL,
    type        VARCHAR(20)     NOT NULL,
    name        VARCHAR(255)    NOT NULL,
    unit        VARCHAR(20)     NOT NULL,
    quantity    NUMERIC(15, 3)  NOT NULL,
    unit_price  NUMERIC(15, 2)  NOT NULL,
    sort_order  INTEGER         NOT NULL DEFAULT 0,
    CONSTRAINT estimate_items_estimate_fk
        FOREIGN KEY (estimate_id) REFERENCES estimates(id) ON DELETE CASCADE,
    CONSTRAINT estimate_items_type_check
        CHECK (type IN ('WORK', 'MATERIAL')),
    CONSTRAINT estimate_items_unit_check
        CHECK (unit IN ('M2', 'M', 'PIECE', 'KG', 'HOUR', 'SET')),
    CONSTRAINT estimate_items_quantity_check
        CHECK (quantity > 0),
    CONSTRAINT estimate_items_unit_price_check
        CHECK (unit_price > 0)
);

CREATE INDEX idx_estimate_items_estimate_id ON estimate_items (estimate_id);
