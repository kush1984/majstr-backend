-- A comment the master writes on an estimate line himself (webinar 2026-11-11: «Можно ли в позициях
-- вставлять комментарии как в excel?»): «у 2 шари», «матеріал замовника», «без демонтажу».
--
-- Beside `description`, not in it: `description` is the library's explanation snapshotted from the
-- catalog (V119) and is deliberately not shown to the client; `note` is the master's own words and the
-- client reads it — unless the master switches that off for this one line (owner's ruling 2026-11-14).
ALTER TABLE estimate_items ADD COLUMN note VARCHAR(500);
ALTER TABLE estimate_items ADD COLUMN note_visible BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN estimate_items.note IS
    'The master''s own comment on this line (V206); null = none.';
COMMENT ON COLUMN estimate_items.note_visible IS
    'The client sees the note on the page and in the PDF; false = the master keeps it to himself (V206).';
