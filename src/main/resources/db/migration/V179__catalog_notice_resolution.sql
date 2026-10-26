-- =================================================================================================
-- V179 — HOW a catalog update notice was closed (inline editing, stage 3)
--
-- `dismissed_at` says WHEN a notice left the queue, not why. Owner ruling (2026-10-26): when a master
-- changes a position's price himself (in the row, from the estimate's catalog card, or in the
-- dialog), his open PRICE_DRIFT notice about it is closed as RESOLVED — the question it asked has
-- been answered — not deleted, and not mistaken for a «Лишити як є». So:
--   DISMISSED — «Лишити як є» / «Зрозуміло»;
--   ACCEPTED  — «Оновити мою ціну» (which now applies the new price always — an explicit tap is an
--               explicit intent);
--   RESOLVED  — the master set the price himself.
-- Rows closed before today keep NULL: nobody recorded why, and guessing would be inventing history.
-- =================================================================================================

ALTER TABLE catalog_update_notices
    ADD COLUMN resolution varchar(20);

ALTER TABLE catalog_update_notices
    ADD CONSTRAINT catalog_update_notices_resolution_check
        CHECK (resolution IS NULL OR resolution IN ('DISMISSED', 'ACCEPTED', 'RESOLVED'));

-- A resolution only ever comes with the moment the notice left the queue.
ALTER TABLE catalog_update_notices
    ADD CONSTRAINT catalog_update_notices_resolution_dismissed_check
        CHECK (resolution IS NULL OR dismissed_at IS NOT NULL);
