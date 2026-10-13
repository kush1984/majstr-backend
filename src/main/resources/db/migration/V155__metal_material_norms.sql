-- =================================================================================================
-- V155 — the trade-by-trade review, batch 8: METAL's first material norms
--
-- Source: `C:\Work\prompts\trade-review\09-METAL.md` §3, re-read against the V154 library. METAL was
-- the last trade with no norm at all. Fabrication is a drawing — how much steel and of which section
-- is the specific gate, grille or truss, never the position name — so the honest answers are the
-- finishes (primer, enamel), the sheet goods a fence or a canopy is clad with, and the bought pieces
-- a position names one at a time.
--
-- OWNER RULINGS (2026-10-13), all as recommended:
--   1. Concrete for a post is a FIXED figure per post, not a depth question: THICKNESS is bounded at
--      150 mm (review round 2 §2), and a post hole is a metre deep. A fence post: Ø200 hole − a 60×60
--      post = 0,0278 m² × 1,0 m × ≈1950 kg/m³ ≈ 55 kg; a gate post: Ø300 − 100×100 = 0,0607 m² ×
--      1,2 m ≈ 140 kg. A master who digs differently forks the figure.
--   2. Metal enamel ships at 0,20 kg/m² for two coats and is NOT rescaled by the paint habit — the
--      code deliberately does not start with `PAINT_`: `PAINT_COVERAGE` is his answer about wall
--      paint, the reasoning that keeps ENAMEL_WOOD out (review B-49).
--   3. The sliding-gate hardware kit and the post caps are normed at one per position, like V152's
--      mounting kits.
--   4. Fabrication, welding by the hour and the welded fence frame stay unnormed.
--
-- DELIBERATELY UNNORMED: every «Виготовлення…» (the steel is the drawing); welding by the hour (no
-- published wire or gas per hour for a one-man shop) and «Зварювання каркасу та лаг паркану»
-- (electrodes per metre or tonne of structure are unproven); erecting steel by the kg; hanging a gate,
-- a wicket, a grille, a porch canopy, railings (fixings depend on the piece); canopy posts (concrete or
-- anchors — the name does not say); the sliding-gate footing (its size follows the gate); the gate
-- automation (the client's equipment, as in V153); blasting and degreasing (no source for the abrasive
-- or the solvent).
--
-- Every norm carries `waste_percent = 0` (batch 2's ruling). No `catalog_templates` row is inserted.
-- None of the normed positions is shipped by another trade, so every row is filed under METAL.
--
-- Self-checks `RAISE EXCEPTION` only about THIS migration's own work (review B-50).
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 1. Dictionary rows — 14 of them, brand-free. A package only where the shelf agrees on one.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name) VALUES
    -- Farbex ГФ-021 and ПФ-115П come in 0,9 and 2,8 kg tins (enamel also 0,25), so no package.
    (gen_random_uuid(), 'PRIMER_METAL',       'Ґрунтовка антикорозійна по металу',  NULL, 'KG', NULL, NULL, NULL),
    (gen_random_uuid(), 'ENAMEL_METAL',       'Емаль по металу',                    NULL, 'KG', NULL, NULL, NULL),
    -- Sheets are cut to order, so the answer is in m².
    (gen_random_uuid(), 'PROFNASTYL',         'Профнастил',                         NULL, 'M2', NULL, NULL, NULL),
    (gen_random_uuid(), 'SCREW_ROOFING',      'Саморіз покрівельний з ущільнювальною шайбою', NULL, 'PIECE', NULL, NULL, NULL),
    -- The 2,1 × 6 m sheet is the one the shelf agrees on.
    (gen_random_uuid(), 'POLYCARBONATE_CELL', 'Полікарбонат стільниковий',          NULL, 'M2', 12.6, 'M2', 'лист'),
    (gen_random_uuid(), 'THERMO_WASHER',      'Термошайба',                         NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'PROFILE_PC_CONNECT', 'Профіль з''єднувальний для полікарбонату', NULL, 'LINEAR_METER', 6, 'LINEAR_METER', 'хлист'),
    (gen_random_uuid(), 'PANEL_3D',           'Панель 3D-огорожі',                  NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'POST_3D',            'Стовп для 3D-огорожі',               NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'CLAMP_3D',           'Кріплення панелі 3D-огорожі',        NULL, 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'CONCRETE_DRY_M300',  'Суміш бетонна суха М300',            NULL, 'KG', 25, 'KG', 'мішок'),
    -- ANCHOR_WEDGE (6×40) is too small for a base plate.
    (gen_random_uuid(), 'ANCHOR_BASEPLATE',   'Анкер для опорної пластини',         'М10–М12', 'PIECE', NULL, NULL, NULL),
    (gen_random_uuid(), 'SLIDING_GATE_KIT',   'Комплект фурнітури для відкатних воріт', NULL, 'SET', NULL, NULL, NULL),
    (gen_random_uuid(), 'POST_CAP',           'Ковпак (заглушка) для стовпа',       NULL, 'PIECE', NULL, NULL, NULL);

