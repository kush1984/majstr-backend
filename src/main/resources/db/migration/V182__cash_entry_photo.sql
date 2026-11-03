-- «Мої гроші»: a photo of the paper on the master's OWN row (object-money iteration).
--
-- Object money already carries its papers (project_receipt, work_act_receipt). What no object knows
-- about — робочий одяг, a drill, fuel — had nowhere to keep its receipt. Nullable, no backfill: a
-- row without a photo is an ordinary row. Read only through GET /api/cash/{id}/photo (owner-scoped),
-- never through /api/files; deleting the row deletes the blob after the commit (B-25).

ALTER TABLE cash_entry ADD COLUMN storage_key VARCHAR(255);

COMMENT ON COLUMN cash_entry.storage_key IS
    'Storage key of the receipt photo on a personal cash row, or NULL. Owner-only read.';
