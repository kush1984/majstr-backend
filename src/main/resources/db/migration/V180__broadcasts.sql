-- Re-engagement broadcasts from the admin «Розсилки» tab.
--   users.broadcast_opt_out_at — the master clicked «не надсилати мені такі листи». Only broadcasts
--     honour it; verification / password / billing mail is transactional and keeps going.
--   broadcast — one send: which segment, the text exactly as the admin typed it, who pressed it.
--   broadcast_recipient — one row per letter. The status is per row and committed per letter, so a
--     restart mid-send resumes from PENDING instead of mailing everybody twice. The unsubscribe link
--     carries this row's own token: a random value, never the user id.
ALTER TABLE users ADD COLUMN broadcast_opt_out_at TIMESTAMPTZ;

CREATE TABLE broadcast (
    id          UUID PRIMARY KEY,
    segment     VARCHAR(40)  NOT NULL,
    subject     VARCHAR(200) NOT NULL,
    body        TEXT         NOT NULL,
    sent_by     VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    finished_at TIMESTAMPTZ
);

CREATE TABLE broadcast_recipient (
    id                UUID PRIMARY KEY,
    broadcast_id      UUID         NOT NULL REFERENCES broadcast (id) ON DELETE CASCADE,
    user_id           UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    email             VARCHAR(255) NOT NULL,
    status            VARCHAR(10)  NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
    error             VARCHAR(500),
    sent_at           TIMESTAMPTZ,
    unsubscribe_token VARCHAR(64)  NOT NULL UNIQUE,
    UNIQUE (broadcast_id, user_id)
);

CREATE INDEX idx_broadcast_recipient_user ON broadcast_recipient (user_id);
CREATE INDEX idx_broadcast_recipient_pending ON broadcast_recipient (broadcast_id) WHERE status = 'PENDING';
