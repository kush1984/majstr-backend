-- =================================================================================================
-- V131 - a короб is sold by the metre and sheathed by the square metre, so we ASK for the section.
--
-- V127 deliberately left the короб / ніша positions unnormed, and its reasoning was right: their
-- material does not follow from the position name. «Монтаж короба (прямого) із гіпсокартону по
-- периметру стелі» is priced per м.п. of LENGTH, but what it consumes is the розгортка - the width
-- of the bottom face plus the height of the side face - which is anywhere between 0,2 m on a
-- cornice box and 1,2 m on a box hiding ventilation ducts. A per-м.п. figure would therefore be
-- wrong by a factor of six in either direction, and the sheathing is most of the cost.
--
-- What V127 got wrong was the CONCLUSION: that a figure the name cannot carry means no norm. The
-- perimeter was the same problem and got an answer - ask the master, once, and say so plainly until
-- he answers (NormBasis.PERIMETER). This migration gives the section the same treatment
-- (NormBasis.SECTION), with one difference that decides the whole shape:
--
--   the perimeter is asked ONCE PER ESTIMATE  - one room has one perimeter;
--   the section is asked ONCE PER POSITION    - a короб, a радіусний короб and a ніша in the same
--                                               estimate are three different boxes, and one number
--                                               for all three is silently wrong for two of them.
--
-- The arithmetic: amount = length (м.п., the quantity he typed) × section (m) × qty_per_unit. A
-- SECTION norm is therefore nothing exotic - it is an ordinary per-m² norm whose m² is computed as
-- length × section instead of being typed. That is what makes the split below honest:
--
--   longitudinal material stays QUANTITY - UD track runs along the box, dowels fix that track, the
--       corner bead follows the arris. All of them are "per м.п. of box", which is the position's
--       own unit, so V127's rule (a norm is written in the POSITION's unit and nothing is ever
--       converted) already handles them with no new machinery;
--   surface material becomes SECTION - board, CD ribs and screws are per m² of розгортка.
--
-- Figures. A box under a ceiling is fixed by two runs of UD (one on the ceiling, one on the wall)
-- with ribs between them, which is the standard detail every Ukrainian dealer compilation of the
-- Knauf handbook shows and the one V127 already used for the ceilings themselves:
--
--   UD    2,1 м.п./м.п.  - two runs plus the 5 % V127 puts on every profile
--   CD    2,2 м.п./m²    - ribs at ~450 mm, each as long as the розгортка (radius box: 3,0, denser)
--   screw 30 /m²         - the same figure as every other sheathing norm in V127 (40 on a bend)
--   dowel 4,2 /м.п.      - the two UD runs at a ~500 mm step, plus 5 %
--   кутник 1,05 м.п./м.п.- ONE external arris runs along a straight box; a radius box has none
--
-- Deliberately NOT normed here: «Монтаж укосів із гіпсокартону» (a reveal is one face of a width,
-- not a box - it needs a different question, not this one) and «Облаштування ніші ГКЛ з
-- підсвічуванням» (depth × height × width, and the light fitting is the master's choice). Asking
-- the wrong question is worse than asking none: see the coverage report, which names both.
--
-- No catalog_templates row is inserted, so V118's ranking is not re-run - nothing to rank.
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 1. The third basis.
-- -------------------------------------------------------------------------------------------------
ALTER TABLE material_norm DROP CONSTRAINT material_norm_basis_check;
ALTER TABLE material_norm ADD CONSTRAINT material_norm_basis_check
    CHECK (basis IN ('QUANTITY', 'PERIMETER', 'SECTION'));

COMMENT ON COLUMN material_norm.basis IS
    'What qty_per_unit multiplies. QUANTITY = the estimate line quantity. PERIMETER = the room perimeter, asked once per estimate. SECTION = the box section (розгортка), asked once per POSITION; the amount is length x section x qty_per_unit, so a SECTION norm is a per-m2 norm whose m2 is not typed.';

-- -------------------------------------------------------------------------------------------------
-- 2. The one material the dictionary was missing. No package: a corner bead is sold in 2,5 m and
--    3 m lengths depending on the merchant, and V127's rule is that a packaging we cannot pick is
--    better left absent than guessed - the calculator then rounds up to a whole metre instead.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name) VALUES
    (gen_random_uuid(), 'ANGLE_PERFORATED', 'Кутник перфорований', NULL, 'LINEAR_METER', NULL, NULL, NULL);

