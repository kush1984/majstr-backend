-- Lifetime object counter — the FREE object cap counts objects EVER created, not just those that
-- currently exist, so deleting a completed/cancelled object can't be used to slip past the limit.
-- Existing accounts are seeded from their current object count (past deletions are unknowable, and
-- punishing users for objects we can no longer see would be unfair); from now on every create
-- increments it and no delete ever decrements it.
ALTER TABLE users ADD COLUMN lifetime_project_count integer NOT NULL DEFAULT 0;
UPDATE users SET lifetime_project_count = (
    SELECT COUNT(*) FROM projects p WHERE p.owner_id = users.id
);
