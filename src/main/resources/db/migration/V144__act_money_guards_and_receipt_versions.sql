-- Review round 3, §4 — the money should-fixes that need schema (B-80, B-82).
--
-- B-80: ProjectReceipt and WorkActReceipt had no optimistic lock. A PATCH that read the row before
-- a concurrent sign committed wrote the whole entity back and reset `billed_on_act_id` to NULL —
-- the V134 stamp that keeps one paper from being billed twice. Same shape as B-43's merge race, one
-- table over. `@Version` makes the loser a 409 instead of a silent overwrite.
--
-- B-82: two open FINAL acts on one object, and two acts both claiming the same ADDENDUM estimate,
-- were accepted by the database — WorkActService refuses both in Java, and a partial unique index
-- is what makes the refusal true for a concurrent pair as well. Reported first, then enforced: the
-- DO blocks below RAISE EXCEPTION on pre-existing duplicates rather than silently dropping rows,
-- because deciding which of two signed acts is the real one is not a migration's call.

ALTER TABLE project_receipt  ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE work_act_receipt ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN project_receipt.version IS
    'Optimistic lock (review B-80): a PATCH built on a stale read must not undo billed_on_act_id.';
COMMENT ON COLUMN work_act_receipt.version IS
    'Optimistic lock (review B-80): same rule as project_receipt.version.';

-- ---------------------------------------------------------------------------
-- One OPEN act per object — the rule WorkActService.create already enforces.
DO $$
DECLARE offenders INT;
BEGIN
    SELECT count(*) INTO offenders FROM (
        SELECT project_id FROM work_act
        WHERE status IN ('DRAFT', 'SENT')
        GROUP BY project_id HAVING count(*) > 1
    ) d;
    IF offenders > 0 THEN
        RAISE EXCEPTION 'V144: % object(s) already hold more than one open act; resolve by hand first', offenders;
    END IF;
END $$;

CREATE UNIQUE INDEX ux_work_act_one_open_per_project
    ON work_act (project_id)
    WHERE status IN ('DRAFT', 'SENT');

-- ---------------------------------------------------------------------------
-- One SIGNED FINAL act per object — ActFinalGuard's rule (B-62).
DO $$
DECLARE offenders INT;
BEGIN
    SELECT count(*) INTO offenders FROM (
        SELECT project_id FROM work_act
        WHERE status = 'SIGNED' AND kind = 'FINAL'
        GROUP BY project_id HAVING count(*) > 1
    ) d;
    IF offenders > 0 THEN
        RAISE EXCEPTION 'V144: % object(s) already hold several signed FINAL acts; resolve by hand first', offenders;
    END IF;
END $$;

CREATE UNIQUE INDEX ux_work_act_one_signed_final_per_project
    ON work_act (project_id)
    WHERE status = 'SIGNED' AND kind = 'FINAL';

-- ---------------------------------------------------------------------------
-- An ADDENDUM estimate belongs to exactly one act (ActAddendumCreator writes it once).
DO $$
DECLARE offenders INT;
BEGIN
    SELECT count(*) INTO offenders FROM (
        SELECT addendum_estimate_id FROM work_act
        WHERE addendum_estimate_id IS NOT NULL
        GROUP BY addendum_estimate_id HAVING count(*) > 1
    ) d;
    IF offenders > 0 THEN
        RAISE EXCEPTION 'V144: % addendum estimate(s) are claimed by several acts; resolve by hand first', offenders;
    END IF;
END $$;

CREATE UNIQUE INDEX ux_work_act_addendum_estimate
    ON work_act (addendum_estimate_id)
    WHERE addendum_estimate_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- The cash union's two missing composite indexes (review B-52). «Мої гроші» is the first screen
-- whose queries are owner-wide rather than object-wide, and both of these are a per-period scan
-- joined through `projects.owner_id`. Harmless at today's volume; cheap to have before it is not.
CREATE INDEX IF NOT EXISTS ix_object_expenses_object_spent
    ON object_expenses (object_id, spent_at);
CREATE INDEX IF NOT EXISTS ix_payment_receipt_project_received
    ON payment_receipt (project_id, received_at);

-- ---------------------------------------------------------------------------
-- A NOTE FOR A FUTURE RE-RUN OF V140 (review B-42). V140 re-filed DRAFT estimate lines under the
-- trade the majority of their lines carried, and its predicate was `own * 2 >= total` — so a 5/5
-- split counted as a majority and went to whichever trade `DISTINCT ON` happened to rank first,
-- against the rule its own header stated («no clear majority → no opinion»). The owner's ruling
-- (2026-10-01) is to LEAVE the ties as applied: V140 recorded nothing about which rows it touched,
-- so an undo would also move the ones it guessed right. The predicate for any re-run is
-- `own * 2 > total`.

-- ---------------------------------------------------------------------------
-- A CORRECTION TO V136's HEADER (review B-53), recorded here because V136 is applied and Flyway
-- checksums it: its «WHAT THIS DELIBERATELY DOES NOT DO» paragraph justifies not unwinding a
-- mis-reconciled receipt by saying a signed act's `doc_hash` must keep verifying. That reason is
-- wrong — neither `billed_on_act_id` nor the object's expense row is inside the hashed render (see
-- WorkActPdfService.PdfModel, and B-76 on why the hash cannot be re-verified at all). The RULE it
-- states is right for a different reason: a signed act is a document the client holds, the money it
-- carries has already moved into «За договором» through its ADDENDUM, and a migration is not the
-- place to decide which of two receipts was the real one. Report, do not revert.

-- ---------------------------------------------------------------------------
-- A norm says which parameter value its own coefficient assumed (review B-49).
--
-- A master's habit rescales a shipped figure as a ratio, and the denominator was one constant per
-- MATERIAL: every TILE_GROUT row was assumed to be written for a 2,5 mm joint. V137 wrote one row
-- that is not — «затирання швів від 3 мм цементною сумішшю», 0,8 kg/m2 — so a master whose habit is
-- 5 mm had that already-wide figure doubled, and bought twice the grout for the one position whose
-- data was the most specific we have.
--
-- NULL = «the product-wide default for my habit», which is what every other row means. This is NOT
-- `default_param`: that is a SUGGESTION the master confirms, this is a statement about the
-- coefficient beside it.
ALTER TABLE material_norm ADD COLUMN baseline_param NUMERIC(15, 4);

COMMENT ON COLUMN material_norm.baseline_param IS
    'The parameter value this coefficient was written against, when not the product default '
    '(review B-49). NULL = the default for the material''s own scaling habit.';

UPDATE material_norm
   SET baseline_param = 3
 WHERE owner_id IS NULL
   AND name_key = 'затирання швів від 3 мм цементною сумішшю'
   AND unit = 'M2';

DO $$
DECLARE marked INT;
BEGIN
    SELECT count(*) INTO marked FROM material_norm WHERE baseline_param IS NOT NULL;
    IF marked <> 1 THEN
        RAISE WARNING 'V144: expected exactly one norm with its own baseline joint, found %', marked;
    END IF;
END $$;
