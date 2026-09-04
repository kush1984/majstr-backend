-- Per-master «say X, mean THIS catalog row» learned during a dictation review.
--
-- The matcher's Dice ladder is stemmed and inspectable, and refuses a tie — so «шпалери» does not
-- match «Поклейка шпалер». That is the flagging rule and it holds. What it costs is that every
-- master teaches the same association the same way twice: the second dictation is flagged for the
-- same reason as the first. A synonym is the smallest thing that closes the loop — one row saying
-- «this spoken wording is that catalog item», per master, and CatalogMatcher consults it before the
-- Dice pass, so the wording wins outright.
--
-- FK is ON DELETE CASCADE deliberately: a synonym for a catalog position that no longer exists is
-- not a fact about anything, and keeping it would resurrect a deleted position's name in a match a
-- week later, silently. A RENAME keeps the synonym pointing at the same row — the row is the same
-- job under a new wording. See docs/open-questions.md → "A learned synonym outlives the catalog
-- position it points at".
CREATE TABLE catalog_item_synonym (
    id                 UUID          PRIMARY KEY,
    owner_id           UUID          NOT NULL,
    catalog_item_id    UUID          NOT NULL,
    -- Lowercased, apostrophes dropped, punctuation collapsed — same shape CatalogMatcher.normalize
    -- produces. Stored so the lookup is one equality, not a normalization pass per row.
    spoken_normalized  VARCHAR(200)  NOT NULL,
    -- What he said, verbatim. Only for a future "розпізнається також як: …" list; unread today.
    spoken_raw         VARCHAR(200)  NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT catalog_item_synonym_owner_fk
        FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT catalog_item_synonym_item_fk
        FOREIGN KEY (catalog_item_id) REFERENCES catalog_items(id) ON DELETE CASCADE,
    -- A spoken wording can only mean one row per master. Teaching a new target overwrites the old
    -- one from the app (delete + insert in the same tx), never inserts a second.
    CONSTRAINT catalog_item_synonym_unique
        UNIQUE (owner_id, spoken_normalized),
    CONSTRAINT catalog_item_synonym_spoken_not_blank
        CHECK (length(trim(spoken_normalized)) > 0)
);

CREATE INDEX idx_catalog_item_synonym_owner ON catalog_item_synonym (owner_id);
CREATE INDEX idx_catalog_item_synonym_item  ON catalog_item_synonym (catalog_item_id);
