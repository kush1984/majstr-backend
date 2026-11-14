-- The master's own comment on a CATALOG position (owner's ruling 2026-11-14), beside V206's comment on
-- an estimate line. A line added from the catalog copies it; a change in the catalog reaches every line
-- of an estimate the client has not signed — but never a line whose comment the master changed in that
-- estimate: that one is his own now, and a catalog edit must not silently overwrite it. A line whose
-- comment is simply not the catalog's (typed when it was added) is left alone too — by comparison, in
-- CatalogService; `note_own` covers the one case a comparison cannot see: changed, then changed back.
ALTER TABLE catalog_items ADD COLUMN note VARCHAR(500);
ALTER TABLE estimate_items ADD COLUMN note_own BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN catalog_items.note IS
    'The master''s own comment on this position (V207); copied into a line added from it, and carried to the lines of unsigned estimates when it changes. Null = none.';
COMMENT ON COLUMN estimate_items.note_own IS
    'The master changed this line''s comment in the estimate itself (V207): a catalog edit of the comment no longer reaches it.';
