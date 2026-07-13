-- Anti-abuse: a canonical form of each user's email used ONLY for duplicate-account
-- detection (the login address stays exactly what the user typed). Collapses gmail
-- aliases (dots + "+tag") so j.o.hn+2@gmail.com can't sidestep john@gmail.com.
-- The app (EmailPolicyService) owns the canonical for new/changed emails; this
-- migration backfills existing rows with the same rule for Google, lowercase for the
-- rest. Index is NON-unique on purpose: any pre-existing legacy duplicates are
-- grandfathered (the constraint would fail on them); new collisions are caught by an
-- application pre-check (existsByEmailCanonical).

ALTER TABLE users ADD COLUMN email_canonical VARCHAR(255);

UPDATE users SET email_canonical =
    CASE
        WHEN lower(split_part(email, '@', 2)) IN ('gmail.com', 'googlemail.com')
            THEN replace(split_part(split_part(lower(email), '@', 1), '+', 1), '.', '') || '@gmail.com'
        ELSE lower(email)
    END
WHERE email_canonical IS NULL;

ALTER TABLE users ALTER COLUMN email_canonical SET NOT NULL;

CREATE INDEX idx_users_email_canonical ON users(email_canonical);
