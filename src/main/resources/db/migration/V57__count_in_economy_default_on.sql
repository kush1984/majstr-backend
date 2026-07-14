-- Economy counting is now default-ON: every estimate counts toward the object
-- economy unless the owner unchecks it (previously default-off, only auto-set on
-- sign). The one built-in exception is a consolidated estimate — created with
-- count_in_economy = false so it doesn't double-count its (counted) sources.
--
-- Flip the column default to TRUE, and backfill existing estimates so the object
-- economy is complete for current masters too. Blanket TRUE is deliberate: a
-- consolidated estimate has no persistent marker (its rollup items are copied, not
-- linked), so any pre-existing consolidated estimate is set counted here as well —
-- acceptable (consolidate is a new, rarely-used feature); a master unchecks that one
-- rollup by hand. New consolidations are correctly excluded by the service.

ALTER TABLE estimates ALTER COLUMN count_in_economy SET DEFAULT TRUE;

UPDATE estimates SET count_in_economy = TRUE WHERE count_in_economy = FALSE;
