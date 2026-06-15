-- Estimate name (tell variant estimates apart: econom / mid / premium) and the
-- reopen audit trail (owner re-opening a signed estimate for edits → re-sign).
ALTER TABLE estimates
    ADD COLUMN name        VARCHAR(255),
    ADD COLUMN reopened_at TIMESTAMPTZ,
    ADD COLUMN reopened_by UUID;

COMMENT ON COLUMN estimates.name IS 'Optional contractor label to distinguish variants; null = default display name.';
COMMENT ON COLUMN estimates.reopened_at IS 'When the owner last reopened a signed estimate for edits (audit).';
COMMENT ON COLUMN estimates.reopened_by IS 'User id of the owner who reopened it (audit; no FK, like signer_*).';
