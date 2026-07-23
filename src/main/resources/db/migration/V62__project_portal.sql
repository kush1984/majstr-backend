-- Project-level client portal: one link per object, the master picks which
-- estimates are visible on it. Per-estimate share links stay valid (legacy URLs
-- already sent to clients keep working); new shares go through the project link.

CREATE TABLE project_share_links (
    id         UUID         PRIMARY KEY,
    project_id UUID         NOT NULL,
    token      VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ,
    revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT project_share_links_project_fk
        FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT project_share_links_token_unique UNIQUE (token)
);

CREATE INDEX idx_project_share_links_project_id ON project_share_links (project_id);

ALTER TABLE estimates ADD COLUMN portal_visible BOOLEAN NOT NULL DEFAULT FALSE;

-- Continuity: an estimate the master already shared (has a live per-estimate
-- link) starts out visible in the new portal, so switching share models doesn't
-- silently empty anyone's portal.
UPDATE estimates e
SET portal_visible = TRUE
WHERE EXISTS (
    SELECT 1 FROM estimate_share_links l
    WHERE l.estimate_id = e.id
      AND l.revoked = FALSE
      AND (l.expires_at IS NULL OR l.expires_at > NOW())
);
