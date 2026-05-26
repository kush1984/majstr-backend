CREATE TABLE estimate_questions (
    id           UUID         PRIMARY KEY,
    estimate_id  UUID         NOT NULL,
    author_name  VARCHAR(255),
    author_phone VARCHAR(50),
    message      TEXT         NOT NULL,
    author_ip    VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT estimate_questions_estimate_fk
        FOREIGN KEY (estimate_id) REFERENCES estimates(id) ON DELETE CASCADE
);

CREATE INDEX idx_estimate_questions_estimate_id ON estimate_questions (estimate_id);
CREATE INDEX idx_estimate_questions_created_at  ON estimate_questions (created_at);
