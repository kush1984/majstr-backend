-- =================================================================================================
-- V227 — a norm may say «no allowance» (calculator review 2026-11-27, items 1 and 2)
--
-- 1. waste_percent becomes NULLABLE, and NULL is what «use the master's allowance» is spelled as.
--    Until now 0 meant that, so a norm had no way to say «nothing is lost here»: the master's 10 %
--    landed on everything, and a whole product rounded up to whole products bought one too many.
--    Every existing 0 becomes NULL — shipped norms and masters' own forks alike, so nothing a master
--    sees changes by this step alone. From here on 0 is a statement: this material takes no
--    allowance.
--
-- 2. A 3D fence is counted from its SIDES, not its metres (owner, 2026-11-27: «помилка в безпечний
--    бік — це все одно помилка»). 0,4 panel and 0,4 post a metre plus the master's 10 % bought 3
--    panels for a 5 m run and 44 posts for 100 m. A panel (Betafence Nylofor 3D, 2500 mm) does not
--    bend round a corner, so each side takes ⌈side ÷ 2,5⌉ of them, its last one cut; and a run of N
--    panels stands on N + 1 posts, a corner post shared, a gate breaking the run. The master types
--    the sides once per position — «25 + 15 + 25; 10» — as a new question, NormBasis.SIDES:
--      * material_norm.qty_per_run — pieces bought once per continuous run (the end post);
--      * the module is the norm's baseline_param, 2,5 m;
--      * estimate_material_param.text_value holds the answer (a list, not a figure), value NULL.
--    Both norms take no allowance (0): nothing of a whole panel or a post is lost.
--
-- 3. Ready-mix concrete carried ДСТУ Б Д.2.2-6:2016's loss already in the coefficient (101,5 m³ per
--    100 m³ placed, V151) AND the master's 10 % on top — 11,65 % over, i.e. 2,3 m³ of a mixer on a
--    20 m³ slab. The loss moves to where it is visible: the coefficient is the volume placed (1 m³
--    per m³, 0,1 per м.п. of a 20×50 plinth, 0,001 per m² per mm — which the THICKNESS norms already
--    were, scale 4 having rounded their 0,001015 away), and the norm's own allowance is ДСТУ's 1,5 %.
--    A master who orders differently forks the norm, as with any other figure.
--
-- Library rows only (owner_id IS NULL) for 2 and 3: a master's own fork is his figure — a forked 3D
-- panel or post norm keeps counting by the metre, as he set it.
-- =================================================================================================

ALTER TABLE material_norm ALTER COLUMN waste_percent DROP NOT NULL;
ALTER TABLE material_norm ALTER COLUMN waste_percent DROP DEFAULT;

UPDATE material_norm SET waste_percent = NULL WHERE waste_percent = 0;

COMMENT ON COLUMN material_norm.waste_percent IS
    'The norm''s own allowance, overriding the master''s: NULL = use his (almost every row), 0 = this material takes none.';

-- 2. The fence's sides --------------------------------------------------------------------------
ALTER TABLE material_norm ADD COLUMN qty_per_run numeric(15, 4);
ALTER TABLE material_norm ADD CONSTRAINT material_norm_qty_per_run_check
    CHECK (qty_per_run IS NULL OR (basis = 'SIDES' AND qty_per_run > 0));
COMMENT ON COLUMN material_norm.qty_per_run IS
    'SIDES norms only: pieces bought once per continuous run of a fence (the end post).';

ALTER TABLE material_norm DROP CONSTRAINT material_norm_basis_check;
ALTER TABLE material_norm ADD CONSTRAINT material_norm_basis_check
    CHECK (basis IN ('QUANTITY', 'PERIMETER', 'SECTION', 'THICKNESS', 'STEP', 'LENGTH',
                     'REINFORCEMENT', 'CHASE_SECTION', 'SIDES'));

ALTER TABLE estimate_material_param ADD COLUMN text_value varchar(300);
ALTER TABLE estimate_material_param ALTER COLUMN value DROP NOT NULL;
ALTER TABLE estimate_material_param DROP CONSTRAINT estimate_material_param_basis;
ALTER TABLE estimate_material_param ADD CONSTRAINT estimate_material_param_basis
    CHECK (basis IN ('PERIMETER', 'SECTION', 'THICKNESS', 'STEP', 'LENGTH', 'REINFORCEMENT',
                     'CHASE_SECTION', 'SIDES'));
ALTER TABLE estimate_material_param ADD CONSTRAINT estimate_material_param_text
    CHECK ((basis = 'SIDES') = (text_value IS NOT NULL AND value IS NULL));
ALTER TABLE estimate_material_param ADD CONSTRAINT estimate_material_param_figure
    CHECK (basis = 'SIDES' OR value IS NOT NULL);
COMMENT ON COLUMN estimate_material_param.text_value IS
    'SIDES only: a fence''s sides as «25 + 15 + 25; 10» (runs split by «;»); value is NULL then.';

UPDATE material_norm n
   SET basis = 'SIDES', qty_per_unit = 1, baseline_param = 2.5, waste_percent = 0,
       qty_per_run = CASE m.code WHEN 'POST_3D' THEN 1 END
  FROM material m
 WHERE m.id = n.material_id AND m.code IN ('PANEL_3D', 'POST_3D') AND n.owner_id IS NULL;

UPDATE material_norm n
   SET qty_per_unit = CASE n.basis
                          WHEN 'THICKNESS' THEN 0.001
                          ELSE CASE n.unit WHEN 'M3' THEN 1.0 WHEN 'LINEAR_METER' THEN 0.1 END
                      END,
       waste_percent = 1.5
  FROM material m
 WHERE m.id = n.material_id AND m.code = 'CONCRETE_READY' AND n.owner_id IS NULL;

DO $$
DECLARE
    unexpected int;
    left_zero  int;
    fence      int;
BEGIN
    SELECT count(*) INTO unexpected
      FROM material_norm n JOIN material m ON m.id = n.material_id
     WHERE m.code = 'CONCRETE_READY' AND n.owner_id IS NULL
       AND (n.qty_per_unit IS NULL OR n.qty_per_unit NOT IN (1.0, 0.1, 0.001) OR n.waste_percent <> 1.5);
    IF unexpected > 0 THEN
        RAISE EXCEPTION 'V227: % ready-mix concrete norms were not rewritten to a placed volume + 1,5 %%', unexpected;
    END IF;

    SELECT count(*) INTO left_zero
      FROM material_norm n LEFT JOIN material m ON m.id = n.material_id
     WHERE n.waste_percent = 0
       AND (m.code IS NULL OR m.code NOT IN ('PANEL_3D', 'POST_3D') OR n.owner_id IS NOT NULL);
    IF left_zero > 0 THEN
        RAISE EXCEPTION 'V227: % norms still read 0 %% (no allowance) that only meant «the master''s»', left_zero;
    END IF;

    SELECT count(*) INTO fence
      FROM material_norm n JOIN material m ON m.id = n.material_id
     WHERE n.owner_id IS NULL AND m.code IN ('PANEL_3D', 'POST_3D')
       AND NOT (n.basis = 'SIDES' AND n.baseline_param = 2.5 AND n.waste_percent = 0
                AND (coalesce(n.qty_per_run, 0) = 1) = (m.code = 'POST_3D'));
    IF fence > 0 THEN
        RAISE EXCEPTION 'V227: % 3D fence norms were not moved onto the fence''s sides', fence;
    END IF;
END $$;
