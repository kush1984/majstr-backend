-- =================================================================================================
-- V226 — keeping masters in touch (iteration-master-contact)
--
-- 1. telegram_notify — the bot tells the master what the app pushes (a signed estimate, a client's
--    message, a payment, an act, the plan running out). On by default: linking the bot is the opt-in;
--    the switch in «Профіль → Telegram-бот» turns it off.
-- 2. contact_phone / contact_phone_at — the number the master shared with the bot («📱 Поділитися
--    номером»), his own only (contact.user_id = from.id). It is how WE reach him: never printed on a
--    document, never shown to a client — unlike users.phone, which documents carry. Filling users.phone
--    from it takes the master's own tap in the app.
-- 3. help_reason — why the «Допомога з налаштуванням» card was offered when he asked for the call:
--    NO_OBJECT (a day in, no object) or NOT_SENT (a week in, nothing ever handed to a client). The
--    admin's call list shows it. NULL for calls asked before this column.
-- =================================================================================================

ALTER TABLE users ADD COLUMN telegram_notify boolean NOT NULL DEFAULT true;
ALTER TABLE users ADD COLUMN contact_phone varchar(32);
ALTER TABLE users ADD COLUMN contact_phone_at timestamptz;
ALTER TABLE users ADD COLUMN help_reason varchar(20);

ALTER TABLE users ADD CONSTRAINT users_help_reason_check
    CHECK (help_reason IS NULL OR help_reason IN ('NO_OBJECT', 'NOT_SENT'));

COMMENT ON COLUMN users.telegram_notify IS
    'Send the app''s notifications to the linked Telegram chat too (on by default; the bot link is the opt-in).';
COMMENT ON COLUMN users.contact_phone IS
    'The master''s own number shared with the bot — for us to reach him; never printed, never shown to a client.';
COMMENT ON COLUMN users.contact_phone_at IS 'When contact_phone was last shared with the bot.';
COMMENT ON COLUMN users.help_reason IS
    'Why the setup-help card was offered when he asked for the call: NO_OBJECT or NOT_SENT.';
