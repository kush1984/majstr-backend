-- Review round 5 P1.
--
-- B-126: a rotation chain is a family, so a token replayed long after it was rotated can take the whole
-- chain down. The default keeps an instance of the previous version inserting during the deploy overlap;
-- every existing row becomes its own family. grace_uses caps how often a just-rotated token may be replayed.
ALTER TABLE refresh_tokens
    ADD COLUMN family_id  UUID     NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN grace_uses SMALLINT NOT NULL DEFAULT 0;

CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);

-- An access token issued before this moment is refused, so a password reset ends it at once rather than
-- up to 15 minutes later.
ALTER TABLE users
    ADD COLUMN tokens_valid_after TIMESTAMPTZ;

-- P-78: a write that carries no id of its own (an upload, an import commit) would run twice when the client
-- gave up on a slow answer and retried. One row per submission; status NULL means it is still running.
CREATE TABLE idempotent_request (
    user_id      UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    request_key  UUID         NOT NULL,
    path         VARCHAR(500) NOT NULL,
    status       SMALLINT,
    content_type VARCHAR(200),
    body         BYTEA,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, request_key, path)
);

CREATE INDEX idx_idempotent_request_created ON idempotent_request (created_at);
