-- The work-act portal (acts iteration, prompt 5) — a fourth share-link kind. Unlike PORTAL/ECONOMY
-- (one link per object, a master-chosen SET of estimates), an ACT link points at exactly ONE act:
-- the act is signed personally, it is a document, not a list. So the link carries work_act_id, and
-- the public read resolves the act from the token directly — it never guesses "the" act of the
-- object. Same CHECK-widening shape as V75/V102 (a fourth value in the same constraint).

ALTER TABLE project_share_links DROP CONSTRAINT project_share_links_kind_check;
ALTER TABLE project_share_links
    ADD CONSTRAINT project_share_links_kind_check CHECK (kind IN ('PORTAL', 'MESSAGE', 'ECONOMY', 'ACT'));

-- The act an ACT link opens. NULL for every other kind; required for ACT (invariant below). ON
-- DELETE CASCADE: a DRAFT/REJECTED act can be deleted (prompt 3), and its dangling link should go
-- with it — a SENT/SIGNED act is never deleted, so a live link never loses its target underfoot.
ALTER TABLE project_share_links
    ADD COLUMN work_act_id UUID REFERENCES work_act(id) ON DELETE CASCADE;

-- An ACT link always names its act; a non-ACT link never does.
ALTER TABLE project_share_links
    ADD CONSTRAINT project_share_links_act_chk
    CHECK ((kind = 'ACT') = (work_act_id IS NOT NULL));

CREATE INDEX idx_project_share_links_work_act ON project_share_links(work_act_id);
