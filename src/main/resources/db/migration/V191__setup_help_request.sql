-- =================================================================================================
-- V191 — «Потрібна допомога з налаштуванням? Залиште телефон» (registration friction, round 2).
--
-- Registration stopped asking for a phone (V190), so a master who wants a call has to say so. The
-- moment he asks is stamped here; the phone itself lands in `users.phone`, which documents print too.
-- NULL = never asked. The admin lists the askers, newest first.
-- =================================================================================================

ALTER TABLE users ADD COLUMN help_requested_at TIMESTAMPTZ;

CREATE INDEX ix_users_help_requested_at ON users (help_requested_at DESC) WHERE help_requested_at IS NOT NULL;
