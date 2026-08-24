-- First-touch UTM tags, captured at registration alongside the ?ref= partner code.
--
-- Deliberately separate from users.referral_source: `ref` is a PARTNER (money, rev-share, the
-- `partners` registry), UTM is a CHANNEL (TikTok, Telegram, an article). A master can arrive on
-- Ліга Майстрів' partner link FROM TikTok — folding both into one column loses a dimension.
--
-- NULL is a legitimate value here ("arrived with no tags") and is why there is no DEFAULT: a
-- sentinel like 'DIRECT' would merge "no tags at all" with "a tag that said direct". Reports must
-- render the NULL bucket explicitly ("без UTM"), never as an empty cell.
--
-- Stamped ONCE at registration and never overwritten — the same law as referral_source.
ALTER TABLE users ADD COLUMN utm_source   VARCHAR(60);
ALTER TABLE users ADD COLUMN utm_medium   VARCHAR(60);
ALTER TABLE users ADD COLUMN utm_campaign VARCHAR(100);

-- The admin by-UTM report groups on utm_source; the other two are read per user.
CREATE INDEX idx_users_utm_source ON users (utm_source);
