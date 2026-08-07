-- Власні трейди майстра. A system trade (Trade enum) is a KEY INTO A REFERENCE CATALOG —
-- adding one always means an admin-curated starter set of works and templates. A master's OWN
-- trade is just a label he invents ("Натяжні стелі"); there is no reference catalog for it and
-- there never will be — he fills it himself, position by position.
--
-- ============ WHY A NULLABLE FK, NOT A NEW ENUM VALUE PER MASTER ==================
-- Every system trade lives as a literal in FOUR CHECK constraints (user_trades,
-- catalog_templates, catalog_items, estimate_templates) — adding ELECTRICAL or METAL meant a
-- migration touching all four at once. A master-invented trade cannot work that way: we cannot
-- ship a migration every time someone types a new word into a text box.
--
-- So a custom trade is a real row (user_trade) with real identity, and catalog_items /
-- estimate_templates reference it by a nullable FK rather than by widening the enum. The
-- existing `trade` enum column is untouched and stays NOT NULL — when a position carries a
-- custom trade, `trade` is simply OTHER, the same legitimate "Інше" catch-all V33 already
-- established. A position is EITHER a system trade (custom_trade_id NULL) OR a custom one
-- (trade = OTHER AND custom_trade_id set) — never both, enforced by a CHECK on both tables.
CREATE TABLE user_trade (
    id         UUID        NOT NULL PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name       VARCHAR(100) NOT NULL,
    sort_order INT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- No duplicate custom trades per master ("Натяжні стелі" twice, differently capitalised).
CREATE UNIQUE INDEX ux_user_trade_owner_name ON user_trade (user_id, lower(btrim(name)));

-- ============ CATALOG ITEMS =========================================================
ALTER TABLE catalog_items
    ADD COLUMN custom_trade_id UUID REFERENCES user_trade (id) ON DELETE SET NULL;

ALTER TABLE catalog_items
    ADD CONSTRAINT catalog_items_custom_trade_invariant
        CHECK (custom_trade_id IS NULL OR trade = 'OTHER');

-- ON DELETE SET NULL alone drops a deleted custom trade's positions back to plain OTHER —
-- their `trade` column already reads OTHER (the invariant above guarantees it), so nothing else
-- has to change when the FK clears. Nothing is lost; the position just stops being "special".
CREATE INDEX idx_catalog_items_custom_trade ON catalog_items (custom_trade_id) WHERE custom_trade_id IS NOT NULL;

-- ============ ESTIMATE TEMPLATES — OWN TEMPLATES ONLY ==============================
-- CatalogTemplate (the global admin-curated starter library) and template_trade_override (a
-- master's personal re-filing of a SYSTEM default) are deliberately untouched — both are about
-- the system reference catalog, which a custom trade never has by design.
ALTER TABLE estimate_templates
    ADD COLUMN custom_trade_id UUID REFERENCES user_trade (id) ON DELETE SET NULL;

-- Also pins is_default = false: a system default can never carry a custom trade, only a
-- master's own saved template can — defense in depth on top of the service-layer check.
ALTER TABLE estimate_templates
    ADD CONSTRAINT estimate_templates_custom_trade_invariant
        CHECK (custom_trade_id IS NULL OR (trade = 'OTHER' AND is_default = false));
