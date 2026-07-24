-- A master's OWN filing of a template into a trade. Own templates keep the trade on
-- their own row; a SYSTEM default is shared by everyone, so re-filing it is stored
-- here — per master, invisible to others. A row with trade NULL means "explicitly
-- general" (distinct from having no override at all).
CREATE TABLE template_trade_override (
    user_id     UUID        NOT NULL,
    template_id UUID        NOT NULL,
    trade       VARCHAR(50),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, template_id),
    CONSTRAINT template_trade_override_trade_check CHECK (
        trade IS NULL OR trade IN ('ELECTRICAL', 'PLUMBING', 'TILING', 'BUILDER', 'PAINTER',
                                   'DRYWALL', 'FLOORING', 'DEMOLITION', 'GENERAL', 'OTHER')),
    CONSTRAINT template_trade_override_user_fk
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT template_trade_override_template_fk
        FOREIGN KEY (template_id) REFERENCES estimate_templates(id) ON DELETE CASCADE
);

CREATE INDEX idx_template_trade_override_user ON template_trade_override (user_id);
