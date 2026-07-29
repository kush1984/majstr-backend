-- Six-month retention on message attachments, and the warning that has to come first.
--
-- A file nobody has opened in six months is storage nobody is paying attention to. But deleting
-- somebody's invoice without notice is not a cleanup, it is data loss — so the sweep warns, waits, and
-- only then deletes. This column is what makes the warning happen once instead of every night.
--
-- It is also the cancel button: opening a warned file clears this back to NULL, so the six months start
-- again. That is the whole deal offered to the master — look at it and it stays.

ALTER TABLE project_message_files
    ADD COLUMN deletion_warned_at TIMESTAMPTZ;

-- The delete pass reads exactly this: warned, and warned long enough ago. Partial, because the vast
-- majority of rows are never warned at all and have no business in this index.
CREATE INDEX idx_project_message_files_warned
    ON project_message_files (deletion_warned_at)
    WHERE deletion_warned_at IS NOT NULL;
