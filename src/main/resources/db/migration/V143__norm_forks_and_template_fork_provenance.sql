-- =================================================================================================
-- V143 — the masters' own coefficient forks V137 left behind (review B-35).
--
-- V137 re-filed ten shipped DRYWALL norms to `trade = NULL`, because a dust-off, a floor covered in
-- cardboard or a coat of primer is the same work whichever trade is doing it. The UPDATE was scoped
-- `owner_id IS NULL` — the shipped rows only — and that is where it went wrong, because V126 put
-- `owner_id` INSIDE `ux_material_norm` precisely so a master's fork sits BESIDE the default it hides.
-- A fork of one of those ten kept `trade = 'DRYWALL'` while its default moved to NULL, and from that
-- moment the pair stopped being a pair:
--
--   * `MaterialCalculatorService.preferOwn` matches on the natural key, trade included, so on a
--     DRYWALL line BOTH rows answered — the master's corrected primer AND the shipped one, i.e. the
--     primer was bought twice;
--   * on a PAINTER line only the trade-less default answered, so his correction was ignored on the
--     trade that now shares the position;
--   * `MaterialNormService.own()` could no longer find the fork FROM the default, so «restore
--     default» was a silent no-op and the next edit tried to INSERT a second fork.
--
-- Reproduced on Postgres 17 by seeding a fork before V137 (see the integration test named below).
--
-- The fix is the same UPDATE V137 should have run over the owned rows too. No conflict is possible:
-- the target key `(owner_id, NULL, name_key, unit, material_id)` can only already exist if a
-- trade-less fork of the same norm is already there, and nothing could have created one — V126
-- shipped no trade-less DRYWALL norms and `saveOwn` copies the trade off the row it forks.
--
-- Deliberately NOT a general repair of «every fork whose default moved»: there is no record of what a
-- default's trade used to be, so a rule broader than this list would be guesswork. Any future
-- migration that re-files a shipped norm must re-file the forks in the SAME statement — that is the
-- lesson, and it is written into the architecture index.
-- =================================================================================================

UPDATE material_norm
   SET trade = NULL
 WHERE owner_id IS NOT NULL
   AND trade = 'DRYWALL'
   AND name_key IN ('базове шпаклювання під скловолокно',
                    'герметизація швів стиків герметиком',
                    'грунтування',
                    'захист підлоги картоном',
                    'звукоізоляція стін мінеральною ватою',
                    'обезпилення поверхні',
                    'поклейка склополотна',
                    'шліфування під скловолокно/склохолст',
                    'шліфування стін/стель (фінішне)',
                    'шпаклювання фінішне (2–4 рази)');

-- Self-check: after the UPDATE no owned row may still claim DRYWALL for one of the ten names, or the
-- pairing this migration exists to restore is still broken for whoever it missed.
DO $$
DECLARE
    stranded integer;
BEGIN
    SELECT count(*) INTO stranded
      FROM material_norm
     WHERE owner_id IS NOT NULL
       AND trade = 'DRYWALL'
       AND name_key IN ('базове шпаклювання під скловолокно',
                        'герметизація швів стиків герметиком',
                        'грунтування',
                        'захист підлоги картоном',
                        'звукоізоляція стін мінеральною ватою',
                        'обезпилення поверхні',
                        'поклейка склополотна',
                        'шліфування під скловолокно/склохолст',
                        'шліфування стін/стель (фінішне)',
                        'шпаклювання фінішне (2–4 рази)');
    IF stranded > 0 THEN
        RAISE EXCEPTION 'V143: % owned norm fork(s) still filed under DRYWALL', stranded;
    END IF;
END $$;


-- =================================================================================================
-- Second half of V143 — a forked template position remembers the DEFAULT position it was copied from
-- (review B-34).
--
-- V113 forks a system default on first write and translates the request's item ids into the copy's —
-- but only for the request that DID the forking. The PWA's outbox replays every queued template op
-- addressing the DEFAULT's ids, so op 1 forked and landed, and ops 2..n found the fork already there,
-- got an EMPTY translation map, matched nothing, and were reported as SUCCESS. An offline batch of
-- «rename, drop position A, retype position B, reorder» kept only the rename, and the editor, which
-- re-seeds its baseline from the answer, showed the master a bundle that looked saved.
--
-- The translation therefore has to be reconstructible from the DATA, not only from the moment of the
-- copy. One nullable self-reference does it. ON DELETE SET NULL: a shipped position can be deleted by
-- a later catalog rebuild (V116/V121/V122 all do), and losing the pointer must not take the master's
-- copy of the position with it.
-- =================================================================================================
ALTER TABLE estimate_template_items
    ADD COLUMN forked_from_item_id uuid
        REFERENCES estimate_template_items (id) ON DELETE SET NULL;

-- Backfill for forks that already exist. Matched on (sort_order, lower(trim(name)), type, unit)
-- against the default the override row names — exactly the tuple `forkDefault` copies, and the only
-- one available after the fact. Deliberately conservative:
--   * only rows whose match is UNIQUE on both sides are filled; anything ambiguous stays NULL, and a
--     NULL simply means «translate by nothing», i.e. today's behaviour for that one row;
--   * a master who has since renamed or reordered his copy is ambiguous by definition and is skipped —
--     which is correct, because his device is no longer holding the default's ids for that row either.
WITH pairs AS (
    SELECT fi.id AS fork_item_id,
           di.id AS default_item_id
      FROM template_default_override o
      JOIN estimate_template_items fi ON fi.template_id = o.forked_template_id
      JOIN estimate_template_items di ON di.template_id = o.template_id
       AND di.sort_order = fi.sort_order
       AND lower(trim(di.name)) = lower(trim(fi.name))
       AND di.type = fi.type
       AND di.unit = fi.unit
     WHERE o.forked_template_id IS NOT NULL
), unambiguous AS (
    -- `min(uuid)` does not exist in Postgres, and a GROUP BY that has to pick one of several would be
    -- the wrong answer anyway: the whole point of HAVING count(*) = 1 is that there is nothing to pick.
    SELECT fork_item_id, max(default_item_id::text)::uuid AS default_item_id
      FROM pairs
     GROUP BY fork_item_id
    HAVING count(*) = 1
)
UPDATE estimate_template_items t
   SET forked_from_item_id = u.default_item_id
  FROM unambiguous u
 WHERE t.id = u.fork_item_id;

CREATE INDEX idx_estimate_template_items_forked_from
    ON estimate_template_items (forked_from_item_id)
 WHERE forked_from_item_id IS NOT NULL;

COMMENT ON COLUMN estimate_template_items.forked_from_item_id IS
    'The system-default position this row was copied from when the bundle was forked on write (V113). '
    'Lets a LATER request that still names the default''s item ids be translated too (review B-34). '
    'NULL for an ordinary own position, and for a backfilled fork whose match was ambiguous.';
