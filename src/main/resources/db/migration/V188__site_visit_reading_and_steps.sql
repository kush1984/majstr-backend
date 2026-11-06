-- Visit statistics, round 2: what a visitor does between arriving and registering (V187 counted only both ends).
--
-- New kinds: LEAVE carries how far the page was read (scroll_depth, per cent of the page that reached the
-- bottom of the screen) and the seconds it was actually on screen; CLICK names what was pressed (a label set
-- on the element, or derived from a link's own path); REGISTER_FIELD / REGISTER_SUBMIT / REGISTER_ERROR walk
-- the registration form by field name and error code. Never a value that was typed.
ALTER TABLE site_visit
    ADD COLUMN target          VARCHAR(60),
    ADD COLUMN scroll_depth    SMALLINT CHECK (scroll_depth BETWEEN 0 AND 100),
    ADD COLUMN engaged_seconds INTEGER  CHECK (engaged_seconds >= 0);

ALTER TABLE site_visit DROP CONSTRAINT site_visit_kind_check;
ALTER TABLE site_visit ADD CONSTRAINT site_visit_kind_check CHECK (kind IN (
    'PAGEVIEW', 'REGISTER_STARTED', 'LEAVE', 'CLICK', 'REGISTER_FIELD', 'REGISTER_SUBMIT', 'REGISTER_ERROR'));
