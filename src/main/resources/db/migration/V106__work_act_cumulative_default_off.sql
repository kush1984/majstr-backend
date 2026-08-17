-- Acts fix — the «ДОВІДКОВО» (cumulative reference) block was wrong on the first act (its per-line
-- «виконано з початку» equalled the act's own quantities) and answered the wrong question. It is
-- being rewritten into three object-wide money rows and hidden on the first act. Default it OFF and
-- clear it on every existing act: the block in its old shape should never render, and the master
-- opts back in per act if he wants the new reference.
ALTER TABLE work_act ALTER COLUMN show_cumulative SET DEFAULT false;
UPDATE work_act SET show_cumulative = false;
