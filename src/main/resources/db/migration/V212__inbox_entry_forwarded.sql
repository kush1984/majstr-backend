-- majster-bot review (2026-11-16): a message the master FORWARDED to the bot is someone else's words —
-- a client's «додайте ще 8 точок» — so nothing in it is filed by itself; every item waits for «Записати».
ALTER TABLE inbox_entry ADD COLUMN forwarded BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN inbox_entry.forwarded IS 'A Telegram message forwarded from someone else: every item waits for the master (V212).';
