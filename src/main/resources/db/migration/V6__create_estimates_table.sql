CREATE TABLE estimates (
    id          UUID            PRIMARY KEY,
    project_id  UUID            NOT NULL,
    status      VARCHAR(50)     NOT NULL,
    valid_until DATE,
    notes       TEXT,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT estimates_project_fk
        FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT estimates_status_check
        CHECK (status IN ('DRAFT', 'SENT', 'SIGNED', 'REJECTED'))
);

CREATE INDEX idx_estimates_project_id ON estimates (project_id);
CREATE INDEX idx_estimates_status     ON estimates (status);
