CREATE TABLE users (
    id              UUID            PRIMARY KEY,
    email           VARCHAR(255)    NOT NULL,
    password_hash   VARCHAR(255)    NOT NULL,
    full_name       VARCHAR(255)    NOT NULL,
    trade           VARCHAR(50)     NOT NULL,
    phone           VARCHAR(50)     NOT NULL,
    company_name    VARCHAR(255)    NOT NULL,
    logo_url        VARCHAR(512),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT users_email_unique UNIQUE (email),
    CONSTRAINT users_trade_check  CHECK (trade IN ('ELECTRICAL', 'PLUMBING', 'TILING', 'GENERAL', 'OTHER'))
);

CREATE INDEX idx_users_email ON users (email);
