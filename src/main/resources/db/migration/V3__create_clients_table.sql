CREATE TABLE clients (
    id          UUID            PRIMARY KEY,
    owner_id    UUID            NOT NULL,
    full_name   VARCHAR(255)    NOT NULL,
    phone       VARCHAR(50)     NOT NULL,
    address     VARCHAR(512),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT clients_owner_fk
        FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_clients_owner_id ON clients (owner_id);
