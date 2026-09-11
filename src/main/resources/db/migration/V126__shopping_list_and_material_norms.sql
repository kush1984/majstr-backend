-- =================================================================================================
-- V126 — the shopping list, the material dictionary, consumption norms, and the LITRE unit
-- (material-calculator iteration, cut 1 — the infrastructure half; the DRYWALL norms land in V127).
--
-- What this migration is FOR
--   A master builds an estimate of WORK and then has to answer a different question on his own:
--   "how much material do I buy". Today nothing in the product derives one from the other. The
--   calculator that will (V127 + the engine) needs four things that do not exist yet: a dictionary
--   of materials, consumption norms per kind of work, the master's own habitual parameters, and a
--   place for the answer to live — the shopping list.
--
-- Four decisions worth reading before changing anything here
--
--   1. A norm has NO foreign key to the catalog. `catalog_items` has no link to `catalog_templates`
--      at all, and the templates themselves are deleted and recreated by every catalog rebuild
--      (V82, V116, V122) — an FK to either is broken by construction. Norms are keyed the way the
--      rest of this codebase already joins catalog to estimate lines: by NAME and UNIT.
--
--   2. `trade` is stored on a norm but is only the FIRST RUNG of the lookup, never the whole key.
--      `estimate_items.trade` is nullable by design (V125: ADDENDUM lines and hand-typed lines),
--      V118 stores a position two trades both ship exactly once — under whichever trade claimed it
--      first — and both the V125 backfill and `EstimateService.resolveTrade` DERIVE the trade from
--      (name, type, unit). Trade is a property of a position's identity, not part of it. The
--      engine therefore looks up (trade, name_key, unit) and falls back to (name_key, unit);
--      hence two indexes, and the second one is the important one.
--
--   3. `material` carries NO price and NO owner. V81 deliberately deleted invented material prices
--      from the default catalog: "a stale guess competing with a real number is worse than no
--      guess". Quantities are this feature's output; a price is born either in the master's own
--      catalog or on a shop receipt.
--
--   4. The shopping list is OBJECT-scoped but a calculation is ESTIMATE-scoped, so every calculated
--      row records which estimate produced it (`source_estimate_id`). That single column is what
--      lets a re-run REPLACE its own contribution instead of adding to it — without it, a master
--      who fixes a typo and recalculates gets double the material.
--
-- The list never touches money: nothing here writes `object_expenses`, and a ticked row carries no
-- amount. An expense is still created only by a receipt, which is also where real prices come from.
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 1. New unit: LITRE. Primer, paint and adhesive are sold and consumed by the litre and `Unit` had
--    no way to say so. Old migrations are immutable, so every live unit CHECK is dropped and
--    recreated with the extended set — same pattern as V18/V26/V27/V45/V80. Purely additive.
--    Four tables carry a unit CHECK; `measurement_item` has its own, much smaller one and is
--    deliberately NOT touched (a measurement is never taken in litres).
-- -------------------------------------------------------------------------------------------------
ALTER TABLE catalog_items           DROP CONSTRAINT catalog_items_unit_check;
ALTER TABLE catalog_items           ADD  CONSTRAINT catalog_items_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM', 'DAY', 'FLOOR', 'LITRE'));

ALTER TABLE estimate_items          DROP CONSTRAINT estimate_items_unit_check;
ALTER TABLE estimate_items          ADD  CONSTRAINT estimate_items_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM', 'DAY', 'FLOOR', 'LITRE'));

ALTER TABLE catalog_templates       DROP CONSTRAINT catalog_templates_unit_check;
ALTER TABLE catalog_templates       ADD  CONSTRAINT catalog_templates_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM', 'DAY', 'FLOOR', 'LITRE'));

ALTER TABLE estimate_template_items DROP CONSTRAINT estimate_template_items_unit_check;
ALTER TABLE estimate_template_items ADD  CONSTRAINT estimate_template_items_unit_check
    CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM', 'DAY', 'FLOOR', 'LITRE'));

