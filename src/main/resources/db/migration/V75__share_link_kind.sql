-- One object can now hand out two different links, and they must never be confused.
--
-- PORTAL is the existing one: it shows the client their estimate, with prices. MESSAGE is new — a link
-- the master can send to anyone (a supplier, a colleague) to get a message and a file back. That one
-- must show no money at all, which is exactly why it cannot be the same link: sending the portal URL
-- to a supplier to ask for an invoice would hand them the client's quote.
--
-- Same table on purpose. Tokens, expiry and revocation are already solved here, and one place that
-- decides whether a link is usable is worth more than two that drift apart.

ALTER TABLE project_share_links
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'PORTAL';

-- Every row that exists today is a portal link, which is what the default backfills. The default
-- stays: it makes a forgotten `kind` behave as the cautious value rather than fail an insert, and
-- PORTAL is the one that reveals nothing new.
ALTER TABLE project_share_links
    ADD CONSTRAINT project_share_links_kind_check CHECK (kind IN ('PORTAL', 'MESSAGE'));

-- Lookups are now "the live link of THIS kind for this object" — the old project-only index cannot
-- serve that without scanning both kinds.
DROP INDEX idx_project_share_links_project_id;
CREATE INDEX idx_project_share_links_project_kind
    ON project_share_links (project_id, kind) WHERE revoked = FALSE;