-- -------------------------------------------------------------------------------------------------
-- 3. The norms. All three positions are LINEAR_METER - V27 shipped them so, V116 only renamed them.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'DRYWALL', v.name_key, 'LINEAR_METER', m.id, v.qty, v.basis, v.ord
FROM (VALUES
    -- ---- A straight box around the ceiling ------------------------------------------------------
    ('монтаж короба (прямого) із гіпсокартону по периметру стелі', 'GKL_SHEET',        1.0,  'SECTION',  1),
    ('монтаж короба (прямого) із гіпсокартону по периметру стелі', 'PROFILE_UD',       2.1,  'QUANTITY', 2),
    ('монтаж короба (прямого) із гіпсокартону по периметру стелі', 'PROFILE_CD',       2.2,  'SECTION',  3),
    ('монтаж короба (прямого) із гіпсокартону по периметру стелі', 'ANGLE_PERFORATED', 1.05, 'QUANTITY', 4),
    ('монтаж короба (прямого) із гіпсокартону по периметру стелі', 'SCREW_TN25',       30,   'SECTION',  5),
    ('монтаж короба (прямого) із гіпсокартону по периметру стелі', 'DOWEL_NAIL',       4.2,  'QUANTITY', 6),

    -- ---- The same box, bent. Arched board, ribs twice as dense, no straight arris to protect ----
    ('монтаж короба (радіусного) із гіпсокартону по периметру стелі', 'GKL_SHEET_ARCH', 1.0,  'SECTION',  1),
    ('монтаж короба (радіусного) із гіпсокартону по периметру стелі', 'PROFILE_UD',     2.1,  'QUANTITY', 2),
    ('монтаж короба (радіусного) із гіпсокартону по периметру стелі', 'PROFILE_CD',     3.0,  'SECTION',  3),
    ('монтаж короба (радіусного) із гіпсокартону по периметру стелі', 'SCREW_TN25',     40,   'SECTION',  4),
    ('монтаж короба (радіусного) із гіпсокартону по периметру стелі', 'DOWEL_NAIL',     4.2,  'QUANTITY', 5),

    -- ---- «ніша під прихований карниз / короб під комунікації» is a box too, and the position name
    --      says as much. The cornice TRACK is its own position («монтаж треків прихованого
    --      карниза», already normed in V127) and is deliberately not repeated here.
    ('монтаж ніші під прихований карниз короб під комунікації', 'GKL_SHEET',        1.0,  'SECTION',  1),
    ('монтаж ніші під прихований карниз короб під комунікації', 'PROFILE_UD',       2.1,  'QUANTITY', 2),
    ('монтаж ніші під прихований карниз короб під комунікації', 'PROFILE_CD',       2.2,  'SECTION',  3),
    ('монтаж ніші під прихований карниз короб під комунікації', 'ANGLE_PERFORATED', 1.05, 'QUANTITY', 4),
    ('монтаж ніші під прихований карниз короб під комунікації', 'SCREW_TN25',       30,   'SECTION',  5),
    ('монтаж ніші під прихований карниз короб під комунікації', 'DOWEL_NAIL',       4.2,  'QUANTITY', 6)
) AS v(name_key, code, qty, basis, ord)
JOIN material m ON m.code = v.code;

-- -------------------------------------------------------------------------------------------------
-- 4. Self-checks. These run at apply time against a real schema, which is why a green Testcontainers
--    boot IS the assertion for a data-only migration.
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    orphan text;
    pairs  int;
    clash  int;
BEGIN
    -- 4a. V127's check, verbatim in intent: a norm whose name_key names no live position is dead
    --     weight that looks like a working figure. The normalisation must match NameKeys.of.
    SELECT string_agg(DISTINCT n.name_key, ', ') INTO orphan
      FROM material_norm n
     WHERE n.owner_id IS NULL
       AND n.basis = 'SECTION'
       AND NOT EXISTS (
           SELECT 1
             FROM catalog_templates t
            WHERE t.trade = 'DRYWALL'
              AND t.type = 'WORK'
              AND t.unit = n.unit
              AND lower(btrim(replace(replace(regexp_replace(t.name, '\s+', ' ', 'g'),
                                              '( ', '('), ' )', ')'))) = n.name_key);
    IF orphan IS NOT NULL THEN
        RAISE EXCEPTION 'V131: a SECTION norm names a position the catalog does not ship: %', orphan;
    END IF;

    -- 4b. Every pair landed. A missing material code silently drops rows through the JOIN.
    SELECT count(*) INTO pairs
      FROM material_norm n
     WHERE n.owner_id IS NULL
       AND n.name_key IN ('монтаж короба (прямого) із гіпсокартону по периметру стелі',
                          'монтаж короба (радіусного) із гіпсокартону по периметру стелі',
                          'монтаж ніші під прихований карниз короб під комунікації');
    IF pairs <> 17 THEN
        RAISE EXCEPTION 'V131: expected 17 box norms, found %', pairs;
    END IF;

    -- 4c. Two materials at the same rank in one position render in an arbitrary order.
    SELECT count(*) INTO clash
      FROM (SELECT n.name_key, n.sort_order
              FROM material_norm n
             WHERE n.owner_id IS NULL
               AND n.name_key IN ('монтаж короба (прямого) із гіпсокартону по периметру стелі',
                                  'монтаж короба (радіусного) із гіпсокартону по периметру стелі',
                                  'монтаж ніші під прихований карниз короб під комунікації')
             GROUP BY n.name_key, n.sort_order
            HAVING count(*) > 1) AS dup;
    IF clash > 0 THEN
        RAISE EXCEPTION 'V131: % (position, rank) pairs are shared by two materials', clash;
    END IF;

    -- 4d. A box's sheathing must be SECTION and its track must not be: getting this backwards buys
    --     either a sixth of the board or six times the track, with nothing looking broken.
    IF EXISTS (SELECT 1 FROM material_norm n JOIN material m ON m.id = n.material_id
                WHERE n.owner_id IS NULL AND m.code IN ('GKL_SHEET', 'GKL_SHEET_ARCH')
                  AND n.name_key LIKE 'монтаж короба%' AND n.basis <> 'SECTION') THEN
        RAISE EXCEPTION 'V131: a box board norm is not SECTION-based';
    END IF;
    IF EXISTS (SELECT 1 FROM material_norm n JOIN material m ON m.id = n.material_id
                WHERE n.owner_id IS NULL AND m.code = 'PROFILE_UD'
                  AND n.name_key LIKE 'монтаж короба%' AND n.basis <> 'QUANTITY') THEN
        RAISE EXCEPTION 'V131: a box track norm is not QUANTITY-based';
    END IF;
END $$;
