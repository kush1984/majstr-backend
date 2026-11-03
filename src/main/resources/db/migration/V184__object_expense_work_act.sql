-- The act an expense was posted for («Чек до акта № N: …», ActAddendumCreator). «Мої гроші» needs
-- it to show how much of that purchase the client's «на матеріал» money paid
-- (MaterialRefundSplit.toActs); the note was the only link, and the master can edit a note.
-- NULL for everything else. Numbered V184: V183 is reserved for the unmerged bot branch.

ALTER TABLE object_expenses
    ADD COLUMN work_act_id UUID REFERENCES work_act (id) ON DELETE SET NULL;

CREATE INDEX ix_object_expenses_work_act ON object_expenses (work_act_id) WHERE work_act_id IS NOT NULL;

-- Rows posted before this column: the note names the act, and an act number is unique per master,
-- so object + number finds exactly one act.
UPDATE object_expenses e
   SET work_act_id = wa.id
  FROM work_act wa
 WHERE e.source = 'RECEIPT'
   AND e.work_act_id IS NULL
   AND wa.project_id = e.object_id
   AND e.note LIKE 'Чек до акта № ' || wa.number || ': %';
