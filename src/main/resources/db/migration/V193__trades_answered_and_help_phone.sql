-- =================================================================================================
-- V193 — review of registration round 2.
--
-- 1. `trades_onboarded_at`: when the master ANSWERED «Чим займаєтесь?» — picked trades or skipped. The
--    PWA kept the skip in localStorage, so the screen came back on every other phone, and a master who
--    later unticked every trade in his profile was sent to it again; «no trades» also mixed «skipped»
--    with «never got there». Stamped by PUT /api/profile/trades (an empty set is the skip), by a
--    profile save, and at registration when an older build sent trades. Backfilled from `created_at`
--    for every master who already has a system or a custom trade — before V190 a trade was required.
--
-- 2. `help_phone`: the number left on «Залиште телефон — передзвонимо» (V191). It went into
--    `users.phone`, which documents print — a personal number given for one call would have reached
--    clients. Its own column keeps it to the purpose the privacy policy names.
--
-- 3. `help_handled_at`: the admin marks a call done, so the list stays a to-do list.
-- =================================================================================================

ALTER TABLE users ADD COLUMN trades_onboarded_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN help_phone VARCHAR(50);
ALTER TABLE users ADD COLUMN help_handled_at TIMESTAMPTZ;

UPDATE users u
SET trades_onboarded_at = u.created_at
WHERE u.trades_onboarded_at IS NULL
  AND (EXISTS (SELECT 1 FROM user_trades t WHERE t.user_id = u.id)
       OR EXISTS (SELECT 1 FROM user_trade c WHERE c.user_id = u.id));

-- The phone a V191 ask wrote into users.phone moves to its own column only where the profile had no
-- phone of its own before (the card was offered only then); the documents' phone is left alone.
UPDATE users SET help_phone = phone WHERE help_requested_at IS NOT NULL AND help_phone IS NULL;
