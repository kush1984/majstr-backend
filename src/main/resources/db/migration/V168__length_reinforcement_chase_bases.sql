-- =================================================================================================
-- V168 — three more questions the calculator can ask per POSITION: a length, a rebar ratio, and a
--        chase's cross-section
--
-- Source: `TRADES-REVIEW.md` §1.8 and the open-questions item «The calculator bases the review asked
-- for and batch 1 did not add». V152 shipped the fourth (STEP). Owner ruling (2026-10-13): «давай
-- далі способи рахунку». All three MULTIPLY by a figure the position does not carry, asked per
-- position, stored in `estimate_material_param` like the others, and bounded per question
-- (`MaterialCalculatorService`, `MaterialParamsRequest`):
--
--   LENGTH         metres, ≤ 100. A riser is sold per piece and its pipe is the storey's height —
--                  V152 normed the tee and the clamps and left the pipe for exactly this reason.
--                  Suggested 3,0 m: a storey (2,7-2,8 m) plus the overlap into the slab.
--   REINFORCEMENT  kg/m³, ≤ 300. A concrete volume buys its rebar as volume × ratio, which only the
--                  design knows (≈60 for a strip footing, 100-150 for a slab or a column). Asked
--                  with NO suggestion: a number here would be a guess wearing his name on a list.
--   CHASE_SECTION  cm², ≤ 200. Width × depth, which one SECTION number (a box's розгортка — a SUM)
--                  cannot say. V153's 0,4 kg/m of gypsum was a 20×20 chase; the figure is now
--                  0,1 kg per cm² per metre with 4 cm² suggested, so a 40×40 bundle chase buys four
--                  times the gypsum instead of the same 0,4.
--
-- «Area from mass» (primer and paint by the tonne of steel) is NOT here: METAL's bundles carry the
-- primer and the paint as their own m² positions, so a norm on the kg position would buy them twice.
-- The 127/t · 255/t rule is in those positions' descriptions (V166).
--
-- B-108's rule holds: only SHIPPED norms (`owner_id IS NULL`) are touched. A master who forked the
-- chase gypsum keeps his own QUANTITY figure — an owned norm is never rescaled.
--
-- Self-checks `RAISE EXCEPTION` only about THIS migration's own work (review B-50).
-- =================================================================================================

ALTER TABLE material_norm DROP CONSTRAINT material_norm_basis_check;
ALTER TABLE material_norm ADD CONSTRAINT material_norm_basis_check
    CHECK (basis IN ('QUANTITY', 'PERIMETER', 'SECTION', 'THICKNESS', 'STEP',
                     'LENGTH', 'REINFORCEMENT', 'CHASE_SECTION'));

ALTER TABLE estimate_material_param DROP CONSTRAINT estimate_material_param_basis;
ALTER TABLE estimate_material_param ADD CONSTRAINT estimate_material_param_basis
    CHECK (basis IN ('PERIMETER', 'SECTION', 'THICKNESS', 'STEP',
                     'LENGTH', 'REINFORCEMENT', 'CHASE_SECTION'));

COMMENT ON COLUMN material_norm.qty_per_unit IS
    'Material per ONE unit of the position, in the MATERIAL''s unit. Read together with `basis`: '
    'QUANTITY multiplies the line''s own quantity, PERIMETER the room outline asked once per '
    'estimate, SECTION the developed width of a reveal, THICKNESS the layer depth in millimetres, '
    'STEP (V152) DIVIDES by the loop spacing in millimetres, and (V168) LENGTH multiplies a length in '
    'metres, REINFORCEMENT a rebar ratio in kg/m³, CHASE_SECTION a chase''s width × depth in cm². A '
    'norm whose figure bakes in a LAYOUT surplus (V147) is still a QUANTITY norm — the cutting '
    'allowance belongs to the master and stays in `waste_percent` / his WASTE_PERCENT habit.';

INSERT INTO material (id, code, name, spec, unit, package_size, package_unit, package_name) VALUES
    (gen_random_uuid(), 'PIPE_SEWER_110', 'Труба каналізаційна', 'Ø110', 'LINEAR_METER', NULL, NULL, NULL),
    (gen_random_uuid(), 'REBAR',          'Арматура',            NULL,   'KG',           NULL, NULL, NULL);

-- -------------------------------------------------------------------------------------------------
-- 1. LENGTH — a riser's pipe. The four sewer risers under their current names and the four keys
--    V167 retired (estimates already written still carry them), and the water riser.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order, default_param)
SELECT gen_random_uuid(), 'PLUMBING', v.name_key, 'PIECE', m.id, 1, 'LENGTH', 0, 3.0
  FROM (VALUES
    ('монтаж (заміна) стояка каналізації (пластиковий на пластиковий) з проходом через одну панель', 'PIPE_SEWER_110'),
    ('монтаж (заміна) стояка каналізації (пластиковий на пластиковий) з проходом через дві панелі',  'PIPE_SEWER_110'),
    ('монтаж (заміна) стояка каналізації (чавунний на пластиковий) з проходом через одну панель',    'PIPE_SEWER_110'),
    ('монтаж (заміна) стояка каналізації (чавунний на пластиковий) з проходом через дві панелі',     'PIPE_SEWER_110'),
    ('монтаж заміна стояка пластик на пластик одна панель', 'PIPE_SEWER_110'),
    ('монтаж заміна стояка пластик на пластик дві панелі',  'PIPE_SEWER_110'),
    ('монтаж заміна стояка чавун на пластик одна панель',   'PIPE_SEWER_110'),
    ('монтаж заміна стояка чавун на пластик дві панелі',    'PIPE_SEWER_110'),
    ('заміна стояка водопостачання (хвп/гвп) в межах квартири', 'PIPE_WATER')
  ) AS v(name_key, code)
  JOIN material m ON m.code = v.code;

