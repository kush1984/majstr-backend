-- «%» стає відсотком ВІД ЧОГОСЬ, і сума рядка починає зберігатися.
--
-- Until now a PERCENT position multiplied like any other unit: 10 × 500 = 5 000 ₴, printed as
-- «10 % · 500 ₴/%» — "five hundred hryvnia for one percent", which means nothing. A percent is a
-- share OF something, and that something is one of three: a sum typed by hand, another position, or
-- the estimate's own subtotal.
--
-- ============ WHY line_total IS STORED, AND NOT DERIVED ========================
-- Six native queries (dashboard, object economy, project cards) compute a total as
-- SUM(ROUND(quantity * unit_price, 2)). A percent OF THE SUBTOTAL cannot be expressed that way at
-- all: the row depends on the total and the total depends on the row. One SUM cannot say that.
--
-- The alternatives were worse. Rewriting six aggregates to be self-referential is not possible in
-- SQL; loading every estimate's items into Java wherever one aggregate runs today turns a single
-- query into N. So the amount is computed ONCE, by the server, on every write, and stored.
--
-- Consequences that are features, not accidents:
--   · the server is the only author of this column — a client value is never accepted, so an
--     offline client computing for display cannot poison the books;
--   · a SIGNED estimate simply is not rewritten, so its numbers cannot drift behind the client's
--     back — the guarantee costs nothing because nothing recomputes on read;
--   · «backend = frontend» becomes a comparison of one number rather than of two formulas.
--
-- ============ WHY THE PERCENT ITSELF LIVES IN quantity =========================
-- quantity = the percent (10 = 10 %), unit_price = the MANUAL base (and nothing at all for the
-- other two kinds). That keeps the existing columns doing analogous jobs and needs no new money
-- column. See the catalog fix at the bottom, which exists precisely because our own seed data
-- carries the percent in the PRICE column instead.

ALTER TABLE estimate_items
    -- The computed amount of this line, in hryvnia. Always written by the server.
    ADD COLUMN line_total NUMERIC(15, 2) NOT NULL DEFAULT 0,
    -- Only meaningful for unit = 'PERCENT'; NULL on every other row.
    ADD COLUMN percent_base_kind VARCHAR(20),
    -- ON DELETE SET NULL, never CASCADE: deleting the base must not delete the percentage line.
    -- The master keeps the last computed amount and is told the base is gone — silently removing a
    -- line he is charging for would be data loss dressed up as tidiness.
    ADD COLUMN percent_base_item_id UUID REFERENCES estimate_items (id) ON DELETE SET NULL,
    -- The live link is off: he edited the amount by hand, or the base was deleted. Manual always
    -- wins over automatic — the same rule quantity_manual follows in measurements.
    ADD COLUMN base_detached BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE estimate_items
    ADD CONSTRAINT estimate_items_percent_base_kind_check
        CHECK (percent_base_kind IS NULL OR percent_base_kind IN ('MANUAL', 'POSITION', 'TOTAL'));

-- Backfill: every existing line keeps exactly the amount it already showed. There are NO PERCENT
-- lines in any estimate yet (verified against production before this shipped), so this multiplication
-- is right for every row without exception, and no estimate — signed or not — changes by a hryvnia.
UPDATE estimate_items
SET line_total = ROUND(quantity * unit_price, 2);

-- The aggregates read this column now, and they read it per estimate.
CREATE INDEX IF NOT EXISTS idx_estimate_items_estimate_line_total
    ON estimate_items (estimate_id) INCLUDE (line_total);

