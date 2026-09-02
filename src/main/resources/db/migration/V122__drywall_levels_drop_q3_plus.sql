-- V122. The drywall finish ladder is Q1, Q2, Q3, Q4 — Q3+ is dropped.
--
-- ============ WHY ============================================================================
-- V121 shipped FIVE level bundles and said so out loud: «Q3+ is the one judgement call here» —
-- not in the industry table, but a middle tier the master's own matrix specifies and prices. He
-- has now answered that call himself: «в нас має бути Q1, Q2, Q3 and Q4, такого я Q3+ - не треба».
-- Five tiers to choose between on a phone is a decision the client cannot make either, and the two
-- premium tiers differed by one line.
--
-- ============ WHAT THIS TOUCHES ==============================================================
-- ONE default bundle row, and nothing else.
--
--   * No catalog position is retired. Every stage Q3+ named is also named by Q3 or Q4 (its own
--     line list is Q4's, with the joint made on mesh instead of high-density paper tape), so
--     nothing leaves any master's catalog and no COUNT notice is queued.
--   * No new position, so no version bump and no V118 ranking re-run — the same reasoning V121
--     spelled out. Catalog version stays 15.
--   * A master's FORK of the bundle (V113) survives as an ordinary template he owns. That is the
--     V113 rule, not an oversight: forking means he edited it, and the override row is the only
--     thing that made it "my copy of a default". His lines still name live catalog positions here
--     — unlike V116/V121, this migration retires none — so the fork keeps applying at real prices.
--     The ON DELETE CASCADE trap those two migrations had to work around therefore does not bite:
--     there is nothing to repair in a fork before the default goes.
--   * Two position HINTS name the retired tier, and a hint is read by the master in the app, so
--     they are re-worded here (PART 2). A hint pointing at a level the app no longer offers is
--     exactly the kind of stale copy that makes him stop trusting the rest of them.

DO $$
DECLARE
    v_bundle uuid;
    v_left   text;
BEGIN
    SELECT id INTO v_bundle
    FROM estimate_templates
    WHERE is_default AND trade = 'DRYWALL'
      AND name = 'Підготовка ГКЛ · Q3+ — під якісне освітлення (преміум)';

    IF v_bundle IS NULL THEN
        RAISE EXCEPTION 'V122: the Q3+ bundle V121 created is gone';
    END IF;

    DELETE FROM estimate_templates WHERE id = v_bundle;   -- items + override rows cascade

    -- Self-check: the ladder the master asked for, and nothing between its rungs.
    SELECT string_agg(substring(name from 'Q[0-9+]+'), ', ' ORDER BY name)
    INTO v_left
    FROM estimate_templates
    WHERE is_default AND trade = 'DRYWALL' AND name LIKE 'Підготовка ГКЛ · Q%';

    IF v_left IS DISTINCT FROM 'Q1, Q2, Q3, Q4' THEN
        RAISE EXCEPTION 'V122: the drywall level ladder reads %, expected Q1, Q2, Q3, Q4', v_left;
    END IF;

    -- ==========================================================================================
    -- PART 2. Two hints stop naming a tier that no longer exists.
    -- ==========================================================================================
    -- Both stages survive Q3+'s removal — they are Q4's lines too — so only the wording moves.
    UPDATE catalog_templates
    SET description = 'Вологе знепилення перед фарбуванням — знімає пил, який суха обробка лишає '
                   || 'в порах. Обов’язкове для Q4.'
    WHERE trade = 'DRYWALL' AND name = 'Вологе обезпилювання поверхні';

    UPDATE catalog_templates
    SET description = 'Шліфування заповненого стику до площини аркуша. Окремий етап у Q2 і Q4.'
    WHERE trade = 'DRYWALL' AND name = 'Шліфування стиків ГКЛ';

    -- description is not on CatalogItemRequest — a master cannot author or edit it — so pushing
    -- our new wording onto his LIBRARY copies overwrites nothing of his (V121 PART 2's rule).
    UPDATE catalog_items ci
    SET description = ct.description
    FROM catalog_templates ct
    WHERE ct.trade = 'DRYWALL'
      AND ci.trade = 'DRYWALL' AND ci.source = 'LIBRARY'
      AND lower(trim(ci.name)) = lower(trim(ct.name))
      AND ci.type = ct.type AND ci.unit = ct.unit
      AND ci.description IS DISTINCT FROM ct.description;

    IF EXISTS (SELECT 1 FROM catalog_templates
               WHERE trade = 'DRYWALL' AND description LIKE '%Q3+%') THEN
        RAISE EXCEPTION 'V122: a drywall hint still names Q3+';
    END IF;
END $$;