-- -------------------------------------------------------------------------------------------------
-- 2. REINFORCEMENT — the reinforced concrete volumes (and the slab's pre-V162 key).
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'BUILDER', v.name_key, 'M3', m.id, 1, 'REINFORCEMENT', 2
  FROM (VALUES
    ('облаштування фундаменту (приймання бетону арматура)'),
    ('влаштування фундаментної плити (армування, бетонування)'),
    ('монолітні стіни'),
    ('влаштування монолітного перекриття (армування, бетонування)'),
    ('монолітне перекриття приймання бетону армування'),
    ('виготовлення монолітних колон'),
    ('буронабивні палі буріння монтаж каркасу заливка')
  ) AS v(name_key)
  JOIN material m ON m.code = 'REBAR';

-- -------------------------------------------------------------------------------------------------
-- 3. CHASE_SECTION — the electrical chase filling, under its V163 name and its old one.
-- -------------------------------------------------------------------------------------------------
UPDATE material_norm n
   SET basis = 'CHASE_SECTION', qty_per_unit = 0.1, default_param = 4
  FROM material m
 WHERE n.owner_id IS NULL AND n.material_id = m.id AND m.code = 'GYPSUM_BUILDING'
   AND n.unit = 'LINEAR_METER' AND n.basis = 'QUANTITY' AND n.qty_per_unit = 0.4
   AND n.name_key IN ('заробка штроб', 'закладення штроб гіпсовою сумішшю');

-- -------------------------------------------------------------------------------------------------
-- 4. Self-checks, each about THIS migration's own work (review B-50).
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE v_bad int;
BEGIN
    SELECT count(*) INTO v_bad FROM material_norm WHERE owner_id IS NULL AND basis = 'LENGTH';
    IF v_bad <> 9 THEN
        RAISE EXCEPTION 'V168: expected 9 riser LENGTH norms, found %', v_bad;
    END IF;
    SELECT count(*) INTO v_bad FROM material_norm WHERE owner_id IS NULL AND basis = 'REINFORCEMENT';
    IF v_bad <> 7 THEN
        RAISE EXCEPTION 'V168: expected 7 REINFORCEMENT norms, found %', v_bad;
    END IF;
    SELECT count(*) INTO v_bad FROM material_norm WHERE owner_id IS NULL AND basis = 'CHASE_SECTION';
    IF v_bad <> 2 THEN
        RAISE EXCEPTION 'V168: expected the chase filling on CHASE_SECTION under both names, found %', v_bad;
    END IF;
    -- A suggestion outside its own bound is refused the moment it is pre-filled (V151's lesson).
    SELECT count(*) INTO v_bad FROM material_norm WHERE owner_id IS NULL
       AND ((basis = 'LENGTH' AND default_param > 100) OR (basis = 'CHASE_SECTION' AND default_param > 200)
            OR (basis = 'REINFORCEMENT' AND default_param IS NOT NULL));
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'V168: % suggestion(s) outside their question''s rule', v_bad;
    END IF;
END $$;
