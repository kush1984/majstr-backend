-- V117. DRYWALL: joint sanding becomes its own position.
--
-- V116 folded «шліфування стику» into the description of «Заповнення та армування стиків ГКЛ»,
-- on the grounds that a joint is filled and sanded in one pass over the same linear metre. The
-- master's own technology matrix disagrees, and it is his document that decides: stage 1.2
-- «Шліфування стиків ГКЛ» is listed separately, and it is present in Q3+ and Q4 and ABSENT in Q3 —
-- which makes it a step that is genuinely sold or not sold, not a detail of another step. A master
-- composing the chain by hand (rather than buying a Q level) has to be able to price it.
--
-- Shipped as a separate migration rather than an edit to V116 for the ordinary reason: V116 has
-- already been applied to a live database, and Flyway checksums it.
--
-- Catalog version: this deliberately reuses V116's version 14 rather than opening 15. Both
-- migrations reach production in the same deploy, so a master sees ONE catalog update, and the
-- propagation below is explicit anyway (masters stamped by V116 are already at 14 and would not be
-- reached by a version-driven refresh).
--
-- Price: 40 ₴/м.п. — an ORIENTIR derived like every other number in V116, not a quote. The full
-- joint operation (fill + tape + sand) is the canon 100 ₴/м.п.; sanding is roughly the closing
-- 40 % of that pass. Cross-check: «Мікрошліфування дефектів» is 60 ₴/м², and a joint strip is well
-- under a square metre per linear metre. To be settled by `price_insight_candidate` (V94).

DO $$
DECLARE
    v_version   int;
    v_added     int := 0;
    v_bundle_id uuid;
BEGIN
    v_version := (SELECT MAX(added_in_version) FROM catalog_templates WHERE trade = 'DRYWALL');

    -- ------------------------------------------------------------------------------------------
    -- 1. The position.
    -- ------------------------------------------------------------------------------------------
    INSERT INTO catalog_templates (id, trade, category, name, type, unit,
                                   suggested_price, added_in_version, description)
    VALUES (gen_random_uuid(), 'DRYWALL', 'Оздоблення під фарбування',
            'Шліфування стиків ГКЛ', 'WORK', 'LINEAR_METER', 40.00, v_version,
            'Шліфування заповненого стику до площини аркуша. Рівні Q3+ і Q4 виконують його ' ||
            'окремим етапом; Q3 — ні.');

    -- The sanding is no longer part of the neighbouring position, so its description must stop
    -- claiming it — otherwise the two rows bill the same work twice on one estimate.
    UPDATE catalog_templates
    SET description = 'Заповнення стику шпаклівкою з армувальною стрічкою (серпянка).'
    WHERE trade = 'DRYWALL' AND name = 'Заповнення та армування стиків ГКЛ';

    UPDATE catalog_items
    SET description = 'Заповнення стику шпаклівкою з армувальною стрічкою (серпянка).'
    WHERE trade = 'DRYWALL' AND source = 'LIBRARY'
      AND lower(trim(name)) = lower(trim('Заповнення та армування стиків ГКЛ'));

    -- ------------------------------------------------------------------------------------------
    -- 2. Into the finishing sequence, in the order the work is done: right after the two joint
    --    variants (серпянка / паперова стрічка), before the planes are primed.
    -- ------------------------------------------------------------------------------------------
    SELECT id INTO v_bundle_id
    FROM estimate_templates
    WHERE is_default AND trade = 'DRYWALL' AND name = 'Підготовка ГКЛ під фарбування';

    IF v_bundle_id IS NULL THEN
        RAISE EXCEPTION 'V117: the finishing bundle V116 created is gone';
    END IF;

    UPDATE estimate_template_items
    SET sort_order = sort_order + 1
    WHERE template_id = v_bundle_id AND sort_order >= 3;

    INSERT INTO estimate_template_items (id, template_id, name, type, unit, sort_order)
    VALUES (gen_random_uuid(), v_bundle_id,
            'Шліфування стиків ГКЛ', 'WORK', 'LINEAR_METER', 3);

    -- ------------------------------------------------------------------------------------------
    -- 3. The masters who already have the trade. Same dedup key as
    --    ux_catalog_items_owner_name_type_unit: an existing row of any source or price wins.
    -- ------------------------------------------------------------------------------------------
    CREATE TEMP TABLE _added ON COMMIT DROP AS
    SELECT gen_random_uuid() AS id, t.user_id AS owner_id
    FROM (SELECT DISTINCT user_id FROM user_trades WHERE trade = 'DRYWALL') t
    WHERE NOT EXISTS (
        SELECT 1 FROM catalog_items ci
        WHERE ci.owner_id = t.user_id
          AND lower(trim(ci.name)) = lower(trim('Шліфування стиків ГКЛ'))
          AND ci.type = 'WORK' AND ci.unit = 'LINEAR_METER');

    INSERT INTO catalog_items (id, owner_id, name, type, unit, default_price,
                               category, trade, source, description)
    SELECT id, owner_id, 'Шліфування стиків ГКЛ', 'WORK', 'LINEAR_METER', 40.00,
           'Оздоблення під фарбування', 'DRYWALL', 'LIBRARY',
           'Шліфування заповненого стику до площини аркуша. Рівні Q3+ і Q4 виконують його ' ||
           'окремим етапом; Q3 — ні.'
    FROM _added;
    GET DIAGNOSTICS v_added = ROW_COUNT;

    -- One deploy, one notice. V116 queued a COUNT notice for exactly these masters minutes ago and
    -- nobody has seen it yet, so this position belongs INSIDE that count — a second «+1» row would
    -- read as a second catalog update that never happened. Only an undismissed notice is topped up;
    -- if there is none (a master who registered between the two migrations, or a re-run), a fresh
    -- one is queued instead.
    UPDATE catalog_update_notices n
    SET positions_added = n.positions_added + 1
    FROM _added a
    WHERE n.user_id = a.owner_id AND n.kind = 'COUNT' AND n.dismissed_at IS NULL;

    INSERT INTO catalog_update_notices (id, user_id, kind, positions_added, positions_removed)
    SELECT gen_random_uuid(), a.owner_id, 'COUNT', 1, 0
    FROM _added a
    WHERE NOT EXISTS (SELECT 1 FROM catalog_update_notices n
                      WHERE n.user_id = a.owner_id AND n.kind = 'COUNT' AND n.dismissed_at IS NULL);

    -- ------------------------------------------------------------------------------------------
    -- 4. Self-checks.
    -- ------------------------------------------------------------------------------------------
    IF NOT EXISTS (SELECT 1 FROM estimate_template_items i
                   JOIN catalog_templates ct ON ct.trade = 'DRYWALL' AND ct.name = i.name
                                            AND ct.type = i.type AND ct.unit = i.unit
                   WHERE i.template_id = v_bundle_id AND i.name = 'Шліфування стиків ГКЛ') THEN
        RAISE EXCEPTION 'V117: the bundle line does not resolve to a catalog position (0 UAH on apply)';
    END IF;

    IF (SELECT count(DISTINCT sort_order) FROM estimate_template_items WHERE template_id = v_bundle_id)
       <> (SELECT count(*) FROM estimate_template_items WHERE template_id = v_bundle_id) THEN
        RAISE EXCEPTION 'V117: two lines of the finishing bundle share a sort_order';
    END IF;

    RAISE NOTICE 'V117 drywall: +1 position (%), % master catalog(s) refreshed',
        (SELECT count(*) FROM catalog_templates WHERE trade = 'DRYWALL'), v_added;
END $$;
