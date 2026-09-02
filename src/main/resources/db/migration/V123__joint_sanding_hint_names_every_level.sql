-- V123. The joint-sanding hint names every level that sells it.
--
-- V122 PART 2 re-worded the two hints that still pointed at the retired Q3+ tier, and got one of
-- them half right: «Шліфування стиків ГКЛ» reads «Окремий етап у Q2 і Q4», but V121's Q1 bundle
-- («під плитку та панелі») lists the same stage right after the joint it sands. A master who
-- applies Q1 then reads a hint saying the stage belongs to two other levels — exactly the kind of
-- stale copy that makes him stop trusting the rest of the hints.
--
-- An UPDATE only: no position is added, so V118's ranking does not need re-running, no notice is
-- queued and the catalog version stays 15.

DO $$
DECLARE
    v_levels text;
BEGIN
    -- The levels that actually ship the stage, read off the bundles rather than typed again.
    SELECT string_agg(substring(et.name from 'Q[0-9+]+'), ', ' ORDER BY et.name)
    INTO v_levels
    FROM estimate_templates et
    JOIN estimate_template_items i ON i.template_id = et.id
    WHERE et.is_default AND et.trade = 'DRYWALL'
      AND et.name LIKE 'Підготовка ГКЛ · Q%'
      AND i.name = 'Шліфування стиків ГКЛ';

    IF v_levels IS DISTINCT FROM 'Q1, Q2, Q4' THEN
        RAISE EXCEPTION 'V123: joint sanding ships in %, the hint below assumes Q1, Q2, Q4', v_levels;
    END IF;

    UPDATE catalog_templates
    SET description = 'Шліфування заповненого стику до площини аркуша. Окремий етап у Q1, Q2 і Q4.'
    WHERE trade = 'DRYWALL' AND name = 'Шліфування стиків ГКЛ';

    -- description is not on CatalogItemRequest — a master cannot author or edit it — so pushing our
    -- wording onto his LIBRARY copy overwrites nothing of his (V121 PART 2 / V122 PART 2's rule).
    UPDATE catalog_items ci
    SET description = ct.description
    FROM catalog_templates ct
    WHERE ct.trade = 'DRYWALL' AND ct.name = 'Шліфування стиків ГКЛ'
      AND ci.trade = 'DRYWALL' AND ci.source = 'LIBRARY'
      AND lower(trim(ci.name)) = lower(trim(ct.name))
      AND ci.type = ct.type AND ci.unit = ct.unit
      AND ci.description IS DISTINCT FROM ct.description;
END $$;
