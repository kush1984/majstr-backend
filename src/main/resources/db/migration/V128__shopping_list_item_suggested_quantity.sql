-- =================================================================================================
-- V128 - what a recalculation WOULD have written into a row the master corrected by hand
-- (shopping-list iteration, follow-up on V126).
--
-- V126 got the money rule right - a hand-edited quantity is never overwritten, because the master's
-- number beats ours - but it dropped the new figure on the floor. He was told nothing, so the one
-- case where our arithmetic had actually improved (he fixed a typo in the estimate and re-ran) was
-- indistinguishable from the case where it had not. Park the figure instead and let him take it
-- with one tap.
--
-- NULL is the normal state: it means "the calculation agrees with him, or has not run since".
-- The column is cleared the moment he decides - by taking our number, by keeping his, or simply by
-- typing a new one - so a stale suggestion can never outlive the decision that answered it.
-- =================================================================================================

ALTER TABLE shopping_list_item
    ADD COLUMN suggested_quantity NUMERIC(15, 3);

ALTER TABLE shopping_list_item
    ADD CONSTRAINT shopping_list_item_suggested_quantity_positive
        CHECK (suggested_quantity IS NULL OR suggested_quantity > 0);

COMMENT ON COLUMN shopping_list_item.suggested_quantity IS
    'What the last recalculation would have written into this hand-edited row. NULL = nothing to '
    'offer. Only an OPEN, edited CALCULATOR row ever carries one; a settled row takes its '
    'difference as a separate delta row instead.';
