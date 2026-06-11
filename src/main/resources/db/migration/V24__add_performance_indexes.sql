-- Indexes for the hottest read paths (code-review follow-up, Fix I).

-- Latest-estimate summary on the project list uses DISTINCT ON (project_id)
-- ... ORDER BY project_id, created_at DESC. The composite index serves it
-- directly and also covers every plain project_id lookup, so the old
-- single-column index is redundant.
CREATE INDEX idx_estimates_project_created
    ON estimates (project_id, created_at DESC);
DROP INDEX idx_estimates_project_id;

-- Dashboard "completed this month": owner + completed_at range over
-- COMPLETED projects only.
CREATE INDEX idx_projects_owner_completed
    ON projects (owner_id, completed_at)
    WHERE status = 'COMPLETED';

-- Daily token sweep (TokenCleanupService) deletes by expires_at.
CREATE INDEX idx_email_verification_tokens_expires_at
    ON email_verification_tokens (expires_at);