-- ============ THE CATALOG SHIPPED PERCENTAGES IN THE PRICE COLUMN ==============
-- V82 put nine PERCENT positions into the live tiling catalog with values like 88, 33, 50, 76, 45
-- in default_price — and those are PERCENTS, not prices: the names say so («плюс % до м.кв.»).
-- With the new model the percent belongs in quantity, so adding one of these from the catalog must
-- map default_price → percent. That mapping lives in CatalogService/EstimateService; what the
-- migration fixes is the WORDING, because two of them still say «коефіцієнт».
--
-- Coefficients are a decision we took and rejected: «коефіцієнт 1,2» is entered as «+20 %», one
-- mechanic instead of two. A catalog that keeps offering the other word invites the other mechanic.
--
-- ⚠️ Renamed in THREE places at once. estimate_template_items references positions BY NAME and looks
-- the price up in the master's own catalog — rename one side only and every bundle carrying these
-- lines silently starts quoting them at zero.
-- ============ FIRST: WHITESPACE, ON ALL THREE TABLES AT ONCE ===================
-- Several seeded names carry a stray space after the bracket — «( плюс % до м.кв.)» — and the
-- lookup that joins a bundle line to a catalog position is EXACT on the lowercased name. Today all
-- 167 tiling names align on both sides, so nothing is broken; the danger is that fixing the wording
-- on one side alone would have silently priced six bundle lines at zero.
--
-- Normalising both sides in one statement removes the class of bug rather than this instance of it.
-- (EstimateTemplateService now also normalises before comparing, so a future drift cannot bite.)
UPDATE catalog_templates SET name = btrim(regexp_replace(regexp_replace(name, '\s+', ' ', 'g'), '\(\s+', '(', 'g'))
WHERE name <> btrim(regexp_replace(regexp_replace(name, '\s+', ' ', 'g'), '\(\s+', '(', 'g'));

UPDATE catalog_items SET name = btrim(regexp_replace(regexp_replace(name, '\s+', ' ', 'g'), '\(\s+', '(', 'g'))
WHERE name <> btrim(regexp_replace(regexp_replace(name, '\s+', ' ', 'g'), '\(\s+', '(', 'g'));

UPDATE estimate_template_items SET name = btrim(regexp_replace(regexp_replace(name, '\s+', ' ', 'g'), '\(\s+', '(', 'g'))
WHERE name <> btrim(regexp_replace(regexp_replace(name, '\s+', ' ', 'g'), '\(\s+', '(', 'g'));

-- ⚠️ catalog_items is normalised for EVERY row, including a master's own — unlike the semantic
-- rename below, which touches only rows we gave him. Collapsing a double space is not a change of
-- meaning, and leaving his side un-normalised while both of ours move is exactly how the link
-- breaks for him alone.

-- ============ THEN: the two names that still say «коефіцієнт» ==================
CREATE TEMP TABLE percent_renames (old_name TEXT, new_name TEXT) ON COMMIT DROP;
INSERT INTO percent_renames VALUES
    ('Укладання плитки на відкос (коефіцієнт від м.кв.)', 'Укладання плитки на відкос (плюс % до м.кв.)'),
    ('Коефіцієнт на погодні умови',                       'Погодні умови (плюс % до кошторису)');

-- 1. our defaults
UPDATE catalog_templates ct
SET name = r.new_name
FROM percent_renames r
WHERE ct.name = r.old_name;

-- 2. the copies masters already hold. Only rows WE gave them (source = 'LIBRARY'); a position a
--    master typed or renamed himself is his, and is left alone — the V83 rule.
UPDATE catalog_items ci
SET name = r.new_name
FROM percent_renames r
WHERE ci.name = r.old_name
  AND ci.source = 'LIBRARY';

-- 3. the bundle lines that join to those names.
UPDATE estimate_template_items ti
SET name = r.new_name
FROM percent_renames r
WHERE ti.name = r.old_name;

-- ⚠️ NO catalog_update_notices row is written here, and that is a decision rather than an omission.
-- That table expresses one thing — how many positions were ADDED and REMOVED (V83) — and this
-- migration adds and removes none. Filling it with 0/0 would show the master an empty notice, which
-- is worse than no notice: it trains him to dismiss the one that will matter.
--
-- The rule from V83 still stands — a master whose catalog we rewrite has to be told — and it is
-- satisfied differently here: nothing he can quote from changed value, only the wording of six
-- names, and the behavioural change («%» is now a percentage of something) is a feature to announce
-- in the app, not a silent rewrite of his price list. Carrying a free-text message on that table is
-- tracked in open-questions instead of being bolted on inside a migration.
