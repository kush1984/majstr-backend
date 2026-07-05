-- Master→master referrals + a half-year billing period.
--
-- (1) Every user gets a short, URL-safe personal referral code (link
--     majstr.pro/?ref=m-<code>). Existing users are backfilled deterministically
--     from their id (md5 → 10 hex chars; collision across any realistic user count
--     is astronomically unlikely, and the UNIQUE constraint would catch one).
-- (2) referred_by_user_id records who invited a master (first-touch, set once at
--     registration; ON DELETE SET NULL so deleting the referrer keeps the account).
-- (3) referral_rewards is the audit + idempotency guard: UNIQUE(referred_user_id)
--     guarantees exactly one reward per invited master, ever (a retried webhook or
--     a second payment can never double-grant).
-- (4) payments.period + users.renew_period carry MONTH | HALF_YEAR so auto-renew
--     recharges the same period the master originally bought.

ALTER TABLE users ADD COLUMN referral_code VARCHAR(16);
UPDATE users SET referral_code = substr(md5(id::text), 1, 10) WHERE referral_code IS NULL;
ALTER TABLE users ALTER COLUMN referral_code SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT uq_users_referral_code UNIQUE (referral_code);

ALTER TABLE users ADD COLUMN referred_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE users ADD COLUMN renew_period VARCHAR(20);

ALTER TABLE payments ADD COLUMN period VARCHAR(20) NOT NULL DEFAULT 'MONTH'
    CHECK (period IN ('MONTH', 'HALF_YEAR'));

CREATE TABLE referral_rewards (
    id                UUID PRIMARY KEY,
    referrer_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    referred_user_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    payment_id        UUID NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    granted_days      INT  NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_referral_rewards_referred UNIQUE (referred_user_id)
);

-- Look up "who did master X invite" / "who invited master X" without a scan.
CREATE INDEX idx_users_referred_by ON users(referred_by_user_id) WHERE referred_by_user_id IS NOT NULL;
CREATE INDEX idx_referral_rewards_referrer ON referral_rewards(referrer_id);
