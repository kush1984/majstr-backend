-- Browser Web Push subscriptions for contractor notifications.
-- One row per (browser endpoint). A user can have several (phone, laptop, ...).
-- endpoint is unique so re-subscribing the same browser upserts instead of
-- duplicating; p256dh + auth are the client's RFC 8291 encryption keys.

CREATE TABLE push_subscriptions (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL,
    endpoint   TEXT         NOT NULL,
    p256dh     VARCHAR(255) NOT NULL,
    auth       VARCHAR(255) NOT NULL,
    user_agent VARCHAR(512),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT push_subscriptions_user_fk
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT push_subscriptions_endpoint_unique UNIQUE (endpoint)
);

CREATE INDEX idx_push_subscriptions_user_id ON push_subscriptions (user_id);
