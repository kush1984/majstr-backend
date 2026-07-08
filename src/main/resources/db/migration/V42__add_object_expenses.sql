-- "Object economy" (PRO): a per-object expense journal. Real profit for an object
-- is the sum of its estimate totals (income) minus these expenses. Money is stored
-- as BigDecimal(15,2), the same as estimate prices and payments — no new format.
--
-- object_id references a project ("об'єкт" in the product). ON DELETE CASCADE so
-- deleting the object removes its expenses. category is a small closed set.

CREATE TABLE object_expenses (
    id          UUID PRIMARY KEY,
    object_id   UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    amount      NUMERIC(15, 2) NOT NULL CHECK (amount >= 0),
    category    VARCHAR(20) NOT NULL CHECK (category IN ('MATERIALS', 'LABOR', 'OTHER')),
    note        VARCHAR(500),
    spent_at    DATE NOT NULL DEFAULT current_date,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_object_expenses_object ON object_expenses(object_id);
