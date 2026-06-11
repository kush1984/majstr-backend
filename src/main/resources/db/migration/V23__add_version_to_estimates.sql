-- Optimistic-locking column for estimates (JPA @Version). Guards against
-- concurrent updates racing each other — most importantly two parallel portal
-- sign requests, where check-then-set alone would let both succeed.
ALTER TABLE estimates
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
