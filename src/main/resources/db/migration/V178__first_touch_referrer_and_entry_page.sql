-- First-touch referrer host and entry page, stamped at registration beside the UTM tags (V114).
--
-- Search traffic carries no UTM — Google sends none — so it fell into «без UTM» together with
-- everyone who typed the address by hand, and nothing could say whether the public calculator
-- pages bring anyone. The PWA now records, once on a visitor's first page, the HOST of the page
-- that linked to us (never the full URL) and the PATH he landed on (never the query), and sends
-- both with registration.
--
-- NULL is a legitimate value in both, as with utm_source: no referrer = typed, bookmarked, or an
-- app that strips it; no entry page = registered from a build older than this column. Stamped
-- ONCE and never overwritten.
ALTER TABLE users ADD COLUMN first_referrer_host VARCHAR(100);
ALTER TABLE users ADD COLUMN first_entry_path    VARCHAR(100);

-- The admin by-source report groups on both.
CREATE INDEX idx_users_first_referrer_host ON users (first_referrer_host);
CREATE INDEX idx_users_first_entry_path ON users (first_entry_path);
