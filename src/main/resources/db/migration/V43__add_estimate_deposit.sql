-- Deposit (завдаток) a client pays up front. Balance (залишок) = total − deposit
-- is computed server-side, never stored. Nullable = no deposit recorded.
-- Client-facing: shown on the estimate, the portal and the PDF.
ALTER TABLE estimates ADD COLUMN deposit_amount NUMERIC(15, 2);
