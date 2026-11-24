-- =================================================================================================
-- V223 — the «country» an oblast factor is measured against (plan-regional-prices.md, step 1)
--
-- V222's factor compared an oblast's masters with the national median of every master. Masters are
-- not spread evenly — most of them work in a few oblasts — so that «country» leaned towards those
-- oblasts, and a factor read «cheaper than the country» when it meant «cheaper than Kyiv and Lviv».
--
-- The baseline is one vote per OBLAST: per position, each oblast's median of its own masters, then
-- the median of those, kept only when 3+ oblasts price the position. Masters without an oblast take
-- no part (they have no vote). The national V94 median is untouched — it still drives the admin's
-- «Від майстрів» proposals for the default catalog.
--
-- Stored, not just used on the way: step 2's «орієнтовно» price is THIS baseline × the factor, and
-- the two must come from the same run.
-- =================================================================================================

CREATE TABLE regional_baseline (
    id            uuid PRIMARY KEY,
    name_key      varchar(512)   NOT NULL,
    sample_name   varchar(255)   NOT NULL,
    item_type     varchar(20)    NOT NULL,
    unit          varchar(20)    NOT NULL,
    median_price  numeric(15, 2) NOT NULL,
    regions_count integer        NOT NULL,
    computed_at   timestamptz    NOT NULL,
    CONSTRAINT regional_baseline_regions_check CHECK (regions_count >= 3),
    CONSTRAINT regional_baseline_price_check CHECK (median_price > 0),
    CONSTRAINT ux_regional_baseline UNIQUE (name_key, item_type, unit)
);

COMMENT ON TABLE regional_baseline IS
    'Per position: median of the oblasts'' own medians (one vote per oblast, 3+ oblasts). The base of regional_index.factor.';
COMMENT ON COLUMN regional_index.factor IS
    'Median over the oblast''s masters (3+, IQR-trimmed) of each master''s median price ÷ regional_baseline, positions with a baseline only.';
