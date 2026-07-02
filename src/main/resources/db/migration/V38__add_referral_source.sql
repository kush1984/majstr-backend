-- V38 — referral-source attribution for partner rev-share. First-touch source of
-- each master (DIRECT by default; a partner like Ліга Майстрів when they arrive via
-- a ?ref= link or type a community promo code). Set once at registration, never
-- auto-overwritten — it's the source of truth for a future rev-share, so its
-- integrity matters. No money math here (billing rev-share layers on later);
-- this is the dimension the admin counts by.

-- Existing rows get DEFAULT 'DIRECT' (the backfill); the column stays NOT NULL.
ALTER TABLE users ADD COLUMN referral_source VARCHAR(40) NOT NULL DEFAULT 'DIRECT';
CREATE INDEX idx_users_referral_source ON users (referral_source);

-- Partner registry as DATA (not hardcode) — add a partner without touching code.
-- `code` is what arrives via ?ref=<code> or the promo field; it maps to `source`
-- (the value stamped on the user). Several codes can map to one source over time.
CREATE TABLE partners (
    id         UUID PRIMARY KEY,
    code       VARCHAR(40)  NOT NULL UNIQUE,
    source     VARCHAR(40)  NOT NULL,
    name       VARCHAR(255),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL
);

-- First partner: Ліга Майстрів. code LIGA (link ?ref=liga / promo "LIGA") → source LIGA.
INSERT INTO partners (id, code, source, name, active, created_at)
VALUES (gen_random_uuid(), 'LIGA', 'LIGA', 'Ліга Майстрів', TRUE, now());
