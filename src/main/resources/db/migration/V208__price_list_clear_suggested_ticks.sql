-- The price list starts EMPTY until the master ticks it himself (owner's ruling 2026-11-14).
--
-- 1.79.0 (V205) pre-ticked every work he had priced himself, and its preview and share saved those ticks
-- into `in_price_list` — so the picker kept showing works nobody chose. The feature had been live for a
-- day and every tick in the column came from that rule, so all of them go. A live link stays; its page
-- reads «Майстер ще не додав робіт до прайсу» until he ticks again.
UPDATE catalog_items SET in_price_list = FALSE WHERE in_price_list;
