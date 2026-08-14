-- The client portal splits into two contexts by WHERE it was shared from — the prod confusion
-- this fixes: a master ticking estimates from «Кошторис» (for signing) and later ticking a signed
-- act from «Економіка» (with payments) both fed the SAME link and the SAME
-- estimates.portal_visible flag, so the two intents ended up mixed on one page. Same fix shape as
-- V75 (a third value in the same CHECK, same table) — tokens/expiry/revocation stay solved once.
--
-- ECONOMY joins PORTAL/MESSAGE: opens the object's economy view — chosen SIGNED acts, a summary
-- panel, and (opt-in, same payments_visible column) a compact payments card. PORTAL keeps its
-- existing meaning (any-status estimates, for signing) and — as of this same iteration's code
-- change, not this migration — stops ever showing payments at all; that boundary is enforced in
-- PublicEstimateService, not the schema.

ALTER TABLE project_share_links DROP CONSTRAINT project_share_links_kind_check;
ALTER TABLE project_share_links
    ADD CONSTRAINT project_share_links_kind_check CHECK (kind IN ('PORTAL', 'MESSAGE', 'ECONOMY'));

-- Which estimates the ECONOMY portal shows — deliberately a SECOND flag, not a repurposed
-- `portal_visible`. The two questions are genuinely independent: an estimate can need to be on
-- the ECONOMY portal (it's signed, the client should see it in the money summary) while having no
-- business on the PORTAL (there is nothing left to sign), and the reverse (a fresh DRAFT the
-- master is sharing for signature has no place in an economy summary yet). One boolean cannot
-- answer two independent questions without one tab's action silently undoing the other's.
ALTER TABLE estimates ADD COLUMN economy_visible BOOLEAN NOT NULL DEFAULT FALSE;

-- Continuity (same reasoning as V62's own backfill): before this iteration, the single combined
-- portal already let a master publish a SIGNED estimate from what the UI called the «Економіка»
-- tab — those rows are already `portal_visible = TRUE`. Carry that same set of estimates into the
-- new economy_visible flag so nobody's already-published economy summary goes blank the moment
-- this ships. portal_visible is left untouched on those rows — the code, not this migration, is
-- what stops a SIGNED estimate's read-only PORTAL rendering from ever showing payments; the row
-- being visible in both places is harmless (a signed section on PORTAL already renders read-only
-- today, `signature != null` disables its sign button).
UPDATE estimates SET economy_visible = TRUE WHERE portal_visible = TRUE AND status = 'SIGNED';
