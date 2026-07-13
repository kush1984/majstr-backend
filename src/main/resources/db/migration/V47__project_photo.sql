-- Object photos («Фото» tab): one table for two sources.
--   RECEIPT — the photo of a receipt whose lines were parsed into an estimate; always
--             PRIVATE (financial), linked to the estimate (id + a durable name snapshot).
--   MANUAL  — a progress photo the master takes; PRIVATE by default, can be made SHARED
--             so the client sees it on the portal.
-- Owner isolation is via the object (project) in the service; cascade drops photos with
-- the object. estimate_id goes NULL if that estimate is later deleted — the snapshot keeps
-- the label. Files are served through authenticated / token-gated endpoints, never the
-- public /api/files/**, so the storage_key is never exposed as a URL.

CREATE TABLE project_photo (
    id                     UUID PRIMARY KEY,
    project_id             UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    storage_key            TEXT NOT NULL,
    source                 VARCHAR(20) NOT NULL,
    visibility             VARCHAR(20) NOT NULL,
    caption                TEXT,
    estimate_id            UUID REFERENCES estimates(id) ON DELETE SET NULL,
    estimate_name_snapshot TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT project_photo_source_check     CHECK (source IN ('RECEIPT', 'MANUAL')),
    CONSTRAINT project_photo_visibility_check CHECK (visibility IN ('PRIVATE', 'SHARED'))
);

CREATE INDEX idx_project_photo_project ON project_photo(project_id);
-- Portal reads the object's SHARED photos; keep that filter cheap.
CREATE INDEX idx_project_photo_shared ON project_photo(project_id) WHERE visibility = 'SHARED';