-- -------------------------------------------------------------------------------------------------
-- 2. `material` — the dictionary. Names and packaging only.
-- -------------------------------------------------------------------------------------------------
CREATE TABLE material (
    id            uuid PRIMARY KEY,
    name          varchar(255) NOT NULL,
    -- Specification kept OUT of the name on purpose: «Лист ГКЛ» + spec «1200×2500». A parameter
    -- («which sheet do you buy») has nothing to choose between if the size is baked into the name.
    spec          varchar(100),
    unit          varchar(20)  NOT NULL,
    -- How it is SOLD: 25 (kg) of putty, 1000 (pieces) of screws, 10 (litres) of primer. The
    -- calculator rounds UP to this, because half a bag cannot be bought.
    package_size  numeric(15, 3),
    package_unit  varchar(20),
    created_at    timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT material_unit_check
        CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM', 'DAY', 'FLOOR', 'LITRE')),
    CONSTRAINT material_package_unit_check
        CHECK (package_unit IS NULL OR package_unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM', 'DAY', 'FLOOR', 'LITRE')),
    -- A size without a unit means nothing, and a unit without a size has nothing to round to.
    CONSTRAINT material_package_pair_check
        CHECK ((package_size IS NULL) = (package_unit IS NULL)),
    CONSTRAINT material_package_size_check
        CHECK (package_size IS NULL OR package_size > 0),
    -- NULLS NOT DISTINCT is load-bearing: with a plain UNIQUE, Postgres treats every NULL `spec`
    -- as distinct, so two rows ('Шпаклівка фінішна', NULL) would both insert — and the "one row per
    -- material" guarantee would break exactly where most materials live (no specification at all).
    CONSTRAINT ux_material_name_spec UNIQUE NULLS NOT DISTINCT (name, spec)
);

COMMENT ON TABLE material IS
    'Dictionary of construction materials for the material calculator (V126). Deliberately carries '
    'NO price and NO owner: V81 removed invented material prices from the product, and this table '
    'exists to normalise names and packaging, not to compete with the receipt import.';

-- -------------------------------------------------------------------------------------------------
-- 3. `material_norm` — how much of a material one unit of a kind of work consumes.
-- -------------------------------------------------------------------------------------------------
CREATE TABLE material_norm (
    id             uuid PRIMARY KEY,
    -- NULL = a default norm shipped by us. A master's own norm will later hide the default under
    -- the same natural key, forked on write exactly like `template_default_override` (V113). No row
    -- carries an owner yet; the column exists so the personal norm lands in THIS table rather than
    -- in a parallel "master coefficient" mechanism beside it.
    owner_id       uuid REFERENCES users (id) ON DELETE CASCADE,
    -- First rung of the lookup only — see decision 2 in the header. Nullable because a norm may
    -- legitimately apply to a position no single trade owns.
    trade          varchar(50),
    -- The position's name, normalised by the SAME function that resolves a template's price
    -- (`NameKeys.of`). Two private notions of "the same name" would drift silently.
    name_key       varchar(255) NOT NULL,
    -- The POSITION's unit, never the material's: a position priced per м.п. consumes "per 1 м.п.".
    -- Writing a per-m² norm against a per-м.п. position is the bug that killed the first draft.
    unit           varchar(20)  NOT NULL,
    material_id    uuid         NOT NULL REFERENCES material (id) ON DELETE CASCADE,
    qty_per_unit   numeric(15, 4) NOT NULL,
    waste_percent  numeric(5, 2)  NOT NULL DEFAULT 0,
    sort_order     integer      NOT NULL DEFAULT 0,
    created_at     timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT material_norm_unit_check
        CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM', 'DAY', 'FLOOR', 'LITRE')),
    CONSTRAINT material_norm_trade_check
        CHECK (trade IS NULL OR trade IN
            ('ELECTRICAL', 'PLUMBING', 'TILING', 'BUILDER', 'PAINTER', 'DRYWALL',
             'FLOORING', 'DEMOLITION', 'METAL', 'GENERAL', 'OTHER')),
    CONSTRAINT material_norm_qty_check   CHECK (qty_per_unit > 0),
    CONSTRAINT material_norm_waste_check CHECK (waste_percent >= 0 AND waste_percent <= 100),
    CONSTRAINT ux_material_norm UNIQUE NULLS NOT DISTINCT (owner_id, trade, name_key, unit, material_id)
);

-- The SECOND rung of the ladder, and the one that carries the load: a line with a NULL trade
-- (V125) or a trade that disagrees with the norm's (V118) is found only here.
CREATE INDEX idx_material_norm_name_unit  ON material_norm (name_key, unit);
CREATE INDEX idx_material_norm_trade_name ON material_norm (trade, name_key, unit);

COMMENT ON COLUMN material_norm.trade IS
    'First rung of the norm lookup, not the key. estimate_items.trade is nullable by design (V125) '
    'and V118 files a position shared by two trades under only one of them, so the engine falls '
    'back to (name_key, unit). Never make this column part of a mandatory lookup.';

-- -------------------------------------------------------------------------------------------------
-- 4. `master_material_pref` — the master's HABITS, and nothing else.
--    A room's perimeter or ceiling height is deliberately absent: those are properties of the
--    OBJECT, they have no meaningful default, and pre-filling one from the previous flat would
--    announce another object's number as this master's answer.
-- -------------------------------------------------------------------------------------------------
CREATE TABLE master_material_pref (
    id         uuid PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    pref_key   varchar(40)  NOT NULL,
    pref_value varchar(100) NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT master_material_pref_key_check
        CHECK (pref_key IN ('TILE_SIZE', 'TILE_JOINT_MM', 'TILE_THICKNESS_MM', 'TILE_LAYOUT',
                            'PAINT_COVERAGE', 'PAINT_COATS', 'GKL_SHEET', 'WASTE_PERCENT')),
    CONSTRAINT ux_master_material_pref UNIQUE (user_id, pref_key)
);

-- -------------------------------------------------------------------------------------------------
-- 5. The shopping list — one per object.
-- -------------------------------------------------------------------------------------------------
CREATE TABLE shopping_list (
    id          uuid PRIMARY KEY,
    project_id  uuid NOT NULL UNIQUE REFERENCES projects (id) ON DELETE CASCADE,
    -- A finished or cancelled object archives its list instead of losing it: the home-screen card
    -- disappears, the content stays reachable from the object. Limit what gets created, never take
    -- away access to what already was.
    archived_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE shopping_list_item (
    id                 uuid PRIMARY KEY,
    shopping_list_id   uuid NOT NULL REFERENCES shopping_list (id) ON DELETE CASCADE,
    -- NULL for a hand-written row: the master may buy something our dictionary has never heard of.
    material_id        uuid REFERENCES material (id) ON DELETE SET NULL,
    name               varchar(255) NOT NULL,
    unit               varchar(20)  NOT NULL,
    quantity           numeric(15, 3) NOT NULL,
    bought             boolean      NOT NULL DEFAULT false,
    bought_at          timestamptz,
    -- "Clear bought" HIDES rows; it must never delete them. A deleted bought row is re-added by the
    -- next recalculation as unbought, and the master buys the same material twice. A hidden row
    -- still counts as settled, which is exactly what blocks the re-add.
    cleared_at         timestamptz,
    -- The master corrected this quantity by hand. A recalculation shows him the difference instead
    -- of overwriting his number.
    edited             boolean      NOT NULL DEFAULT false,
    source             varchar(20)  NOT NULL,
    -- Which estimate produced this row. The whole "a re-run replaces its own contribution" rule
    -- hangs off this column.
    source_estimate_id uuid REFERENCES estimates (id) ON DELETE SET NULL,
    -- Kept for a later plan-vs-fact comparison; nothing reads it yet. SET NULL because deleting an
    -- estimate line must not take a shopping row (and the master's list) with it.
    estimate_item_id   uuid REFERENCES estimate_items (id) ON DELETE SET NULL,
    note               varchar(500),
    sort_order         integer      NOT NULL DEFAULT 0,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),

    -- The merge key, in the schema rather than only in the service: a dictionary material merges by
    -- id, a hand-written row by its normalised name and unit (so «Клей  Ceresit» and «Клей Ceresit»
    -- are one row, not two).
    dedup_key text GENERATED ALWAYS AS (
        coalesce(material_id::text, lower(btrim(name)) || '|' || unit)
    ) STORED,

    CONSTRAINT shopping_list_item_unit_check
        CHECK (unit IN ('M2', 'M', 'LINEAR_METER', 'PIECE', 'KG', 'HOUR', 'SET', 'M3', 'T', 'POINT', 'PERCENT', 'KM', 'DAY', 'FLOOR', 'LITRE')),
    CONSTRAINT shopping_list_item_source_check
        CHECK (source IN ('CALCULATOR', 'ESTIMATE', 'MANUAL')),
    -- A calculated row without its estimate cannot be replaced on a re-run, only duplicated.
    CONSTRAINT shopping_list_item_calculated_source_check
        CHECK (source <> 'CALCULATOR' OR source_estimate_id IS NOT NULL),
    CONSTRAINT shopping_list_item_quantity_check CHECK (quantity > 0)
);

CREATE INDEX idx_shopping_list_item_list ON shopping_list_item (shopping_list_id);

-- At most ONE open (unbought, uncleared) row per material per CONTRIBUTION, where a contribution is
-- (source, source_estimate_id). Bought rows are deliberately outside the index: several of them may
-- accumulate for the same material as the master buys in instalments, and each records what he
-- actually bought at the time. `source` is part of the key so that a future 'copy the MATERIALS
-- lines of estimate E' path can coexist with E's calculated rows instead of colliding with them.
--
-- MANUAL rows are OUTSIDE the index, and that is a decision, not an omission: a hand-written row is
-- created under a client-supplied id (offline authoring), so merging one into an existing row would
-- leave a replayed create with nothing to find and it would add the quantity a second time. A row
-- the master typed is also his number — silently folding it into another one is exactly what the
-- rest of this feature refuses to do. Two identical hand-written rows are visible and editable.
CREATE UNIQUE INDEX ux_shopping_list_item_open
    ON shopping_list_item (shopping_list_id, source, source_estimate_id, dedup_key)
    NULLS NOT DISTINCT
    WHERE bought = false AND cleared_at IS NULL AND source <> 'MANUAL';

COMMENT ON TABLE shopping_list IS
    'One shopping list per object (V126). Never creates an ObjectExpense: a ticked row carries no '
    'amount, and money still enters only through a receipt.';
