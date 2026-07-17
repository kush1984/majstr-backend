-- Object notes («Нотатки» tab): a master keeps object-scoped things handy — a
-- subcontractor's contact, access conditions, agreements. A note is FREE TEXT plus an
-- OPTIONAL title and phone (phone present → one-tap tel: call on the phone). No PRO gate
-- (a retention utility, not monetization) and no per-object count limit (it's text, not
-- files) — only field-length caps. Owner isolation is via the object (project) in the
-- service; cascade drops notes with the object. PRIVATE — never served to the client
-- portal / PDF / share response.

CREATE TABLE project_note (
    id         UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    title      VARCHAR(255),
    -- Stored as the master typed it ("067 123 45 67" or "+380…") — both work for tel:.
    phone      VARCHAR(40),
    body       TEXT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_project_note_project ON project_note(project_id);
