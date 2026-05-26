CREATE TABLE estimate_share_links (
    id          UUID            PRIMARY KEY,
    estimate_id UUID            NOT NULL,
    token       VARCHAR(128)    NOT NULL,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ,
    revoked     BOOLEAN         NOT NULL DEFAULT FALSE,
    CONSTRAINT estimate_share_links_estimate_fk
        FOREIGN KEY (estimate_id) REFERENCES estimates(id) ON DELETE CASCADE,
    CONSTRAINT estimate_share_links_token_unique UNIQUE (token)
);

CREATE INDEX idx_estimate_share_links_estimate_id ON estimate_share_links (estimate_id);
CREATE INDEX idx_estimate_share_links_token       ON estimate_share_links (token);
