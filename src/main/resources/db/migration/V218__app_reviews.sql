-- =================================================================================================
-- V218 — masters rate Majstr itself (iteration-app-reviews)
--
-- Not a review of a master by his client: a master's rating of the APP. Two jobs at once:
-- 1. What to fix — every rating reaches the admin, a low one with the master's own words.
-- 2. Proof for the next master — a high one he AGREED to show goes on majstr.pro next to the
--    figures that make it believable (how long he has used Majstr, estimates, signatures).
--
-- Asked from the profile at any time, and once in a while on the home screen after he has had a
-- success (an estimate sent or signed) — never in his first week. `users.review_prompt_at` is when
-- he was last asked (answered or «Не зараз»), so the home screen asks again no sooner than
-- AppReviewService.PROMPT_EVERY.
-- =================================================================================================

CREATE TABLE app_review (
    id              uuid PRIMARY KEY,
    user_id         uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    rating          smallint     NOT NULL,
    text            varchar(2000),
    source          varchar(20)  NOT NULL,
    publish_consent boolean      NOT NULL DEFAULT false,
    status          varchar(20)  NOT NULL DEFAULT 'NEW',
    created_at      timestamptz  NOT NULL DEFAULT now(),
    published_at    timestamptz,
    CONSTRAINT app_review_rating_check CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT app_review_source_check CHECK (source IN ('PROFILE', 'PROMPT')),
    CONSTRAINT app_review_status_check CHECK (status IN ('NEW', 'PUBLISHED', 'HIDDEN')),
    -- Nothing he did not agree to show is ever shown.
    CONSTRAINT app_review_published_consent_check CHECK (status <> 'PUBLISHED' OR publish_consent),
    CONSTRAINT app_review_published_at_check CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);

CREATE INDEX ix_app_review_created ON app_review (created_at DESC);
CREATE INDEX ix_app_review_user ON app_review (user_id);
CREATE INDEX ix_app_review_published ON app_review (published_at DESC) WHERE status = 'PUBLISHED';

ALTER TABLE users ADD COLUMN review_prompt_at timestamptz;
