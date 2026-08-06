-- Lineage for a consolidated estimate: which estimates it was rolled up from. A consolidated
-- estimate copies its sources' LINE ITEMS by value, but their receipts stay on the sources
-- (project_photo.estimate_id points at the source). This table lets the consolidated estimate
-- offer those source receipts when the master builds its PDF, without moving the photos.
--
-- Both sides ON DELETE CASCADE: deleting the rollup drops its lineage rows; deleting a source
-- estimate drops the rows that referenced it (its receipts become orphans via the existing
-- project_photo FK, exactly as they would anyway).
CREATE TABLE estimate_consolidation_sources (
    consolidated_estimate_id UUID NOT NULL REFERENCES estimates (id) ON DELETE CASCADE,
    source_estimate_id       UUID NOT NULL REFERENCES estimates (id) ON DELETE CASCADE,
    PRIMARY KEY (consolidated_estimate_id, source_estimate_id)
);
