CREATE TABLE projects (
    id          UUID            PRIMARY KEY,
    owner_id    UUID            NOT NULL,
    client_id   UUID,
    name        VARCHAR(255)    NOT NULL,
    address     VARCHAR(512)    NOT NULL,
    description TEXT,
    status      VARCHAR(50)     NOT NULL,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT projects_owner_fk
        FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT projects_client_fk
        FOREIGN KEY (client_id) REFERENCES clients(id) ON DELETE SET NULL,
    CONSTRAINT projects_status_check
        CHECK (status IN ('DRAFT', 'ESTIMATING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'))
);

CREATE INDEX idx_projects_owner_id  ON projects (owner_id);
CREATE INDEX idx_projects_client_id ON projects (client_id);
CREATE INDEX idx_projects_status    ON projects (status);
