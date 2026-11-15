-- «Увійти через Google» (2026-11-15): the Google account's stable id (`sub`), set the first time a master
-- signs in with Google — on a new account or on an existing one with the same, Google-verified email.
-- NULL for a master who never used Google. V210, not V209: the majster-bot branch holds V209.
ALTER TABLE users ADD COLUMN google_sub VARCHAR(255);
CREATE UNIQUE INDEX ux_users_google_sub ON users (google_sub) WHERE google_sub IS NOT NULL;

COMMENT ON COLUMN users.google_sub IS 'The Google account id the master signs in with (V210); NULL = never.';
