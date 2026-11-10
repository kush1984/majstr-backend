-- =================================================================================================
-- V197 — the training sample «Приклад: …» (iteration-sample-object).
--
-- A newcomer afraid of losing one of his two free objects (V107 counts every object ever made) does not
-- try the app; at a webinar he makes a test object and loses half of the free plan. The sample is an
-- object the server fills itself — a priced estimate for his trade — that the cap does not count, that is
-- deleted freely (no B-70 guard), and that no figure reads: the dashboard, «Мої гроші», the admin
-- funnel and lists, broadcast segments, community prices.
--
-- One LIVE sample per account (the partial unique index); deleting it lets the master see it again.
-- =================================================================================================

ALTER TABLE projects ADD COLUMN sample BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX ux_projects_one_sample_per_owner ON projects (owner_id) WHERE sample;

COMMENT ON COLUMN projects.sample IS
    'The training sample (V197): outside the FREE object cap, deletable with a signature or money, read by no metric. One live per owner.';
