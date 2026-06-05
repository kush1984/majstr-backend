-- Read/unread flag for client questions so the contractor can track which ones
-- they have already seen. Existing rows default to unread (never acknowledged).
ALTER TABLE estimate_questions ADD COLUMN is_read BOOLEAN NOT NULL DEFAULT FALSE;

-- Partial index keeps per-project unread counts cheap even with many questions:
-- the unread-count query filters is_read = FALSE and joins by estimate_id.
CREATE INDEX idx_estimate_questions_unread
    ON estimate_questions (estimate_id) WHERE is_read = FALSE;
