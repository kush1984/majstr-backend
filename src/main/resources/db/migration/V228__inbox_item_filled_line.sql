-- A bot estimate line that filled the template's own empty «к-сть?» row instead of appending a second
-- one beside it. «Скасувати» then empties that row again rather than deleting it.
ALTER TABLE inbox_item ADD COLUMN filled_line BOOLEAN NOT NULL DEFAULT FALSE;
