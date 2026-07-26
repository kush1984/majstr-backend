-- Stop counting REJECTED estimates as object income, and repair the data V57 broke.
--
-- V57 flipped economy counting to default-ON with a blanket backfill:
--     UPDATE estimates SET count_in_economy = TRUE WHERE count_in_economy = FALSE;
-- Its comment justified blanket TRUE only for CONSOLIDATED estimates. But the prior model
-- (V51) deliberately backfilled TRUE only WHERE status = 'SIGNED', so rejected variants had
-- been excluded — and V57 swept them back in. Since `sumIncomeCounted` filtered on the flag
-- alone, every master with a rejected estimate has been seeing INFLATED object income ever
-- since, with no hint why; the only remedy was to untick it by hand.
--
-- Two halves, and both are needed:
--   1. this data patch, which fixes what masters see today;
--   2. an `AND e.status <> 'REJECTED'` guard in the counted-income queries, so a rejected
--      estimate can never be income again even if something flags it later.

UPDATE estimates
SET count_in_economy = FALSE
WHERE status = 'REJECTED' AND count_in_economy = TRUE;
