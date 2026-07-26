-- Store email-verification and password-reset tokens HASHED, like refresh tokens.
--
-- Both were persisted raw. They are bearer credentials — whoever holds the value IS the
-- user for that operation — so anyone with read access to a DB dump or a backup got live,
-- unexpired password-reset links and could take over accounts without touching the running
-- system. `refresh_tokens.token_hash` already did this correctly; these two did not.
--
-- The column is RENAMED (not just repurposed) so the schema states what it holds, matching
-- refresh_tokens. Postgres carries the UNIQUE index across a rename automatically.
--
-- Existing rows are DELETED rather than migrated: they contain raw tokens, which cannot be
-- turned into hashes here without also leaving the raw values behind in the WAL/backups —
-- and keeping them would break lookups anyway (the code now hashes before comparing).
-- Consequence, accepted deliberately: links already emailed stop working. Password-reset
-- tokens live 45 minutes and verification tokens 24 hours, so the blast radius is whoever
-- has an unclicked link right now; both flows have a "send again" path.

DELETE FROM password_reset_tokens;
DELETE FROM email_verification_tokens;

ALTER TABLE password_reset_tokens RENAME COLUMN token TO token_hash;
ALTER TABLE email_verification_tokens RENAME COLUMN token TO token_hash;
