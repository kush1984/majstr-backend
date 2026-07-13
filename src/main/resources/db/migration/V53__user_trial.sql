-- Self-serve 5-day PRO trial (opt-in, one-time, no card). Reuses plan/plan_expires_at
-- for the grant; trial_started_at stamps when the master activated it so the trial
-- can only be taken once (NULL = never used) and admin can see who tried PRO.
ALTER TABLE users ADD COLUMN trial_started_at TIMESTAMPTZ;
