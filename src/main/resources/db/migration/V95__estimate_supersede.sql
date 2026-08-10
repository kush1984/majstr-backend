-- Economy-rework iteration: replaces the "difference (negative)" double-count workaround with an
-- honest supersede. Scenario: estimate A is SIGNED, the master duplicates it with a discount → B,
-- the client signs B too. Both being SIGNED at once meant the economy either counted A's full price
-- AND some negative "difference" from B, or silently disagreed about which deal was real.
--
-- The fix: signing a duplicate whose parent is still SIGNED auto-reopens the parent to DRAFT (the
-- existing reopen path, called as a system step, not the owner-only endpoint) and stamps which
-- estimate did it. A DRAFT estimate is not an "act" — it simply stops appearing in the economy at
-- all, so nothing downstream needs to know about supersession; this column exists only to drive a
-- one-line warning banner on the Кошторис tab, telling the master what happened and why A is back
-- in drafts, so he can decide to delete it or keep it as a record.
ALTER TABLE estimates
    -- ON DELETE SET NULL: deleting B (the estimate that superseded A) must not corrupt A — the
    -- banner just stops finding a name to show and clears itself away, same spirit as
    -- duplicated_from_id.
    ADD COLUMN superseded_by_estimate_id UUID REFERENCES estimates(id) ON DELETE SET NULL;

COMMENT ON COLUMN estimates.superseded_by_estimate_id IS
    'Set when this (SIGNED) estimate was auto-reopened to DRAFT because a duplicate of it got '
    'signed while it was still SIGNED — points at that duplicate. Cleared when the master edits, '
    're-signs, or dismisses the resulting banner. NULL in the ordinary case.';

CREATE INDEX idx_estimates_superseded_by ON estimates(superseded_by_estimate_id)
    WHERE superseded_by_estimate_id IS NOT NULL;
