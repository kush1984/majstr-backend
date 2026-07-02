-- V37 — real payments (monobank acquiring). Adds a per-user subscription expiry and
-- a payments ledger. A billing-granted PRO carries an expiry (plan_expires_at);
-- admin-granted PRO leaves it NULL (never auto-expires — admin owns that plan).
-- On success a verified webhook flips the payment to SUCCESS and extends PRO; a
-- daily job downgrades to FREE once plan_expires_at (+ grace) passes.

ALTER TABLE users ADD COLUMN plan_expires_at TIMESTAMPTZ;

CREATE TABLE payments (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider    VARCHAR(20)  NOT NULL CHECK (provider IN ('MONOBANK')),
    invoice_id  VARCHAR(100) UNIQUE,
    amount      NUMERIC(15, 2) NOT NULL CHECK (amount >= 0),
    ccy         INTEGER      NOT NULL,
    status      VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILURE', 'EXPIRED', 'REVERSED')),
    plan        VARCHAR(20)  NOT NULL CHECK (plan IN ('FREE', 'PRO', 'TEAM')),
    days        INTEGER      NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    paid_at     TIMESTAMPTZ
);

CREATE INDEX idx_payments_user_id ON payments (user_id);
CREATE INDEX idx_payments_created_at ON payments (created_at);
-- The expiry sweep scans only subscriptions that actually have an end date.
CREATE INDEX idx_users_plan_expires_at ON users (plan_expires_at) WHERE plan_expires_at IS NOT NULL;
