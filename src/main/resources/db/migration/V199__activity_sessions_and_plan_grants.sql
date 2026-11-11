-- Admin: how long a master actually uses the app, and who granted him a plan for how long.

-- One row per stretch of use: opened by the first authenticated request after 30 minutes of
-- silence, stretched by every later one (LastActiveTracker, throttled to 5 minutes — that is also
-- the precision of last_seen_at). Kept 90 days (ActivitySessionRetention).
CREATE TABLE user_activity_session (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    started_at   TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_user_activity_session_order CHECK (last_seen_at >= started_at)
);
CREATE INDEX ix_user_activity_session_user ON user_activity_session (user_id, last_seen_at DESC);
CREATE INDEX ix_user_activity_session_seen ON user_activity_session (last_seen_at);

-- Every plan change made from the admin page: who, when, what, for how many months (NULL = no
-- end) and the expiry it produced. History, never updated.
CREATE TABLE admin_plan_grant (
    id          UUID PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    actor_email VARCHAR(255) NOT NULL,
    plan        VARCHAR(20)  NOT NULL,
    months      INTEGER,
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_admin_plan_grant_months CHECK (months IS NULL OR months BETWEEN 1 AND 12)
);
CREATE INDEX ix_admin_plan_grant_user ON admin_plan_grant (user_id, created_at DESC);
