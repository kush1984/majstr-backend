-- Clean the default catalog templates of the one position that materializes as a
-- duplicate for a multi-trade master.
--
-- "Армування кладки сіткою арматурою" existed under BOTH trades with a DIFFERENT unit
-- (BUILDER: WORK/M2 @100, DRYWALL: WORK/LINEAR_METER @363). The per-user copy de-dups by
-- (name, type, unit), so the mismatched unit slipped through and a BUILDER+DRYWALL master
-- got the same line twice. Mesh reinforcement of masonry is a builder (мулярна) line — keep
-- the BUILDER M2 row, drop the DRYWALL variant. This is the ONLY template name across all
-- trades with a divergent unit/type (checked); "Щебінь гранітний фракція 5-20" (M3 vs T) is
-- left intact on purpose — crushed stone is legitimately ordered by volume OR by weight.
--
-- Templates are global reference data copied into a master's catalog by value; deleting one
-- here only changes what NEW copies get. Existing masters' own catalog items are untouched.

DELETE FROM catalog_templates
WHERE trade = 'DRYWALL'
  AND lower(trim(name)) = 'армування кладки сіткою арматурою';
