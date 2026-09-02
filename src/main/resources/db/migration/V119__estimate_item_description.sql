-- =================================================================================================
-- V119 — an estimate line can explain itself.
--
-- Master feedback on the three finishing levels V116 shipped:
--   «оце що ми додали Q3, Q4 — якщо таке попаде в кошторис, звідки клієнт має знати що це таке?
--    та і сам майстер може не знати, бо не всі вкурсі таких рівнів, треба тут і з поясненням,
--    а в порталі клієнта і на пдф — розшифрування тих позначень»
--
-- V116 already put the sentence on catalog_templates and catalog_items, so the master can read it
-- in his own library. It stops at the library: «Підготовка ГКЛ під фарбування · Q4 (еліт)» reaches
-- the client as a bare name in the portal and in the PDF, and «Q4» is a plasterer's word.
--
-- The line carries its OWN copy rather than joining the catalog on read, for the same reason every
-- other field on this table does (name, unit, price, category — «estimate lines are snapshots»):
-- the client signed THIS wording. Re-pricing a position, renaming it or deleting it from the
-- catalog must never change what a signed estimate says.
-- =================================================================================================
ALTER TABLE estimate_items ADD COLUMN description VARCHAR(500);

COMMENT ON COLUMN estimate_items.description IS
    'Snapshot of the catalog position''s explanation at the moment the line was added (V119). '
    'NULL for a line the master typed himself — most lines need no explaining.';
