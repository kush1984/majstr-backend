-- =================================================================================================
-- V173 — the Ліга Майстрів meetup of 11 November as a referral source of its own.
--
-- Same shape and the same guard as V172 (YouTube): code LIGA-MEETUP (matched case-insensitively, so
-- ?ref=liga-meetup and the promo code «liga-meetup» both work) stamps users.referral_source =
-- 'LIGA_MEETUP', a source apart from LIGA so the meetup's sign-ups can be counted on their own.
-- Nothing is inserted when a row already answers to that code in any case — a second match would
-- fail every registration that carries it.
-- =================================================================================================

INSERT INTO partners (id, code, source, name, active, created_at)
SELECT gen_random_uuid(), 'LIGA-MEETUP', 'LIGA_MEETUP', 'Ліга Майстрів — мітап 11 листопада', TRUE, now()
WHERE NOT EXISTS (SELECT 1 FROM partners WHERE lower(code) = 'liga-meetup');
