-- Economy split: distinguish receipt-logged expenses (real material cost, counted
-- against the deposit) from hand-entered expenses (unforeseen, subtracted from earnings).
-- Existing expenses were all entered by hand → MANUAL.

ALTER TABLE object_expenses
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'MANUAL';

ALTER TABLE object_expenses
    ADD CONSTRAINT object_expenses_source_check CHECK (source IN ('RECEIPT', 'MANUAL'));
