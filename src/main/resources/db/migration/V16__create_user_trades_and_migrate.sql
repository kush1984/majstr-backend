-- User.trade (single) -> User.trades (multi). Generalist contractors do
-- several kinds of work. Move the value into a join table, preserving each
-- existing user's current trade as a single membership, then drop the old
-- column. CRITICAL: every existing user must keep their trade.

CREATE TABLE user_trades (
    user_id UUID         NOT NULL,
    trade   VARCHAR(50)  NOT NULL,
    CONSTRAINT user_trades_pk PRIMARY KEY (user_id, trade),
    CONSTRAINT user_trades_user_fk FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT user_trades_trade_check
        CHECK (trade IN ('ELECTRICAL', 'PLUMBING', 'TILING', 'GENERAL', 'OTHER'))
);

-- Carry over existing data: one membership row per user from the old column.
INSERT INTO user_trades (user_id, trade)
SELECT id, trade FROM users;

-- Old single-valued column is now redundant. Dropping it also drops the
-- dependent users_trade_check constraint automatically.
ALTER TABLE users DROP COLUMN trade;
