-- project_photo.estimate_id is a foreign key, and PostgreSQL does NOT index foreign keys
-- automatically (only the referenced side gets one, via the PK).
--
-- Two costs without it, both growing with the table:
--   1. Every DELETE of an estimate must find the referencing photo rows to apply
--      ON DELETE SET NULL — a full scan of project_photo per deleted estimate, while
--      holding the estimate's lock.
--   2. Receipt photos are listed per estimate, which is the same scan on a read path.
--
-- Partial: only RECEIPT photos carry an estimate_id at all (MANUAL progress photos leave it
-- NULL), so indexing the NULLs would double the size for nothing. Postgres still uses a
-- partial index for the FK maintenance above, because that lookup is by a concrete id.
CREATE INDEX idx_project_photo_estimate
    ON project_photo (estimate_id)
    WHERE estimate_id IS NOT NULL;
