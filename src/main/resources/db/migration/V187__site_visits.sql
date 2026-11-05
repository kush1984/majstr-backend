-- Anonymous visit statistics for the public pages: the landing, /register and the SEO pages.
--
-- Until now a visitor who never registered left no trace at all, so the admin could count sign-ups
-- per channel but not the visits they came out of. The page's own browser reports one row per page
-- it shows a logged-out visitor, plus one when the registration form is first typed into.
--
-- Nothing here identifies a person. There is no IP column and no cookie: «visitor» is a hash of the
-- address and the browser string under a salt that lives for one Kyiv day (site_visit_salt) and is
-- deleted after it, so a visitor can be counted once per day and never recognised again. The source
-- columns are the visitor's FIRST touch, the same values registration stores on users (V114, V178),
-- so a channel's visitors and its registrations are counted by one key.
CREATE TABLE site_visit (
    id            BIGSERIAL    PRIMARY KEY,
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    day           DATE         NOT NULL,
    kind          VARCHAR(20)  NOT NULL CHECK (kind IN ('PAGEVIEW', 'REGISTER_STARTED')),
    visitor       VARCHAR(16)  NOT NULL,
    path          VARCHAR(100) NOT NULL,
    referrer_host VARCHAR(100),
    entry_path    VARCHAR(100),
    utm_source    VARCHAR(60),
    utm_medium    VARCHAR(60),
    utm_campaign  VARCHAR(100),
    device        VARCHAR(10)  NOT NULL,
    os            VARCHAR(10)  NOT NULL,
    browser       VARCHAR(12)  NOT NULL,
    time_zone     VARCHAR(64),
    language      VARCHAR(16)
);

-- Every report reads a day range and attributes each (day, visitor) to its earliest row.
CREATE INDEX ix_site_visit_day ON site_visit (day, visitor, occurred_at);

CREATE TABLE site_visit_salt (
    day  DATE  PRIMARY KEY,
    salt BYTEA NOT NULL
);