-- =================================================================================================
-- 2. Sources behind the figures (the report's §3.1 in short form).
--
--   Farbex ГФ-021: 60-100 g/m² a coat → 0,10 kg/m² (the top, for a profile's edges and ends).
--   Farbex ПФ-115П: 70-120 g/m² a coat; Farbex 3 в 1: 50-100 → 0,10 a coat, two coats 0,20.
--   Blachotrapez: «5-8 szt./m2» screws for trapezoidal sheet → 6 on a fence, 8 on a roof; the sheet
--     at 1,05 m²/m² on a fence (side lap and offcuts) and 1,10 on a roof (end lap) is a calculation.
--   Palram SUNLITE: fix about every 500 mm along a purlin, 300 mm at the edge; purlins 0,8 m apart →
--     ≈4 washers/m². A connector every 2,1 m of sheet width → 0,48 m/m². Sheet 1,10 m²/m² (calc).
--   Betafence Nylofor 3D: panels 2500 mm wide → 0,4 panel and 0,4 post a metre; 3 clamps a post
--     (no source — a master with tall panels forks to 4). The run's last post is the master's.
--   OSHA 29 CFR 1926.755(a)(1): «a minimum of 4 anchor rods» per column → 4 per plate.
--   Concrete: dry mixes set at 1800-2000 kg/m³ (NORMS-SUMMARY §2.8, median 1900) — ruling 1.
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 3. METAL norms.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'METAL', v.name_key, v.unit, m.id, v.qty, 'QUANTITY', v.ord
FROM (VALUES
    -- ---- Finishes (ruling 2) ---------------------------------------------------------------------
    ('ґрунтування металу',                      'M2', 'PRIMER_METAL',       0.10::numeric, 1),
    ('фарбування металу',                       'M2', 'ENAMEL_METAL',       0.20, 1),

    -- ---- Cladding --------------------------------------------------------------------------------
    ('монтаж профнастилу на паркан',            'M2', 'PROFNASTYL',         1.05, 1),
    ('монтаж профнастилу на паркан',            'M2', 'SCREW_ROOFING',      6,    2),
    ('монтаж покрівлі навісу з профнастилу',    'M2', 'PROFNASTYL',         1.10, 1),
    ('монтаж покрівлі навісу з профнастилу',    'M2', 'SCREW_ROOFING',      8,    2),
    ('монтаж покрівлі навісу з полікарбонату',  'M2', 'POLYCARBONATE_CELL', 1.10, 1),
    ('монтаж покрівлі навісу з полікарбонату',  'M2', 'THERMO_WASHER',      4,    2),
    ('монтаж покрівлі навісу з полікарбонату',  'M2', 'PROFILE_PC_CONNECT', 0.48, 3),

    -- ---- Fences ----------------------------------------------------------------------------------
    ('монтаж секції 3d-сітки',                  'LINEAR_METER', 'PANEL_3D', 0.4, 1),
    ('монтаж секції 3d-сітки',                  'LINEAR_METER', 'POST_3D',  0.4, 2),
    ('монтаж секції 3d-сітки',                  'LINEAR_METER', 'CLAMP_3D', 1.2, 3),
    ('монтаж стовпів для паркану',              'PIECE', 'CONCRETE_DRY_M300', 55,  1),
    ('монтаж заглушок і ковпаків на стовпи',    'PIECE', 'POST_CAP',          1,   1),

    -- ---- Gates and frames (rulings 1 and 3) ------------------------------------------------------
    ('бетонування стовпів під ворота',          'PIECE', 'CONCRETE_DRY_M300', 140, 1),
    ('монтаж фурнітури відкатних воріт (балка, ролики, вловлювачі)', 'SET', 'SLIDING_GATE_KIT', 1, 1),
    ('анкерування опорних пластин',             'PIECE', 'ANCHOR_BASEPLATE',  4,   1)
) AS v(name_key, unit, code, qty, ord)
JOIN material m ON m.code = v.code;

-- -------------------------------------------------------------------------------------------------
-- 4. «Checked, consumes nothing» — services bought outside, taking steel down, and cutting, bending
--    and drilling (a disc and a bit are tool wear, as V153's chasing and drilling).
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'METAL', v.name_key, v.unit, NULL, NULL, 'QUANTITY', 0
FROM (VALUES
    ('гаряче цинкування (послуга)',   'KG'),
    ('порошкове фарбування (послуга)', 'M2'),
    ('демонтаж металоконструкцій',    'KG'),
    ('демонтаж старого паркану',      'LINEAR_METER'),
    ('різка металу',                  'LINEAR_METER'),
    ('гнуття профтруби та листа',     'PIECE'),
    ('свердління отворів у металі',   'PIECE')
) AS v(name_key, unit);

-- -------------------------------------------------------------------------------------------------
-- 5. Self-checks, each about THIS migration's own work (review B-50).
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    orphans   text;
    ambiguous text;
    normed    int;
BEGIN
    -- 5a. Every METAL key names a live METAL WORK position (a typo here is mine).
    SELECT string_agg(DISTINCT n.name_key || ' [' || n.unit || ']', ', ')
      INTO orphans
      FROM material_norm n
     WHERE n.owner_id IS NULL
       AND n.trade = 'METAL'
       AND NOT EXISTS (
            SELECT 1 FROM catalog_templates t
             WHERE t.trade = 'METAL' AND t.type = 'WORK' AND t.unit = n.unit
               AND lower(btrim(replace(replace(regexp_replace(t.name, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))) = n.name_key
       );
    IF orphans IS NOT NULL THEN
        RAISE EXCEPTION 'V155: METAL norms name no live catalog position: %', orphans;
    END IF;

    -- 5b. Two trades norming one (name, unit) cannot be resolved.
    SELECT string_agg(name_key || ' [' || unit || ']', ', ' ORDER BY name_key)
      INTO ambiguous
      FROM (SELECT name_key, unit FROM material_norm
             WHERE owner_id IS NULL
             GROUP BY name_key, unit
            HAVING count(DISTINCT trade) > 1
                OR (count(DISTINCT trade) = 1 AND count(trade) <> count(*))) AS a;
    IF ambiguous IS NOT NULL THEN
        RAISE EXCEPTION 'V155: a name and unit two trades both norm cannot be resolved: %', ambiguous;
    END IF;

    -- 5c. Row count: 11 positions with a material + 7 recorded verdicts, so a half-applied list cannot
    --     pass quietly. With the trade-less cleanup row, 19 of 56 positions answer.
    SELECT count(DISTINCT name_key || '|' || unit) INTO normed
      FROM material_norm WHERE owner_id IS NULL AND trade = 'METAL';
    IF normed <> 18 THEN
        RAISE EXCEPTION 'V155: expected 18 normed METAL positions, found %', normed;
    END IF;
END $$;
