-- PRO upgrade-intent tracking (painted door). One table, two event types:
--   CLICK    — every tap on an "Upgrade to PRO" CTA (counts intensity/where from).
--   INTEREST — the master submitted the painted-door form (a warm lead, with reason).
-- Anonymous product analytics over our own users (not ads / not sold). Used by the
-- admin activation dashboard: demand width (distinct clickers), intensity (total
-- clicks), by-trigger breakdown, and the warm-lead list to call.
CREATE TABLE upgrade_event (
    id             UUID PRIMARY KEY,
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type           VARCHAR(20) NOT NULL,
    trigger_source VARCHAR(40),          -- where the click came from (nullable)
    reason         TEXT,                 -- INTEREST only: what the master wants
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT upgrade_event_type_check CHECK (type IN ('CLICK', 'INTEREST'))
);
CREATE INDEX idx_upgrade_event_user    ON upgrade_event (user_id);
CREATE INDEX idx_upgrade_event_type    ON upgrade_event (type);
CREATE INDEX idx_upgrade_event_created ON upgrade_event (created_at);
