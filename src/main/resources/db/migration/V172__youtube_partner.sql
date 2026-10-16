-- =================================================================================================
-- V172 — YouTube as a referral source of its own, so ?ref=youtube counts.
--
-- A ?ref= value resolves through the `partners` registry (V38, ReferralService); a code it does not
-- know falls through to DIRECT, so links from the channel's videos were landing in DIRECT and the
-- admin's by-source funnel could not show them. One row makes the link count: code YOUTUBE (matched
-- case-insensitively, so ?ref=youtube works) stamps users.referral_source = 'YOUTUBE'.
--
-- Guarded rather than a plain INSERT: a partner is DATA and can be added by hand, and `code` is
-- unique only case-SENSITIVELY. A second row matching «youtube» in another case would make
-- `findByCodeIgnoreCaseAndActiveTrue` find two and fail every registration that carries the link.
-- So nothing is inserted when any row already answers to that code.
-- =================================================================================================

INSERT INTO partners (id, code, source, name, active, created_at)
SELECT gen_random_uuid(), 'YOUTUBE', 'YOUTUBE', 'YouTube', TRUE, now()
WHERE NOT EXISTS (SELECT 1 FROM partners WHERE lower(code) = 'youtube');
