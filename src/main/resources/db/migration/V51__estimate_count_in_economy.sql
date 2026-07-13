-- Object economy rework: which estimates count toward the object's income.
--
-- The economy used to sum ALL of an object's estimates (minus REJECTED), which
-- double/triple-counts once you have variant estimates (econom/premium), a
-- consolidated estimate (its sources + the consolidated), or working drafts.
-- Instead, an estimate now carries an explicit "count in economy" flag: income =
-- the sum of FLAGGED estimates only (usually one — the accepted deal; multiple for
-- multi-phase objects).
--
-- Backfill: SIGNED estimates are the real deal → flagged true. Everything else stays
-- false (the master flags the accepted one; signing / consolidating auto-manage it).

ALTER TABLE estimates ADD COLUMN count_in_economy BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE estimates SET count_in_economy = TRUE WHERE status = 'SIGNED';
