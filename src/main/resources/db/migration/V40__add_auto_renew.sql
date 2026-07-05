-- V40 — subscription auto-renewal via monobank card tokenization. At checkout the
-- master can opt in; the card is tokenized (saveCardData → wallet), and a scheduled
-- job charges the saved token before the period ends (merchant-initiated). Trust
-- guards live in code: explicit opt-in, T-3 warning email, one-tap cancel, retries
-- within grace before downgrade. The token is SENSITIVE — never in API/logs.

ALTER TABLE users ADD COLUMN auto_renew BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE users ADD COLUMN card_token VARCHAR(255);          -- sensitive; never exposed
ALTER TABLE users ADD COLUMN card_mask  VARCHAR(40);           -- masked PAN for display only
ALTER TABLE users ADD COLUMN wallet_id  VARCHAR(64);           -- monobank wallet id (tokenization)
ALTER TABLE users ADD COLUMN renew_reminder_sent_at TIMESTAMPTZ; -- T-3 reminder dedup per cycle

-- Distinguish manual checkouts from scheduled auto-renew token charges.
ALTER TABLE payments ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'CHECKOUT'
    CHECK (kind IN ('CHECKOUT', 'AUTO_RENEW'));

-- The auto-renew sweep scans only opted-in subscriptions with a saved token.
CREATE INDEX idx_users_auto_renew ON users (plan_expires_at) WHERE auto_renew = true AND card_token IS NOT NULL;
