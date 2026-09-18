-- =================================================================================================
-- V136 — a blank fiscal code is not an identity (review item B-21).
--
-- THE HOLE
--   `fiscal_fn`/`fiscal_id` arrive on a PATCH, and the DTOs took whatever the client sent. An empty
--   string is not null, so a receipt saved with `fiscal_fn = ''`, `fiscal_id = ''` was an IDENTIFIED
--   receipt as far as every reader was concerned: the `IS NOT NULL` lookups returned it, and both
--   readers keyed it as the single string `'|'`.
--
--   The read path turned that into a false «цей чек уже є» on every blank-identity receipt of the
--   object — annoying but harmless. The MONEY path did not: `ActReceiptReconciler` matches an act
--   receipt to an object receipt by that key alone and, when it matches, stamps `billed_on_act_id`
--   and (with `receipts_to_expenses` on) DELETES the object receipt's own-cost `object_expenses`
--   row. Two unrelated papers that both happened to be blank were therefore reconciled into each
--   other: one receipt left the reimbursable axis it belonged on, and a real cost disappeared from
--   `Прибуток`.
--
-- WHAT THIS DOES
--   Nulls every blank (or whitespace-only) code on both receipt tables, so the legacy rows read the
--   way the code now writes them. Both columns are nulled together: half an identity matches nothing
--   and is exactly as unidentified as none.
--
-- WHAT THIS DELIBERATELY DOES NOT DO
--   It does not unwind money. An object receipt already carrying `billed_on_act_id` was settled
--   against a SIGNED act, and a signed act is a historical fact whose `doc_hash` must keep verifying
--   — the same rule V134 followed when it refused to rescan signed acts. So a mis-reconciled row is
--   REPORTED, not reverted: the count is raised as a warning for the log, and the master's own
--   journal is where such a receipt gets its expense back. The expectation is zero rows — the
--   reconciler only shipped with V134, one release ago, and the fiscal QR path (the only thing that
--   ever produces a code) writes real codes.
-- =================================================================================================

DO $$
DECLARE
    blank_project int;
    blank_act     int;
    suspect       int;
BEGIN
    SELECT count(*) INTO blank_project
    FROM project_receipt
    WHERE (fiscal_fn IS NOT NULL AND btrim(fiscal_fn) = '')
       OR (fiscal_id IS NOT NULL AND btrim(fiscal_id) = '');

    SELECT count(*) INTO blank_act
    FROM work_act_receipt
    WHERE (fiscal_fn IS NOT NULL AND btrim(fiscal_fn) = '')
       OR (fiscal_id IS NOT NULL AND btrim(fiscal_id) = '');

    -- Object receipts whose money was moved on a blank key. Not fixed here — see the header.
    SELECT count(*) INTO suspect
    FROM project_receipt
    WHERE billed_on_act_id IS NOT NULL
      AND ((fiscal_fn IS NOT NULL AND btrim(fiscal_fn) = '')
        OR (fiscal_id IS NOT NULL AND btrim(fiscal_id) = ''));

    UPDATE project_receipt
    SET fiscal_fn = NULL, fiscal_id = NULL
    WHERE (fiscal_fn IS NOT NULL AND btrim(fiscal_fn) = '')
       OR (fiscal_id IS NOT NULL AND btrim(fiscal_id) = '');

    UPDATE work_act_receipt
    SET fiscal_fn = NULL, fiscal_id = NULL
    WHERE (fiscal_fn IS NOT NULL AND btrim(fiscal_fn) = '')
       OR (fiscal_id IS NOT NULL AND btrim(fiscal_id) = '');

    IF blank_project > 0 OR blank_act > 0 THEN
        RAISE WARNING 'V136: cleared blank fiscal identity on % object receipt(s) and % act receipt(s)',
            blank_project, blank_act;
    END IF;

    IF suspect > 0 THEN
        RAISE WARNING 'V136: % object receipt(s) carry billed_on_act_id and had a BLANK fiscal identity - review by hand, their expense may have been dropped against the wrong act',
            suspect;
    END IF;
END $$;
