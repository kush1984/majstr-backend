-- Candidates the owner looked at and decided against.
--
-- Without this the insight screens are unusable after the first pass: every position a master
-- ever invented reappears on every visit, so the list only grows and the ones already judged
-- drown the ones that are new. The same lesson the offline outbox learned — dedupe against what
-- has been SEEN, not against what was accepted.
--
-- Keyed by the normalised name (CatalogNameKey), not by a catalog_items id, and that is the
-- point: the candidate is not one master's row, it is "this work, however it was spelled". If
-- twenty masters type it and one is dismissed, all twenty are dismissed — otherwise the same
-- decision has to be made twenty times.
--
-- `kind` separates the lists: a position dismissed as a NEW candidate should still be able to
-- appear as evidence that our own wording is wrong.
CREATE TABLE catalog_insight_dismissals (
    id            UUID         PRIMARY KEY,
    kind          VARCHAR(20)  NOT NULL,
    name_key      VARCHAR(512) NOT NULL,
    -- What was dismissed, in readable form. The key is sorted words and unreadable, and an
    -- audit line saying "you rejected 'плитки укладання'" helps nobody.
    sample_name   VARCHAR(255) NOT NULL,
    dismissed_by  UUID         REFERENCES users(id) ON DELETE SET NULL,
    dismissed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    note          VARCHAR(500),

    CONSTRAINT catalog_insight_dismissals_kind_check
        CHECK (kind IN ('NEW_POSITION', 'RENAMED_POSITION', 'UNUSED_DEFAULT', 'NEW_TEMPLATE'))
);

-- One dismissal per (kind, work). Re-dismissing is then an upsert rather than a second row.
CREATE UNIQUE INDEX idx_catalog_insight_dismissals_kind_key
    ON catalog_insight_dismissals (kind, name_key);
