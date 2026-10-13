-- =================================================================================================
-- V156 — the trade-by-trade review: METAL's bundles, the one the review still wanted
--
-- Source: `C:\Work\prompts\trade-review\09-METAL.md` §2, re-read against the V155 library. The report
-- was written on V145; V148 already rebuilt all seven METAL bundles in the order it asked for
-- (foundations → fabrication → cleaning → primer → paint → erection → cladding / hardware), so the
-- only thing left is its list of NEW bundles.
--
-- OWNER RULING (2026-10-13), as recommended: ship «Антресоль / майданчик» only. The 3D-mesh fence
-- (four lines) and the stainless railing (two) fall under V148's floor of six lines — a master types
-- them faster than he finds a bundle — and their positions already answer on the materials screen.
--
-- A new default bundle, so no master's fork or override is touched; every line is a live METAL
-- catalog position spelled character for character (a miss would price at 0 ₴ on apply).
--
-- Self-checks `RAISE EXCEPTION` only about THIS migration's own work (review B-50).
-- =================================================================================================

INSERT INTO estimate_templates (id, owner_id, name, trade, is_default, description, created_at, updated_at)
SELECT gen_random_uuid(), NULL, 'Антресоль / майданчик', 'METAL', TRUE,
       'Металева антресоль чи технічний майданчик: закладні деталі, виготовлення, очищення, ґрунт і фарба до монтажу, монтаж з анкеруванням опорних пластин.',
       now(), now()
 WHERE NOT EXISTS (SELECT 1 FROM estimate_templates x
                    WHERE x.owner_id IS NULL AND x.is_default
                      AND x.trade = 'METAL' AND x.name = 'Антресоль / майданчик');

INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
SELECT gen_random_uuid(), t.id, l.name, 'WORK', l.unit, l.pos
  FROM estimate_templates t
  JOIN (VALUES
    (0, 'Виготовлення закладних деталей',        'PIECE'),
    (1, 'Виготовлення антресолі та площадки',    'M2'),
    (2, 'Зачистка та знежирення металу',         'M2'),
    (3, 'Ґрунтування металу',                    'M2'),
    (4, 'Фарбування металу',                     'M2'),
    (5, 'Монтаж металоконструкцій',              'KG'),
    (6, 'Анкерування опорних пластин',           'PIECE')
  ) AS l(pos, name, unit) ON TRUE
 WHERE t.owner_id IS NULL AND t.is_default AND t.trade = 'METAL' AND t.name = 'Антресоль / майданчик'
   AND NOT EXISTS (SELECT 1 FROM estimate_template_items i
                    WHERE i.template_id = t.id
                      AND lower(trim(i.name)) = lower(trim(l.name)) AND i.unit = l.unit);

DO $$
DECLARE
    lines    int;
    unpriced text;
BEGIN
    SELECT count(*) INTO lines
      FROM estimate_template_items i JOIN estimate_templates t ON t.id = i.template_id
     WHERE t.owner_id IS NULL AND t.trade = 'METAL' AND t.name = 'Антресоль / майданчик';
    IF lines <> 7 THEN
        RAISE EXCEPTION 'V156: expected 7 lines in «Антресоль / майданчик», found %', lines;
    END IF;

    SELECT string_agg(i.name, ', ') INTO unpriced
      FROM estimate_template_items i JOIN estimate_templates t ON t.id = i.template_id
     WHERE t.owner_id IS NULL AND t.trade = 'METAL' AND t.name = 'Антресоль / майданчик'
       AND NOT EXISTS (SELECT 1 FROM catalog_templates c
                        WHERE c.trade = 'METAL' AND c.type = 'WORK' AND c.unit = i.unit
                          AND lower(trim(c.name)) = lower(trim(i.name)));
    IF unpriced IS NOT NULL THEN
        RAISE EXCEPTION 'V156: bundle lines name no METAL catalog position: %', unpriced;
    END IF;
END $$;
