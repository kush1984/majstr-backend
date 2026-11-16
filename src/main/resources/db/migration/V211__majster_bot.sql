-- majster-bot: object tasks, the Telegram link, and the inbox every bot/quick-add message lands in.
-- docs/iteration-majster-bot.md.

-- «Що треба зробити» on an object — its own entity, not a note: a task gets DONE, a note is reference.
-- Owner isolation through the object, like project_note. PRIVATE — never on a portal/PDF/share.
CREATE TABLE project_task (
    id         UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    body       VARCHAR(500) NOT NULL,
    done_at    TIMESTAMPTZ,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_project_task_project ON project_task(project_id);

-- One Telegram chat per master and one master per chat.
CREATE TABLE telegram_link (
    user_id   UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    chat_id   BIGINT NOT NULL UNIQUE,
    username  VARCHAR(64),
    linked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The one-time code a deep link t.me/<bot>?start=<code> carries; short-lived, deleted on use.
CREATE TABLE telegram_link_code (
    code       VARCHAR(40) PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_telegram_link_code_user ON telegram_link_code(user_id);

-- Every message as it arrived, BEFORE anything is parsed out of it.
CREATE TABLE inbox_entry (
    id                  UUID PRIMARY KEY,
    owner_id            UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source              VARCHAR(16) NOT NULL CHECK (source IN ('TELEGRAM', 'PWA')),
    raw_text            TEXT NOT NULL,
    status              VARCHAR(16) NOT NULL CHECK (status IN ('PARSING', 'NEW', 'DONE', 'FAILED')),
    -- Telegram redelivers an update on any non-2xx; the second delivery must find the first.
    telegram_update_id  BIGINT UNIQUE,
    telegram_chat_id    BIGINT,
    -- The bot's own answer, edited in place when the master taps a button.
    telegram_message_id BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_inbox_entry_owner ON inbox_entry(owner_id, created_at DESC);

-- One thing the parse found in a message. Applying it goes through the record's OWN service;
-- record_id is what that created, so «Скасувати» can delete it through the same door.
CREATE TABLE inbox_item (
    id          UUID PRIMARY KEY,
    entry_id    UUID NOT NULL REFERENCES inbox_entry(id) ON DELETE CASCADE,
    kind        VARCHAR(16) NOT NULL
                CHECK (kind IN ('SHOPPING', 'TASK', 'NOTE', 'ESTIMATE_LINE', 'EXPENSE', 'INCOME')),
    status      VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'APPLIED', 'DISCARDED')),
    project_id  UUID REFERENCES projects(id) ON DELETE SET NULL,
    estimate_id UUID REFERENCES estimates(id) ON DELETE SET NULL,
    text        VARCHAR(2000) NOT NULL,
    quantity    NUMERIC(15, 3),
    unit        VARCHAR(16),
    unit_price  NUMERIC(15, 2),
    item_type   VARCHAR(16),
    amount      NUMERIC(15, 2),
    category    VARCHAR(16),
    record_id   UUID,
    -- Why it waits: OBJECT, ESTIMATE, PRICE, UNIT, QUANTITY, AMOUNT, CONFIRM — null when nothing does.
    issue       VARCHAR(16),
    sort_order  INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_inbox_item_entry ON inbox_item(entry_id);
CREATE INDEX idx_inbox_item_project ON inbox_item(project_id);
