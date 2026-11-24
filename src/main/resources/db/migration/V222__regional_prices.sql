-- =================================================================================================
-- V222 — prices by region, step 1: computed, not shown (plan-regional-prices.md)
--
-- The weekly community-price run (V94) now also splits by the master's oblast (V219 users.region).
-- Nothing a master sees reads these tables yet: the admin looks at how much data each oblast has
-- before a viewer or regional seeding is built on it.
--
--   regional_price — level 1: the median of one position in one oblast, 3+ masters after the trim.
--   regional_index — level 2: one factor per oblast, the oblast's masters' prices against the
--                    national median over every position both have («орієнтовно» when shown).
--
-- Both are rebuilt from scratch on every run (delete + insert), like price_insight_candidate.
-- =================================================================================================

CREATE TABLE regional_price (
    id            uuid PRIMARY KEY,
    region        varchar(30)    NOT NULL,
    name_key      varchar(512)   NOT NULL,
    sample_name   varchar(255)   NOT NULL,
    item_type     varchar(20)    NOT NULL,
    unit          varchar(20)    NOT NULL,
    median_price  numeric(15, 2) NOT NULL,
    masters_count integer        NOT NULL,
    min_price     numeric(15, 2) NOT NULL,
    max_price     numeric(15, 2) NOT NULL,
    computed_at   timestamptz    NOT NULL,
    CONSTRAINT regional_price_region_check CHECK (region IN ('VINNYTSIA', 'VOLYN', 'DNIPRO', 'DONETSK', 'ZHYTOMYR', 'ZAKARPATTIA', 'ZAPORIZHZHIA', 'IVANO_FRANKIVSK', 'KYIV_OBLAST', 'KIROVOHRAD', 'LUHANSK', 'LVIV', 'MYKOLAIV', 'ODESA', 'POLTAVA', 'RIVNE', 'SUMY', 'TERNOPIL', 'KHARKIV', 'KHERSON', 'KHMELNYTSKYI', 'CHERKASY', 'CHERNIVTSI', 'CHERNIHIV', 'KYIV_CITY')),
    CONSTRAINT regional_price_masters_check CHECK (masters_count >= 3),
    CONSTRAINT ux_regional_price UNIQUE (region, name_key, item_type, unit)
);

CREATE TABLE regional_index (
    region          varchar(30)   PRIMARY KEY,
    factor          numeric(6, 3) NOT NULL,
    masters_count   integer       NOT NULL,
    positions_count integer       NOT NULL,
    computed_at     timestamptz   NOT NULL,
    CONSTRAINT regional_index_region_check CHECK (region IN ('VINNYTSIA', 'VOLYN', 'DNIPRO', 'DONETSK', 'ZHYTOMYR', 'ZAKARPATTIA', 'ZAPORIZHZHIA', 'IVANO_FRANKIVSK', 'KYIV_OBLAST', 'KIROVOHRAD', 'LUHANSK', 'LVIV', 'MYKOLAIV', 'ODESA', 'POLTAVA', 'RIVNE', 'SUMY', 'TERNOPIL', 'KHARKIV', 'KHERSON', 'KHMELNYTSKYI', 'CHERKASY', 'CHERNIVTSI', 'CHERNIHIV', 'KYIV_CITY')),
    CONSTRAINT regional_index_factor_check CHECK (factor > 0),
    CONSTRAINT regional_index_masters_check CHECK (masters_count >= 3)
);
