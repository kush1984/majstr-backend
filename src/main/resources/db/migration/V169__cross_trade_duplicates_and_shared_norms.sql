-- =================================================================================================
-- V169 — the trade-by-trade review, last two leftovers: one job, one wording ACROSS trades, and every
-- norm of a position two trades ship answers for both.
--
-- Source: the cross-trade rows of `C:\Work\prompts\trade-review\` 01 (DRYWALL ↔ PAINTER sound
-- insulation), 03/05 (clinker, stone, quartz primer), 08 (debris bags), 09 (METAL ↔ BUILDER
-- profiled sheet), plus a trigram sweep of the V168 library for the same job under two wordings in
-- two trades (two-colour marking, the facade insulation «комплекс», the old switchboard). Owner
-- (2026-10-14): «беремо 1 і 6» — the cross-trade pairs V167 left, and the shared-norm re-filing.
--
-- 1. CROSS-TRADE PAIRS (V116's shape, V167's guarantees). V167 could only DELETE a losing wording
--    because its survivor lived in the same trade. Here the survivor usually lives in ANOTHER trade,
--    so deleting the loser would take the job out of the loser trade's library altogether. Per
--    library row of the loser:
--      * its trade already ships the survivor → the row is deleted (V167);
--      * otherwise the row is RENAMED to the survivor in place — its own price, category and order
--        kept, the survivor's description taken — so both trades now ship one shared position.
--    Prices are NOT touched (the shared rows keep a price per trade, as «Гідроізоляція плівкова» and
--    the quartz primer already did). Everything a master owns follows V167 verbatim: a LIBRARY copy of
--    the loser is renamed to the survivor at HIS price when he does not own the survivor (the bundle
--    line moves, and a missing name prices at 0 ₴), his pending notice and his forked norms follow,
--    a master holding both keeps both, default bundle lines move (or drop where the bundle already
--    carries the survivor), a fork's line moves where his catalog carries the survivor. The loser's
--    norms stay on its key and the pair is recorded in `catalog_renamed_position`.
--
-- 2. SHARED NORMS (TRADES-REVIEW §1.2). A shared position lands in a master's catalog under ONE
--    trade (V118), and the calculator reads only that trade's norms or trade-less ones. So every norm
--    on a (name, unit) the library ships under two or more trades is re-filed to `trade = NULL`,
--    shipped rows and forks in the same statement (review B-35), never duplicated; a trade-bound row
--    whose trade-less twin already exists is dropped instead (B-107). Before this migration only the
--    three DRYWALL «consumes nothing» verdicts PAINTER shares were still trade-bound; the pairs of
--    part 1 add their survivors.
--
-- NOT DONE: pairs whose content differs (METAL posts vs BUILDER «wooden or metal post», the stair
-- frame per step vs BUILDER's straight/turning step, «Армування стиків ГКЛ» vs DRYWALL's two joint
-- rows, the bathroom fan in ELECTRICAL vs PLUMBING, «Винесення» vs «Винесення та вивезення»), and
-- pairs in different units («Підйом будматеріалів» PIECE vs «Підняття матеріалу» T).
--
-- No `catalog_templates` row is inserted, so V118's ranking is not re-run and no version is bumped.
-- Self-checks `RAISE EXCEPTION` only about THIS migration's own work (review B-50).
-- =================================================================================================

CREATE TEMP TABLE _dup (loser varchar(255), survivor varchar(255), unit varchar(20)) ON COMMIT DROP;

INSERT INTO _dup VALUES
    -- PAINTER ships DRYWALL's framed sound insulation under its own wording, with no norm.
    ('Каркасна звукоізоляція ГКЛ два слоя стелі',          'Каркасна звукоізоляція стелі з обшивкою ГКЛ у 2 шари', 'M2'),
    ('Каркасна звукоізоляція ГКЛ два слоя стін',           'Каркасна звукоізоляція стін з обшивкою ГКЛ у 2 шари',  'M2'),
    -- BUILDER + PAINTER: the short clinker row beside BUILDER's own facade one.
    ('Облицювання клінкерною плиткою',                     'Облицювання фасаду клінкерною плиткою',                'M2'),
    ('Облицювання фасаду натуральним штучним каменем',     'Облицювання фасаду натуральним або штучним каменем',   'M2'),
    -- BUILDER + PAINTER «Грунтовка поверхні…» beside PAINTER's «Грунтування…» (V157 priced it by that).
    ('Грунтовка поверхні кварцгрунтом',                    'Грунтування кварцгрунтом',                             'M2'),
    ('Розділення розмітка двох кольорів декор штукатурки', 'Розділення та розмітка двох кольорів декор штукатурки', 'LINEAR_METER'),
    ('Утеплення фасада комплекс пінопласт сітка декор штукатурка',
                                                           'Утеплення фасада. Комплекс (пінопласт, сітка, перетяжка, декор. штукатурка)', 'M2'),
    ('Утеплення фасада комплекс вата сітка декор штукатурка',
                                                           'Утеплення фасада. Комплекс (вата, сітка, перетяжка, декор. штукатурка)', 'M2'),
    -- METAL ↔ BUILDER: one job, the METAL row carries the norms.
    ('Монтаж профнастилу на паркан до 2м',                 'Монтаж профнастилу на паркан',                         'M2'),
    -- DEMOLITION ↔ ELECTRICAL.
    ('Демонтаж електрощита',                               'Демонтаж старого електрощита',                         'PIECE'),
    -- PAINTER's own second wording of the debris bags every trade ships (report 08).
    ('Збирання сміття в мішки після демонтажу',            'Фасування сміття в мішки',                             'PIECE');

-- -------------------------------------------------------------------------------------------------
-- 0. Preconditions: both wordings are where we think they are.
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE v_bad text;
BEGIN
    SELECT string_agg(d.loser || ' → ' || d.survivor, '; ') INTO v_bad FROM _dup d
     WHERE NOT EXISTS (SELECT 1 FROM catalog_templates ct WHERE ct.type = 'WORK' AND ct.unit = d.unit
                         AND lower(trim(ct.name)) = lower(trim(d.loser)))
        OR NOT EXISTS (SELECT 1 FROM catalog_templates ct WHERE ct.type = 'WORK' AND ct.unit = d.unit
                         AND lower(trim(ct.name)) = lower(trim(d.survivor)))
        OR lower(trim(d.loser)) = lower(trim(d.survivor));
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V169: a cross-trade pair is not in the library as expected: %', v_bad;
    END IF;
END $$;

CREATE TEMP TABLE _key ON COMMIT DROP AS
SELECT d.*,
       lower(btrim(replace(replace(regexp_replace(d.loser, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))) AS loser_key,
       lower(btrim(replace(replace(regexp_replace(d.survivor, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))) AS survivor_key
  FROM _dup d;

INSERT INTO catalog_renamed_position (old_name_key, new_name_key, unit, renamed_in)
SELECT loser_key, survivor_key, unit, 'V169' FROM _key
ON CONFLICT DO NOTHING;

-- -------------------------------------------------------------------------------------------------
-- 1. Norms: a survivor with no shipped norm of its own takes the loser's, trade-less (it is shared).
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, owner_id, trade, name_key, unit, material_id, qty_per_unit,
                           waste_percent, sort_order, basis, default_param, baseline_param)
SELECT DISTINCT ON (k.survivor_key, k.unit, n.material_id)
       gen_random_uuid(), NULL, NULL, k.survivor_key, n.unit, n.material_id, n.qty_per_unit,
       n.waste_percent, n.sort_order, n.basis, n.default_param, n.baseline_param
  FROM _key k
  JOIN material_norm n ON n.owner_id IS NULL AND n.name_key = k.loser_key AND n.unit = k.unit
 WHERE NOT EXISTS (SELECT 1 FROM material_norm x WHERE x.owner_id IS NULL
                     AND x.name_key = k.survivor_key AND x.unit = k.unit)
 ORDER BY k.survivor_key, k.unit, n.material_id, n.trade NULLS FIRST;

-- -------------------------------------------------------------------------------------------------
-- 2. A master who owns the loser but not the survivor: his copy becomes the survivor (price kept),
--    his pending notice and his forked norms follow.
-- -------------------------------------------------------------------------------------------------
CREATE TEMP TABLE _moved ON COMMIT DROP AS
SELECT ci.id, ci.owner_id, k.loser, k.survivor, k.loser_key, k.survivor_key, k.unit
  FROM _key k
  JOIN catalog_items ci
    ON ci.source = 'LIBRARY' AND ci.type = 'WORK' AND ci.unit = k.unit
   AND lower(trim(ci.name)) = lower(trim(k.loser))
 WHERE NOT EXISTS (SELECT 1 FROM catalog_items x WHERE x.owner_id = ci.owner_id AND x.type = 'WORK'
                     AND x.unit = k.unit AND lower(trim(x.name)) = lower(trim(k.survivor)));

UPDATE catalog_update_notices n
   SET position_name = m.survivor
  FROM _moved m
 WHERE n.user_id = m.owner_id AND n.kind = 'PRICE_DRIFT' AND n.dismissed_at IS NULL
   AND lower(trim(n.position_name)) = lower(trim(m.loser));

UPDATE catalog_items ci SET name = m.survivor FROM _moved m WHERE ci.id = m.id;

INSERT INTO material_norm (id, owner_id, trade, name_key, unit, material_id, qty_per_unit,
                           waste_percent, sort_order, basis, default_param, baseline_param)
SELECT DISTINCT ON (n.owner_id, m.survivor_key, n.unit, n.material_id)
       gen_random_uuid(), n.owner_id, NULL, m.survivor_key, n.unit, n.material_id, n.qty_per_unit,
       n.waste_percent, n.sort_order, n.basis, n.default_param, n.baseline_param
  FROM _moved m
  JOIN material_norm n ON n.owner_id = m.owner_id AND n.name_key = m.loser_key AND n.unit = m.unit
 WHERE NOT EXISTS (SELECT 1 FROM material_norm x WHERE x.owner_id = m.owner_id
                     AND x.name_key = m.survivor_key AND x.unit = m.unit)
 ORDER BY n.owner_id, m.survivor_key, n.unit, n.material_id, n.trade NULLS FIRST;

-- -------------------------------------------------------------------------------------------------
-- 3. Bundle lines: default bundles always; a master's fork where his catalog carries the survivor.
--    A bundle that already carries the survivor drops the loser's line instead.
-- -------------------------------------------------------------------------------------------------
DELETE FROM estimate_template_items i
 USING _key k, estimate_templates t
 WHERE t.id = i.template_id AND i.type = 'WORK' AND i.unit = k.unit
   AND lower(trim(i.name)) = lower(trim(k.loser))
   AND (t.owner_id IS NULL OR EXISTS (SELECT 1 FROM template_default_override o
                                        WHERE o.forked_template_id = t.id AND o.user_id = t.owner_id))
   AND EXISTS (SELECT 1 FROM estimate_template_items s
                WHERE s.template_id = i.template_id AND s.unit = k.unit
                  AND lower(trim(s.name)) = lower(trim(k.survivor)));

UPDATE estimate_template_items i
   SET name = k.survivor
  FROM _key k, estimate_templates t
 WHERE t.id = i.template_id AND t.owner_id IS NULL AND t.is_default
   AND i.type = 'WORK' AND i.unit = k.unit AND lower(trim(i.name)) = lower(trim(k.loser));

UPDATE estimate_template_items i
   SET name = k.survivor
  FROM _key k, estimate_templates t, template_default_override o
 WHERE t.id = i.template_id AND t.owner_id IS NOT NULL
   AND o.forked_template_id = t.id AND o.user_id = t.owner_id
   AND i.type = 'WORK' AND i.unit = k.unit AND lower(trim(i.name)) = lower(trim(k.loser))
   AND EXISTS (SELECT 1 FROM catalog_items ci WHERE ci.owner_id = t.owner_id AND ci.type = 'WORK'
                 AND ci.unit = k.unit AND lower(trim(ci.name)) = lower(trim(k.survivor)));

-- -------------------------------------------------------------------------------------------------
-- 4. The library: a trade that already ships the survivor loses the row; any other trade's row is
--    renamed to the survivor in place and takes its description.
-- -------------------------------------------------------------------------------------------------
DELETE FROM catalog_templates ct
 USING _key k
 WHERE ct.type = 'WORK' AND ct.unit = k.unit AND lower(trim(ct.name)) = lower(trim(k.loser))
   AND EXISTS (SELECT 1 FROM catalog_templates s WHERE s.trade = ct.trade AND s.type = 'WORK'
                 AND s.unit = k.unit AND lower(trim(s.name)) = lower(trim(k.survivor)));

UPDATE catalog_templates ct
   SET name = k.survivor,
       description = COALESCE((SELECT s.description FROM catalog_templates s
                                WHERE s.type = 'WORK' AND s.unit = k.unit
                                  AND lower(trim(s.name)) = lower(trim(k.survivor))
                                  AND s.description IS NOT NULL
                                ORDER BY s.trade LIMIT 1), ct.description)
  FROM _key k
 WHERE ct.type = 'WORK' AND ct.unit = k.unit AND lower(trim(ct.name)) = lower(trim(k.loser));

-- -------------------------------------------------------------------------------------------------
-- 5. Every norm on a position two or more trades ship is trade-less — shipped rows and forks alike.
-- -------------------------------------------------------------------------------------------------
CREATE TEMP TABLE _shared ON COMMIT DROP AS
SELECT DISTINCT lower(btrim(replace(replace(regexp_replace(ct.name, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))) AS name_key,
       ct.unit
  FROM catalog_templates ct
 WHERE ct.type = 'WORK'
 GROUP BY 1, 2
HAVING count(DISTINCT ct.trade) > 1;

-- A trade-less twin already answers (B-107): the trade-bound copy goes.
DELETE FROM material_norm n
 USING _shared s
 WHERE n.name_key = s.name_key AND n.unit = s.unit AND n.trade IS NOT NULL
   AND EXISTS (SELECT 1 FROM material_norm t
                WHERE t.owner_id IS NOT DISTINCT FROM n.owner_id AND t.trade IS NULL
                  AND t.name_key = n.name_key AND t.unit = n.unit
                  AND t.material_id IS NOT DISTINCT FROM n.material_id);

-- Two trades carrying the same material on one key: keep one, so the re-filing cannot collide.
DELETE FROM material_norm n
 USING _shared s
 WHERE n.name_key = s.name_key AND n.unit = s.unit AND n.trade IS NOT NULL
   AND EXISTS (SELECT 1 FROM material_norm t
                WHERE t.owner_id IS NOT DISTINCT FROM n.owner_id AND t.trade IS NOT NULL
                  AND t.name_key = n.name_key AND t.unit = n.unit
                  AND t.material_id IS NOT DISTINCT FROM n.material_id
                  AND t.trade < n.trade);

UPDATE material_norm n
   SET trade = NULL
  FROM _shared s
 WHERE n.name_key = s.name_key AND n.unit = s.unit AND n.trade IS NOT NULL;

-- -------------------------------------------------------------------------------------------------
-- 6. Self-checks, each about THIS migration's own work (review B-50).
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE v_bad text;
BEGIN
    SELECT string_agg(DISTINCT ct.trade || ' / ' || ct.name, '; ') INTO v_bad
      FROM catalog_templates ct JOIN _key k ON lower(trim(ct.name)) = lower(trim(k.loser)) AND ct.unit = k.unit
     WHERE ct.type = 'WORK';
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V169: the library still ships a merged wording: %', v_bad;
    END IF;

    SELECT string_agg(DISTINCT i.name, '; ') INTO v_bad
      FROM estimate_template_items i JOIN estimate_templates t ON t.id = i.template_id
      JOIN _key k ON lower(trim(i.name)) = lower(trim(k.loser)) AND i.unit = k.unit
     WHERE t.owner_id IS NULL AND t.is_default;
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V169: default bundle lines still on a merged wording: %', v_bad;
    END IF;

    SELECT string_agg(DISTINCT n.name_key, '; ') INTO v_bad
      FROM material_norm n JOIN _shared s ON s.name_key = n.name_key AND s.unit = n.unit
     WHERE n.trade IS NOT NULL;
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'V169: a shared position still carries a trade-bound norm: %', v_bad;
    END IF;
END $$;
