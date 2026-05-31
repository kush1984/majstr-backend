-- Dashboard "completed this month" needs to know WHEN a project was
-- completed, which updated_at can't provide (any edit re-dates it).
-- completed_at is set when status enters COMPLETED and cleared when it
-- leaves (ProjectService.updateStatus). For existing COMPLETED projects we
-- backfill from updated_at as a one-time approximation (better than null).

ALTER TABLE projects ADD COLUMN completed_at TIMESTAMPTZ;

UPDATE projects SET completed_at = updated_at WHERE status = 'COMPLETED';
