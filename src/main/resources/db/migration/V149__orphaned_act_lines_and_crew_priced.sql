-- =================================================================================================
-- V149 — review round 4: the V141 backfill fixed forward (B-92), and «this sheet carries the crew's
-- prices» stated rather than inferred from a sign (B-105)
--
-- PART 1 — B-92. V141 classified every act line by the rule the code used until then: «no estimate
-- item = an additional work». An ESTIMATE line whose position was deleted later (the estimate kept
-- and uncounted, or deleted too — both FKs are ON DELETE SET NULL) also has no item, so it became
-- ADDITIONAL — and an ADDITIONAL line counts in «Прийнято актами» UNCONDITIONALLY, while the
-- estimate it closed counts in «За договором» nowhere. Probe: accepted moved 0 → 3 000 against a
-- contract of 0.
--
-- Two facts tell the two apart, and this migration uses only those:
--   * an ADDITIONAL line never carries an `estimate_id` (WorkActService writes it from the linked
--     item and from nothing else), so one that does was an estimate line;
--   * a SIGNED act's additional works were rolled into ITS OWN ADDENDUM estimate by name
--     (ActAddendumCreator), so an «additional» line of a signed act whose ADDENDUM holds no matching
--     line never was one. An act with no ADDENDUM at all says nothing either way and is left alone.
-- Only `line_kind` moves (owner ruling, 2026-10-09): no amount, no quantity, no signature — what
-- the client signed is untouched; the classification the economy reads it by is corrected.
--
-- PART 2 — B-105. The crew-margin panel was gated on `markup_percent > 0`, which is the SIGN of the
-- last step and not what the sheet carries: B (+20 %) → C (−5 %) inherits the crew's prices yet
-- showed nothing, and A → D (−10 %) → E (+20 %) read D's inherited «crew» prices (A's client prices)
-- as E's crew. `crew_priced` records it: a markup copy, or any copy of a crew-priced sheet.
-- Backfilled along `duplicated_from_id`; a chain whose link was deleted (ON DELETE SET NULL) falls
-- back to the sign, which is what the panel read until now — nothing that showed stops showing.
--
-- Self-checks RAISE EXCEPTION only about this migration's own work (review B-50).
-- =================================================================================================

-- -------------------------------------------------------------------------------------------------
-- PART 1
-- -------------------------------------------------------------------------------------------------
CREATE TEMP TABLE _v149_orphans ON COMMIT DROP AS
SELECT wai.id
  FROM work_act_item wai
  JOIN work_act wa ON wa.id = wai.work_act_id
 WHERE wai.line_kind = 'ADDITIONAL'
   AND (wai.estimate_id IS NOT NULL
        OR (wa.status = 'SIGNED'
            AND wa.addendum_estimate_id IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM estimate_items ei
                             WHERE ei.estimate_id = wa.addendum_estimate_id
                               AND lower(trim(ei.name)) = lower(trim(wai.name)))));

UPDATE work_act_item wai
   SET line_kind = 'ESTIMATE'
  FROM _v149_orphans o
 WHERE o.id = wai.id;

DO $$
DECLARE
    v_moved int := (SELECT count(*) FROM _v149_orphans);
    v_left  int;
BEGIN
    SELECT count(*) INTO v_left
      FROM work_act_item wai JOIN _v149_orphans o ON o.id = wai.id
     WHERE wai.line_kind <> 'ESTIMATE';
    IF v_left <> 0 THEN
        RAISE EXCEPTION 'V149: % re-classified line(s) are not ESTIMATE', v_left;
    END IF;
    SELECT count(*) INTO v_left
      FROM work_act_item WHERE line_kind = 'ADDITIONAL' AND estimate_id IS NOT NULL;
    IF v_left <> 0 THEN
        RAISE EXCEPTION 'V149: % additional line(s) still carry an estimate', v_left;
    END IF;
    RAISE NOTICE 'V149: % orphaned act line(s) re-classified ADDITIONAL -> ESTIMATE', v_moved;
END $$;

-- -------------------------------------------------------------------------------------------------
-- PART 2
-- -------------------------------------------------------------------------------------------------
ALTER TABLE estimates ADD COLUMN crew_priced boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN estimates.crew_priced IS
    'Its lines carry the crew''s prices in source_unit_price: a markup copy, or any copy of a crew-priced sheet (V149, review B-105). Gates the crew-margin panel and whether a further copy inherits those prices.';

WITH RECURSIVE priced(id) AS (
    SELECT id FROM estimates WHERE markup_percent > 0
    UNION
    SELECT e.id FROM estimates e JOIN priced p ON e.duplicated_from_id = p.id
)
UPDATE estimates e SET crew_priced = true
  FROM priced p
 WHERE p.id = e.id;

DO $$
DECLARE v_missed int;
BEGIN
    SELECT count(*) INTO v_missed FROM estimates WHERE markup_percent > 0 AND NOT crew_priced;
    IF v_missed <> 0 THEN
        RAISE EXCEPTION 'V149: % markup copies are not crew-priced', v_missed;
    END IF;
END $$;

-- -------------------------------------------------------------------------------------------------
-- PART 3 — review B-103, owner decision 2026-10-09. The ECONOMY portal no longer has a picker: it
-- shows every SIGNED and counted estimate of the object. A signed estimate is one the client has
-- already read — online, or on paper the master handed him — so there was nothing to hide, and a
-- forgotten tick showed him «Залишок 0» while part of the deal was still unpaid. Nothing reads
-- `economy_visible` any more; «counted» is the one switch for «this is the deal».
-- -------------------------------------------------------------------------------------------------
ALTER TABLE estimates DROP COLUMN economy_visible;
