-- Провенанс замороженого «%» рядка при зведенні кошторисів.
--
-- copyForConsolidation() freezes a PERCENT line's amount (correct — recalculating against the
-- merged subtotal would silently give the client a discount he never signed) but the row then
-- reads «10 % від 3 450 ₴» with no hint of what «3 450» is or where the line came from. A snapshot,
-- not a FK (the source estimate/position may later be edited or deleted — same pattern as
-- estimate_items.source_unit_price / source_item_id, V85).
ALTER TABLE estimate_items
    ADD COLUMN base_origin_label TEXT;
