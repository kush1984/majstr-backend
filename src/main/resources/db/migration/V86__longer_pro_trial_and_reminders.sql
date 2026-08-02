-- The PRO trial goes from 5 days to 15, and everyone already on one gets the extra days.
--
-- Why: five days is barely one object. A master signs up, takes a job, and the trial lapses before
-- he has run a single estimate through to a client's signature — which is the moment PRO is
-- supposed to prove itself. Ending the trial before the product has had a chance to work is not a
-- conversion strategy, it is a missed one.
--
-- The trial LENGTH itself lives in config (app.billing.trial-days), so this migration only fixes up
-- the masters who are mid-trial right now.

ALTER TABLE users
    -- When the daily "your trial is ending" reminder last went out. Distinct from
    -- renew_reminder_sent_at, which guards the auto-renew mail and is cleared on every extension:
    -- this one is compared against TODAY, because the trial reminder is sent once a day for the
    -- last three days rather than once per cycle.
    ADD COLUMN trial_reminder_sent_at TIMESTAMPTZ;

DO $$
DECLARE
    v_extended int;
BEGIN
    -- ============ WHO IS TOUCHED ==============================================
    --   · took the trial (trial_started_at IS NOT NULL);
    --   · is still on it — a paid plan that has not run out yet;
    --   · has NEVER paid. A master who bought PRO is on a subscription, not a trial, and his
    --     expiry date is something he paid for — moving it would be giving away a month.
    -- ==========================================================================
    UPDATE users u
    SET plan_expires_at = GREATEST(u.plan_expires_at, u.trial_started_at + INTERVAL '15 days')
    WHERE u.trial_started_at IS NOT NULL
      AND u.plan <> 'FREE'
      AND u.plan_expires_at IS NOT NULL
      AND u.plan_expires_at > now()
      AND NOT EXISTS (
          SELECT 1 FROM payments p
          WHERE p.user_id = u.id AND p.status = 'SUCCESS');
    GET DIAGNOSTICS v_extended = ROW_COUNT;

    -- GREATEST, not a plain assignment: a trial master may also hold days from a referral reward
    -- (30 days, granted on someone else's first payment), and recomputing from trial_started_at
    -- alone would take those away. This migration can only ever ADD time.
    --
    -- Deliberately NOT touched: trials that have already lapsed. «Хто зараз є на пробній» means the
    -- ones running now — reviving an expired trial would hand PRO back to people who already
    -- decided against it, silently and without them asking.
    RAISE NOTICE 'V86: extended % live trial(s) to 15 days', v_extended;
END $$;
