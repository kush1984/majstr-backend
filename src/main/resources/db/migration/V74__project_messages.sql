-- Client questions become project MESSAGES, pinned to the object rather than to an estimate.
--
-- Why: the next step gives a master a link they can send to anyone — a supplier, a colleague — to
-- send them a message with a file. Such a message has no estimate at all, and today the table cannot
-- hold one: estimate_id is NOT NULL and every query reaches the project through it
-- (`q.estimate.project.id`, with a JOIN FETCH on the estimate).
--
-- Renamed in place rather than copied into a new table: the rows, their ids and their read flags all
-- survive untouched, and there is no window where the two disagree. Postgres carries the indexes and
-- the primary key across a rename automatically; only their NAMES would keep saying
-- «estimate_questions», so they are renamed too — an index whose name lies about its table is a trap
-- for whoever reads a slow-query log next year.

ALTER TABLE estimate_questions RENAME TO project_messages;

-- The object the message belongs to. Backfilled from the estimate, which every existing row has.
ALTER TABLE project_messages ADD COLUMN project_id UUID;

UPDATE project_messages m
SET project_id = e.project_id
FROM estimates e
WHERE e.id = m.estimate_id;

-- Safe to demand: estimate_id was NOT NULL and its FK cascaded from estimates, so no row could
-- reference an estimate that is gone, and none can be left without a project.
ALTER TABLE project_messages ALTER COLUMN project_id SET NOT NULL;

-- And the estimate becomes optional — that is the whole point.
ALTER TABLE project_messages ALTER COLUMN estimate_id DROP NOT NULL;

ALTER TABLE project_messages
    ADD CONSTRAINT project_messages_project_fk
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

-- The estimate link stops cascading. A message is now the OBJECT's: deleting one estimate must not
-- delete the conversation about the job, it should only forget which estimate was being discussed.
ALTER TABLE project_messages DROP CONSTRAINT estimate_questions_estimate_fk;
ALTER TABLE project_messages
    ADD CONSTRAINT project_messages_estimate_fk
    FOREIGN KEY (estimate_id) REFERENCES estimates(id) ON DELETE SET NULL;

-- The PRIMARY KEY carries a generated name, and a rename leaves it behind as well — found by the
-- test below, not by reading the docs.
ALTER INDEX estimate_questions_pkey RENAME TO project_messages_pkey;

ALTER INDEX idx_estimate_questions_estimate_id RENAME TO idx_project_messages_estimate_id;
ALTER INDEX idx_estimate_questions_created_at  RENAME TO idx_project_messages_created_at;

-- The old partial index was keyed on estimate_id, which no query will use again: unread counts are
-- per project (the badge on every object row) and per owner (the bell).
DROP INDEX idx_estimate_questions_unread;
CREATE INDEX idx_project_messages_project_id ON project_messages (project_id);
CREATE INDEX idx_project_messages_project_unread
    ON project_messages (project_id) WHERE is_read = FALSE;
