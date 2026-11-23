-- =================================================================================================
-- V219 — where a master works (iteration-regions)
--
-- The first step to prices by region: nothing reads it yet but the master's own profile. Asked now,
-- so that by the time «ринкова ціна» can be split by oblast there are months of answers behind it —
-- a median needs at least three masters per position per region, and the regions fill slowly.
-- The 24 oblasts and Kyiv city. NULL = not answered yet (the home screen asks once).
-- =================================================================================================

ALTER TABLE users ADD COLUMN region varchar(30);

ALTER TABLE users ADD CONSTRAINT users_region_check CHECK (region IN ('VINNYTSIA', 'VOLYN', 'DNIPRO', 'DONETSK', 'ZHYTOMYR', 'ZAKARPATTIA', 'ZAPORIZHZHIA', 'IVANO_FRANKIVSK', 'KYIV_OBLAST', 'KIROVOHRAD', 'LUHANSK', 'LVIV', 'MYKOLAIV', 'ODESA', 'POLTAVA', 'RIVNE', 'SUMY', 'TERNOPIL', 'KHARKIV', 'KHERSON', 'KHMELNYTSKYI', 'CHERKASY', 'CHERNIVTSI', 'CHERNIHIV', 'KYIV_CITY'));

CREATE INDEX ix_users_region ON users (region) WHERE region IS NOT NULL;
