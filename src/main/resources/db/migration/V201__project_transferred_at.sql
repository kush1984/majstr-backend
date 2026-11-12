-- «Перенести в Majstr»: an object created by moving the master's existing estimates in. Such an
-- object is outside the FREE lifetime cap (owner's decision, 2026-11-12) — this column is what
-- tells it apart, and what bounds how many a master may bring in (TransferService).
ALTER TABLE projects ADD COLUMN transferred_at TIMESTAMPTZ;
