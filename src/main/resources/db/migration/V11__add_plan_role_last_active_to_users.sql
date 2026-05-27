ALTER TABLE users
    ADD COLUMN plan            VARCHAR(20)  NOT NULL DEFAULT 'FREE',
    ADD COLUMN role            VARCHAR(20)  NOT NULL DEFAULT 'USER',
    ADD COLUMN last_active_at  TIMESTAMPTZ;

ALTER TABLE users
    ADD CONSTRAINT users_plan_check CHECK (plan IN ('FREE', 'PRO', 'TEAM')),
    ADD CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN'));

CREATE INDEX idx_users_plan           ON users (plan);
CREATE INDEX idx_users_role           ON users (role);
CREATE INDEX idx_users_last_active_at ON users (last_active_at);
