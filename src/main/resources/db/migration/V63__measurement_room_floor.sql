-- Floor as a plain room attribute (free text: «1», «2», «цоколь», «мансарда») —
-- deliberately NOT a new hierarchy level. NULL = no floor; rooms without one
-- keep rendering ungrouped, so existing measurements are untouched.
ALTER TABLE measurement_room ADD COLUMN floor VARCHAR(20);
