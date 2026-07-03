-- V39 — lifetime estimate counters per object. The FREE per-project estimate cap
-- is enforced on CONCURRENT count, so delete→create frees a slot (a known bypass,
-- see docs/open-questions "FREE estimate cap: delete→create loophole"). Rather than
-- block it, we MONITOR it: count estimates ever created / deleted per object so the
-- admin can see if anyone actually churns. No gate uses these yet.

ALTER TABLE projects ADD COLUMN estimates_created INTEGER NOT NULL DEFAULT 0;
ALTER TABLE projects ADD COLUMN estimates_deleted INTEGER NOT NULL DEFAULT 0;

-- Backfill created = the current live estimate count (a truthful baseline; past
-- deletions are unknowable, so deleted stays 0 for existing objects).
UPDATE projects p
SET estimates_created = (SELECT COUNT(*) FROM estimates e WHERE e.project_id = p.id);
