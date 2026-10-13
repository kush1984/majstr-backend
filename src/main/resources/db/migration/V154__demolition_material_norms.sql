-- =================================================================================================
-- V154 — the trade-by-trade review, batch 7: DEMOLITION's material norms
--
-- Source: `C:\Work\prompts\trade-review\08-DEMOLITION.md` §3, re-read against the V153 library.
-- Demolition consumes almost nothing; what it produces is debris, and the bags for that debris are
-- already answered by «Фасування сміття в мішки» (V148, one bag per bag the master bills). So this
-- batch is mostly RECORDED VERDICTS, plus the dust curtain and the entrance-door protection.
--
-- OWNER RULINGS (2026-10-13), all as recommended:
--   1. Bags stay on «Фасування сміття в мішки» alone (the review's variant A). Bags per m² on the
--      demolition positions themselves (variant B) would count them twice, and every figure there
--      is a calculation from a thickness and a substrate no position name carries.
--   2. The dust curtain on a doorway takes 4 m² of film and 7 m of tape: a 0,9×2,1 opening plus a
--      30 cm overlap on three sides = 1,5×2,4 = 3,6 → 4 m²; its perimeter 2×2,4 + 1,5 = 6,3 → 7 m.
--   3. The fixture removals and the openings stay unnormed (below).
--
-- DELIBERATELY UNNORMED — they do consume something, but nothing in the name says how much:
-- removing a WC, washbasin, bath, shower, mixer, radiator, towel rail or boiler leaves pipe ends to
-- cap, and the caps depend on the pipes (V152 left fittings unnormed for the same reason); cutting or
-- widening an opening needs a lintel whose size is not in the name.
--
-- «Захист вхідних дверей картоном» is shipped by DEMOLITION and PAINTER, but V137 filed its norm under
-- PAINTER, where a demolition estimate never reaches it. Re-filed to `trade = NULL`, never duplicated,
-- with the forks in the same statement (review B-35) unless the owner already holds a trade-less twin
-- (B-107). The three verdicts on positions another trade ships too (old pipes — PLUMBING, old wiring
-- — ELECTRICAL, wallpaper and whitewash — PAINTER) are trade-less for the same reason.
--
-- Every norm carries `waste_percent = 0` (batch 2's ruling). No dictionary row and no
-- `catalog_templates` row is inserted: film, tape and cardboard are already in the dictionary.
--
-- Self-checks `RAISE EXCEPTION` only about THIS migration's own work (review B-50).
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- 1. The dust curtain (ruling 2). Only DEMOLITION ships it.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), 'DEMOLITION', v.name_key, v.unit, m.id, v.qty, 'QUANTITY', v.ord
FROM (VALUES
    ('пилозахисна завіса з плівки на дверний проріз', 'PIECE', 'PE_FILM',      4::numeric, 1),
    ('пилозахисна завіса з плівки на дверний проріз', 'PIECE', 'TAPE_MASKING', 7,          2)
) AS v(name_key, unit, code, qty, ord)
JOIN material m ON m.code = v.code;

-- -------------------------------------------------------------------------------------------------
-- 2. The entrance-door protection answers for every trade that ships it.
-- -------------------------------------------------------------------------------------------------
UPDATE material_norm n
   SET trade = NULL
 WHERE n.trade = 'PAINTER'
   AND n.name_key = 'захист вхідних дверей картоном' AND n.unit = 'PIECE'
   AND NOT EXISTS (SELECT 1 FROM material_norm o
                    WHERE o.owner_id IS NOT DISTINCT FROM n.owner_id
                      AND o.trade IS NULL
                      AND o.name_key = n.name_key AND o.unit = n.unit
                      AND o.material_id IS NOT DISTINCT FROM n.material_id);

-- -------------------------------------------------------------------------------------------------
-- 3. «Checked, consumes nothing» — taking things apart and carrying them out. The debris goes into
--    the bags «Фасування» already buys (ruling 1); a disc, a bit and a scraper are tool wear.
-- -------------------------------------------------------------------------------------------------
INSERT INTO material_norm (id, trade, name_key, unit, material_id, qty_per_unit, basis, sort_order)
SELECT gen_random_uuid(), v.trade, v.name_key, v.unit, NULL, NULL, 'QUANTITY', 0
FROM (VALUES
    -- ---- Shipped by another trade too, so trade-less ---------------------------------------------
    (NULL,         'демонтаж старої електропроводки',                       'LINEAR_METER'),
    (NULL,         'демонтаж старих труб',                                  'LINEAR_METER'),
    (NULL,         'змивання побілки зі стелі',                             'M2'),
    (NULL,         'зняття шпалер',                                         'M2'),
    -- ---- Electrics -------------------------------------------------------------------------------
    ('DEMOLITION', 'демонтаж витяжки / електроплити / варильної поверхні',  'PIECE'),
    ('DEMOLITION', 'демонтаж електрощита',                                  'PIECE'),
    ('DEMOLITION', 'демонтаж розеток вимикачів',                            'PIECE'),
    -- ---- Floors ----------------------------------------------------------------------------------
    ('DEMOLITION', 'демонтаж дерев''яної підлоги лаг',                      'M2'),
    ('DEMOLITION', 'демонтаж керамічної плитки з підлоги',                  'M2'),
    ('DEMOLITION', 'демонтаж ламінату паркету',                             'M2'),
    ('DEMOLITION', 'демонтаж плитки зі стін',                               'M2'),
    ('DEMOLITION', 'демонтаж стяжки бетонної',                              'M2'),
    -- ---- Plumbing that leaves no pipe end --------------------------------------------------------
    ('DEMOLITION', 'демонтаж екрана ванни з плиткою',                       'PIECE'),
    -- ---- Ceilings --------------------------------------------------------------------------------
    ('DEMOLITION', 'демонтаж натяжної стелі',                               'M2'),
    ('DEMOLITION', 'демонтаж стельового багета',                            'LINEAR_METER'),
    -- ---- Joinery and furniture -------------------------------------------------------------------
    ('DEMOLITION', 'демонтаж балконного блоку',                             'PIECE'),
    ('DEMOLITION', 'демонтаж вхідних дверей',                               'PIECE'),
    ('DEMOLITION', 'демонтаж вікна балконного блоку',                       'PIECE'),
    ('DEMOLITION', 'демонтаж засклення балкона',                            'LINEAR_METER'),
    ('DEMOLITION', 'демонтаж кухонних меблів',                              'SET'),
    ('DEMOLITION', 'демонтаж міжкімнатних дверей з коробкою',               'PIECE'),
    ('DEMOLITION', 'демонтаж наличників і дверних укосів',                  'PIECE'),
    ('DEMOLITION', 'демонтаж підвіконня',                                   'PIECE'),
    -- ---- Walls and holes -------------------------------------------------------------------------
    ('DEMOLITION', 'демонтаж перегородки з піноблоку газоблоку',            'M2'),
    ('DEMOLITION', 'демонтаж перегородки з цегли півцеглини',               'M2'),
    ('DEMOLITION', 'демонтаж пластикових панелей зі стін і стелі',          'M2'),
    ('DEMOLITION', 'демонтаж стінових панелей',                             'M2'),
    ('DEMOLITION', 'демонтаж цегляної стіни',                               'M2'),
    ('DEMOLITION', 'збивання штукатурки зі стін',                           'M2'),
    ('DEMOLITION', 'зняття старої фарби зі стін',                           'M2'),
    ('DEMOLITION', 'отвір в бетоні до 10 см',                               'M2'),
    -- ---- Debris and logistics --------------------------------------------------------------------
    ('DEMOLITION', 'вантаження сміття в авто',                              'M3'),
    ('DEMOLITION', 'вивезення сміття контейнером',                          'PIECE'),
    ('DEMOLITION', 'винесення будівельного сміття',                         'M3'),
    ('DEMOLITION', 'підйом будматеріалів на поверх',                        'PIECE')
) AS v(trade, name_key, unit);

-- -------------------------------------------------------------------------------------------------
-- 4. Self-checks, each about THIS migration's own work (review B-50).
-- -------------------------------------------------------------------------------------------------
DO $$
DECLARE
    orphans   text;
    ambiguous text;
    normed    int;
    left_over int;
BEGIN
    -- 4a. Every key this migration wrote names a live DEMOLITION WORK position (a typo here is mine).
    SELECT string_agg(DISTINCT n.name_key || ' [' || n.unit || ']', ', ')
      INTO orphans
      FROM material_norm n
     WHERE n.owner_id IS NULL
       AND (n.trade = 'DEMOLITION'
            OR (n.trade IS NULL AND n.name_key IN ('демонтаж старої електропроводки', 'демонтаж старих труб',
                                                   'змивання побілки зі стелі', 'зняття шпалер',
                                                   'захист вхідних дверей картоном')))
       AND NOT EXISTS (
            SELECT 1 FROM catalog_templates t
             WHERE t.trade = 'DEMOLITION' AND t.type = 'WORK' AND t.unit = n.unit
               AND lower(btrim(replace(replace(regexp_replace(t.name, '\s+', ' ', 'g'), '( ', '('), ' )', ')'))) = n.name_key
       );
    IF orphans IS NOT NULL THEN
        RAISE EXCEPTION 'V154: DEMOLITION norms name no live catalog position: %', orphans;
    END IF;

    -- 4b. Two trades norming one (name, unit) cannot be resolved.
    SELECT string_agg(name_key || ' [' || unit || ']', ', ' ORDER BY name_key)
      INTO ambiguous
      FROM (SELECT name_key, unit FROM material_norm
             WHERE owner_id IS NULL
             GROUP BY name_key, unit
            HAVING count(DISTINCT trade) > 1
                OR (count(DISTINCT trade) = 1 AND count(trade) <> count(*))) AS a;
    IF ambiguous IS NOT NULL THEN
        RAISE EXCEPTION 'V154: a name and unit two trades both norm cannot be resolved: %', ambiguous;
    END IF;

    -- 4c. The shipped door protection left PAINTER.
    SELECT count(*) INTO left_over FROM material_norm
     WHERE owner_id IS NULL AND trade = 'PAINTER' AND name_key = 'захист вхідних дверей картоном';
    IF left_over <> 0 THEN
        RAISE EXCEPTION 'V154: % shipped door-protection norm(s) still filed under PAINTER', left_over;
    END IF;

    -- 4d. Row count: the curtain + 31 verdicts filed under DEMOLITION, so a half-applied list cannot
    --     pass quietly. With the trade-less rows, 44 of 55 positions answer.
    SELECT count(DISTINCT name_key || '|' || unit) INTO normed
      FROM material_norm WHERE owner_id IS NULL AND trade = 'DEMOLITION';
    IF normed <> 32 THEN
        RAISE EXCEPTION 'V154: expected 32 normed DEMOLITION positions, found %', normed;
    END IF;
END $$;
