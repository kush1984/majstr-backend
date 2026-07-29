-- Files attached to a message: a photo of a wall, an invoice PDF from a supplier.
--
-- CASCADE on the message is the right coupling. A file has no meaning without the message that
-- explains it, and the master deleting a message expects its attachments to go with it — the rows do
-- so here, and the service deletes the stored bytes before the row disappears.

CREATE TABLE project_message_files (
    id             UUID PRIMARY KEY,
    message_id     UUID        NOT NULL,
    -- Opaque StorageService key. Never leaves the server: the client addresses a file by its id.
    storage_key    TEXT        NOT NULL,
    -- What the sender called it. Shown to the master and used for the download filename, so it is
    -- displayed as text and never used to build a path.
    original_name  VARCHAR(255),
    -- Sniffed from the bytes, not taken from the upload's declared type.
    content_type   VARCHAR(100) NOT NULL,
    size_bytes     BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- NULL = never opened. The retention clock reads this (falling back to created_at), which is why
    -- it exists from the start: a column added later would have no history to measure and every file
    -- uploaded before it would look untouched.
    last_opened_at TIMESTAMPTZ,

    CONSTRAINT project_message_files_message_fk
        FOREIGN KEY (message_id) REFERENCES project_messages (id) ON DELETE CASCADE
);

CREATE INDEX idx_project_message_files_message_id
    ON project_message_files (message_id);

-- The retention sweep looks for the least recently touched files across every master, so it is served
-- by one expression index rather than a scan.
CREATE INDEX idx_project_message_files_last_touched
    ON project_message_files (COALESCE(last_opened_at, created_at));
