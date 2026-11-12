-- Where the client's page shows «Зведення» — the per-estimate Роботи / Матеріали / Разом lines and the grand
-- total (owner's ruling 2026-11-12, after a master asked for it under the positions as it used to be). The
-- master chooses it in the share sheet; FALSE keeps it at the top, as every page shows it today.
-- One choice per object: the object's portal, its economy page and each estimate's own ?t= link read it.
ALTER TABLE projects ADD COLUMN portal_summary_at_bottom BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN projects.portal_summary_at_bottom IS
    'The client''s page shows «Зведення» after the positions instead of at the top (V202).';
