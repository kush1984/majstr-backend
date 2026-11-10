-- One row per PDF the server handed out (iteration-first-experience): an estimate or an act, made by
-- the master in the app (MASTER) or downloaded by the client from a link (CLIENT). Nothing about the
-- person who downloaded it: no IP, no user agent — the link's token only resolves which master's
-- object it was.
--
-- An event table, not a counter on the document: a counter on `estimates` would UPDATE the row whose
-- @Version the portal signature checks (a download would race a signature into a 409), it would
-- vanish with a deleted estimate, and the funnel needs OWNERS, which this answers directly.
CREATE TABLE pdf_render (
    id         UUID PRIMARY KEY,
    owner_id   UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    project_id UUID        REFERENCES projects (id) ON DELETE SET NULL,
    document   VARCHAR(10) NOT NULL CHECK (document IN ('ESTIMATE', 'ACT')),
    reader     VARCHAR(10) NOT NULL CHECK (reader IN ('MASTER', 'CLIENT')),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_pdf_render_owner_reader ON pdf_render (owner_id, reader);
CREATE INDEX idx_pdf_render_project ON pdf_render (project_id);
