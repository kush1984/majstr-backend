-- One master's decision to take a SYSTEM DEFAULT template out of their own list.
--
-- Defaults are shared rows (is_default = true, owner_id = null) that every master sees, so a
-- master can neither delete nor edit one — but they asked for exactly that: «нам треба добавити
-- можливість видаляти шаблони, редагувати і також перетягувати позиції у шаблонах».
--
-- Both answers are the same row, following the template_trade_override precedent (per master,
-- invisible to everyone else):
--   * HIDE  — the master deleted a default they never use. Row with forked_template_id NULL.
--   * FORK  — the master edited a default. The service copies it into their OWN editable
--             template (copy-on-write) and points forked_template_id at the copy, so the
--             default disappears from the list and the copy takes its place.
--
-- forked_template_id is ON DELETE SET NULL: deleting the copy later leaves the default hidden
-- (the master threw that bundle away twice over), not silently restored.
CREATE TABLE template_default_override (
    user_id            UUID        NOT NULL,
    template_id        UUID        NOT NULL,
    forked_template_id UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, template_id),
    CONSTRAINT template_default_override_user_fk
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT template_default_override_template_fk
        FOREIGN KEY (template_id) REFERENCES estimate_templates(id) ON DELETE CASCADE,
    CONSTRAINT template_default_override_fork_fk
        FOREIGN KEY (forked_template_id) REFERENCES estimate_templates(id) ON DELETE SET NULL
);

CREATE INDEX idx_template_default_override_user ON template_default_override (user_id);
